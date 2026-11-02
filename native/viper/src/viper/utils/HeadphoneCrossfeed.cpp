#include "HeadphoneCrossfeed.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <complex>

namespace headphone {
namespace {
constexpr double pi=3.14159265358979323846;
float clean(float x){return std::isfinite(x)?x:0.f;}
int db(double x){return x>0?static_cast<int>(std::llround(2000*std::log10(x))):std::numeric_limits<int32_t>::min();}
}
bool valid(const int32_t* v,size_t n){
    if(!v || n!=kWords)return false;
    const int lo[kWords]={1,0,300,10,0,0,-120,0},hi[kWords]={1,1,2000,150,100,500,0,1};
    for(unsigned i=0;i<kWords;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return true;
}
bool validTools(const int32_t* v,size_t n){
    return v && n==kToolsWords && v[0]>=0 && v[0]<=6 && v[1]>=0 && v[1]<=1 && v[2]>=0 &&
        valid(v+3,kWords) && valid(v+3+kWords,kWords) &&
        (v[0]<4 || (v[2]>0 && v[4]==1 && v[4+kWords]==1));
}
bool validAdvanced(const int32_t* v,size_t n){
    if(!v || n!=kAdvancedWords)return false;
    constexpr int lo[]={1,0,0,0,10,10,0,0,0,1,0},hi[]={1,1,100,1,150,150,500,500,1,4,1};
    for(unsigned i=0;i<n;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return true;
}
bool validAdvancedTools(const int32_t* v,size_t n){
    constexpr unsigned stride=kWords+kAdvancedWords;
    return v && n==kAdvancedToolsWords && v[0]>=0 && v[0]<=6 && v[1]>=0 && v[1]<=1 && v[2]>=0 &&
        valid(v+3,kWords) && validAdvanced(v+3+kWords,kAdvancedWords) &&
        valid(v+3+stride,kWords) && validAdvanced(v+3+stride+kWords,kAdvancedWords) &&
        (v[0]<4 || (v[2]>0 && v[4]==1 && v[4+stride]==1));
}
void Kernel::configure(const Settings& v,uint32_t fs,const Advanced& adv){
    settings=v;previous={};low={};high={};history={};position=0;
    advanced=adv;rate=fs;extraLow={};stages=adv[8]?adv[9]:1;
    // Keep the 0.1 dB level as floating point. The legacy Crossfeed deliberately stays unchanged.
    const double level=v[3]/10.0,gbLo=-level*5/6-3,gbHi=level/6-3;
    const double gLo=std::pow(10.,gbLo/20),gHi=1-std::pow(10.,gbHi/20);
    const double fcHi=v[2]*std::pow(2.,(gbLo-20*std::log10(gHi))/12);
    b1lo=std::exp(-2*pi*v[2]/fs);a0lo=gLo*(1-b1lo);
    b1hi=std::exp(-2*pi*fcHi/fs);a0hi=1-gHi*(1-b1hi);a1hi=-b1hi;
    gain=1/(1-gHi+gLo);mix=v[4]/100.;trim=std::pow(10.,v[6]/200.);delay=v[5]*fs/1000000.;
    for(unsigned c=0;c<2;++c){
        const unsigned direction=c==1?0:1; // Destination R is the L -> R path.
        crossGain[c]=adv[3]?std::pow(10.,(v[3]-adv[4+direction])/200.):1.;
        pathDelay[c]=(adv[3]?adv[6+direction]:v[5])*fs/1000000.;
    }
    // Original 10 Hz / three first-order 18 kHz filters, bounded below Nyquist at low host rates.
    for(auto* bank:{&post,&crossPost})for(auto& channel:*bank){
        channel[0].setHPF_BW(10,fs);channel[0].Mute();
        for(unsigned i=1;i<4;++i){channel[i].setLPF_BW(std::min(18000.f,fs*.45f),fs);channel[i].Mute();}
    }
}
void Kernel::process(const float* in,float* out,float* cross){
    if(!settings[1]){out[0]=in[0];out[1]=in[1];cross[0]=cross[1]=0;return;}
    for(unsigned c=0;c<2;++c){
        const double x=clean(in[c]);low[c]=a0lo*x+b1lo*low[c];
        high[c]=a0hi*x+a1hi*previous[c]+b1hi*high[c];previous[c]=x;
        double filtered=low[c];
        for(unsigned i=1;i<stages;++i){extraLow[c][i-1]=(1-b1lo)*filtered+b1lo*extraLow[c][i-1];filtered=extraLow[c][i-1];}
        history[c][position]=static_cast<float>(filtered);
    }
    std::array<double,2> delayed{};
    for(unsigned c=0;c<2;++c){
        const unsigned integer=static_cast<unsigned>(pathDelay[c]);const double fraction=pathDelay[c]-integer;
        const auto& ring=history[1-c];
        // Linear fractional delay is applied only to the already low-passed cross path.
        delayed[c]=(ring[(position+history[0].size()-integer)%history[0].size()]*(1-fraction)+ring[(position+history[0].size()-1-integer)%history[0].size()]*fraction)*crossGain[c];
    }
    for(unsigned c=0;c<2;++c){
        float processed=static_cast<float>((high[c]+delayed[c])*gain);
        float bleed=static_cast<float>(delayed[c]*gain);
        if(advanced[1] && advanced[2]){
            // Preserve the input Mid channel progressively; retain the processed Side channel.
            // This is a linear M/S correction, not voice separation or headphone calibration.
            const double amount=advanced[2]/100.;
            processed=static_cast<float>((high[c]+delayed[c])*gain+amount*.5*
                (in[0]+in[1]-(high[0]+high[1]+delayed[0]+delayed[1])*gain));
            bleed=static_cast<float>(delayed[c]*gain+amount*.5*(in[1-c]-(high[1-c]+delayed[c])*gain));
        }
        if(settings[7]==0){for(auto& f:post[c])processed=do_filter(&f,processed);for(auto& f:crossPost[c])bleed=do_filter(&f,bleed);}
        out[c]=clean(static_cast<float>((in[c]*(1-mix)+processed*mix)*trim));
        cross[c]=clean(static_cast<float>(bleed*mix*trim));
    }
    position=(position+1)%history[0].size();
}
void Kernel::response(int32_t* values)const{
    using C=std::complex<double>;
    const double maximum=std::min(20000.,rate*.45);
    for(unsigned i=0;i<kResponsePoints;++i){
        const double f=20*std::pow(maximum/20.,double(i)/(kResponsePoints-1));
        const C z=std::polar(1.,-2*pi*f/rate);
        const C direct=(a0hi+a1hi*z)/(1.-b1hi*z)*gain;
        C lowpass=a0lo/(1.-b1lo*z)*gain;
        for(unsigned s=1;s<stages;++s)lowpass*=(1.-b1lo)/(1.-b1lo*z);
        std::array<C,2> cross{},d{},c{};
        for(unsigned channel=0;channel<2;++channel){
            const unsigned n=static_cast<unsigned>(pathDelay[channel]);const double frac=pathDelay[channel]-n;
            cross[channel]=lowpass*crossGain[channel]*std::pow(z,n)*((1-frac)+frac*z);
        }
        C postResponse=1.;
        if(settings[7]==0)for(const auto& filter:post[0]){
            postResponse*=(double(filter.b0)+double(filter.b1)*z)/(1.-double(filter.a1)*z);
        }
        const double amount=advanced[1]?advanced[2]/100.:0.;
        for(unsigned channel=0;channel<2;++channel){
            d[channel]=((1.-mix)+mix*postResponse*(direct+amount*.5*(1.-direct-cross[1-channel])))*trim;
            c[channel]=mix*postResponse*(cross[channel]+amount*.5*(1.-direct-cross[channel]))*trim;
        }
        const std::array<C,6> curves{d[0],d[1],c[0],c[1],d[0]+c[0],d[1]+c[1]};
        for(unsigned curve=0;curve<6;++curve)values[i*6+curve]=std::clamp(db(std::abs(curves[curve])),-18000,2400);
    }
}
Engine::Engine(){reset();}
void Engine::reset(){
    active=0;fade=0;fadeLength=std::max(1u,rate/100);clearTools();last={};toolFade=0;peakDecay=static_cast<float>(std::exp(-1./(.5*rate)));
    main[0].configure(settings,rate,advanced);main[1].configure(settings,rate,advanced);main[0].response(responseValues.data());peaks={};
}
void Engine::clearTools(){if(mode!=0)toolFade=fadeLength;mode=0;epoch=0;match=false;measurement=0;measurementFrames=0;energyIn=energyA=energyB=0;gainA=gainB=1;}
void Engine::setRate(uint32_t fs){if(fs!=rate){rate=std::clamp(fs,8000u,768000u);reset();}}
bool Engine::set(const int32_t* v,size_t n){
    if(!valid(v,n))return false;
    Settings next;std::copy_n(v,kWords,next.begin());if(next==settings)return true;
    settings=next;main[1-active].configure(settings,rate,advanced);main[1-active].response(responseValues.data());fade=fadeLength;clearTools();return true;
}
bool Engine::setAdvanced(const int32_t* v,size_t n){
    if(!validAdvanced(v,n))return false;
    Advanced next;std::copy_n(v,kAdvancedWords,next.begin());if(next==advanced)return true;
    advanced=next;main[1-active].configure(settings,rate,advanced);main[1-active].response(responseValues.data());fade=fadeLength;peaks={};clearTools();return true;
}
bool Engine::setTools(const int32_t* v,size_t n){
    if(!validTools(v,n))return false;
    std::array<int32_t,kAdvancedToolsWords> packet{};std::copy_n(v,3,packet.begin());
    std::copy_n(v+3,kWords,packet.begin()+3);std::copy(advancedDefaults.begin(),advancedDefaults.end(),packet.begin()+3+kWords);
    std::copy_n(v+3+kWords,kWords,packet.begin()+3+kWords+kAdvancedWords);
    std::copy(advancedDefaults.begin(),advancedDefaults.end(),packet.begin()+3+2*kWords+kAdvancedWords);
    return setAdvancedTools(packet.data(),packet.size());
}
bool Engine::setAdvancedTools(const int32_t* v,size_t n){
    if(!validAdvancedTools(v,n) || (v[0]!=0 && !enabled()))return false;
    constexpr unsigned stride=kWords+kAdvancedWords;
    Settings nextA,nextB;std::copy_n(v+3,kWords,nextA.begin());
    std::copy_n(v+3+stride,kWords,nextB.begin());
    Advanced advA,advB;std::copy_n(v+3+kWords,kAdvancedWords,advA.begin());std::copy_n(v+3+stride+kWords,kAdvancedWords,advB.begin());
    const bool changed=epoch!=v[2] || nextA!=a || nextB!=b || advA!=aAdvanced || advB!=bAdvanced;
    if(v[0]>=4 && v[0]<=5 && v[1] && (changed || measurement!=2))return false;
    if(changed){clearTools();epoch=v[2];a=nextA;b=nextB;aAdvanced=advA;bAdvanced=advB;comparisonA.configure(a,rate,aAdvanced);comparisonB.configure(b,rate,bAdvanced);}
    if(v[0]==6 && mode!=6){
        measurement=1;measurementFrames=0;energyIn=energyA=energyB=0;gainA=gainB=1;
        comparisonA.configure(a,rate,aAdvanced);comparisonB.configure(b,rate,bAdvanced);
    }
    if(v[0]!=mode)toolFade=fadeLength;
    if(mode==6 && v[0]!=6 && measurement==1)measurement=0;
    mode=v[0];match=v[1]!=0;return true;
}
void Engine::process(const float* in,float* out){
    float normal[2],cross[2];main[active].process(in,normal,cross);
    if(fade){float next[2],nextCross[2];main[1-active].process(in,next,nextCross);const float t=1.f-static_cast<float>(fade)/fadeLength;
        for(unsigned c=0;c<2;++c){normal[c]+=(next[c]-normal[c])*t;cross[c]+=(nextCross[c]-cross[c])*t;}
        if(--fade==0)active=1-active;
    }
    float result[2]={normal[0],normal[1]};
    if(mode==1){result[0]=in[0];result[1]=in[1];}
    else if(mode==2){for(unsigned c=0;c<2;++c)result[c]=(normal[c]-in[c])*.5f;}
    else if(mode==3){result[0]=cross[0];result[1]=cross[1];}
    if(mode>=4){
        float aOut[2],bOut[2],unused[2];comparisonA.process(in,aOut,unused);comparisonB.process(in,bOut,unused);
        if(mode==4 || mode==5)for(unsigned c=0;c<2;++c)result[c]=(mode==4?aOut[c]:bOut[c])*(match?(mode==4?gainA:gainB):1.f);
        if(mode==6 && measurement==1){
            ++measurementFrames;
            // Warm both filters for 100 ms, then measure identical input frames for 2 seconds.
            if(measurementFrames>rate/10)for(unsigned c=0;c<2;++c){energyIn+=double(in[c])*in[c];energyA+=double(aOut[c])*aOut[c];energyB+=double(bOut[c])*bOut[c];}
            if(measurementFrames>=rate*21/10){
                const double denominator=rate*4.;const double minimum=denominator*1e-8;
                if(energyIn>minimum && energyA>minimum && energyB>minimum){
                    const double target=std::min({energyIn,energyA,energyB});
                    gainA=static_cast<float>(std::sqrt(target/energyA));gainB=static_cast<float>(std::sqrt(target/energyB));measurement=2;
                }else measurement=3;
            }
        }
    }
    // A bounded 10 ms fade changes listening selection without a discontinuous sample jump.
    const float smoothing=toolFade?1.f/static_cast<float>(toolFade):1.f;
    for(unsigned c=0;c<2;++c){if(toolFade)last[c]+=(clean(result[c])-last[c])*smoothing;else last[c]=clean(result[c]);out[c]=last[c];}
    if(toolFade)--toolFade;
    if(advanced[10])for(unsigned c=0;c<2;++c)peaks[c]=std::max(std::abs(out[c]),peaks[c]*peakDecay);
}
void Engine::runtime(int32_t* r,bool moduleEnabled)const{
    std::fill_n(r,kRuntimeWords,0);r[0]=1;r[2]=rate;r[3]=moduleEnabled;r[4]=enabled();r[5]=mode;r[6]=measurement;
    r[7]=static_cast<int32_t>(measurementFrames*1000ull/rate);r[8]=db(gainA);r[9]=db(gainB);r[10]=epoch;r[11]=fade!=0;
    for(unsigned i=2;i<kWords;++i)r[10+i]=settings[i];
    r[18]=match;r[19]=static_cast<int32_t>(std::llround(settings[5]*rate/1000.));
}
void Engine::diagnostics(int32_t* r,bool moduleEnabled)const{
    std::fill_n(r,kDiagnosticWords,0);r[0]=1;r[2]=rate;r[3]=enabled();r[4]=advanced[10];
    r[5]=r[6]=std::numeric_limits<int32_t>::min();
    if(moduleEnabled && enabled() && advanced[10]){r[5]=db(peaks[0]);r[6]=db(peaks[1]);}
    std::copy(advanced.begin(),advanced.end(),r+9);
    if(enabled() && advanced[10])std::copy(responseValues.begin(),responseValues.end(),r+20);
    else std::fill_n(r+20,kResponsePoints*6,std::numeric_limits<int32_t>::min());
}
}

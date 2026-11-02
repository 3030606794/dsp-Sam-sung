#include "ProfessionalReverb.h"
#include "CRevModel.h"
#include <algorithm>
#include <cmath>
#include <complex>
#include <limits>
#include <vector>

namespace reverbtuning {
namespace {
constexpr double pi=3.14159265358979323846;
float clean(float value){return std::isfinite(value)?value:0.f;}
int level(double value){return value>0?static_cast<int>(std::clamp(std::llround(2000*std::log10(value)),-18000ll,12000ll)):std::numeric_limits<int32_t>::min();}
struct Biquad {
    std::array<double,5> coefficient{1,0,0,0,0},target=coefficient;
    double z1=0,z2=0;
    void tune(double frequency,uint32_t rate,bool highpass,bool immediate){
        const double w=2*pi*std::min(frequency,rate*.45)/rate,c=std::cos(w),s=std::sin(w),alpha=s/std::sqrt(2.),denominator=1+alpha;
        target=highpass?std::array<double,5>{(1+c)/(2*denominator),-(1+c)/denominator,(1+c)/(2*denominator),-2*c/denominator,(1-alpha)/denominator}:
            std::array<double,5>{(1-c)/(2*denominator),(1-c)/denominator,(1-c)/(2*denominator),-2*c/denominator,(1-alpha)/denominator};
        if(immediate)coefficient=target;
    }
    float process(float x,double smoothing){
        for(unsigned i=0;i<5;++i)coefficient[i]+=(target[i]-coefficient[i])*smoothing;
        const double y=coefficient[0]*x+z1;
        z1=coefficient[1]*x-coefficient[3]*y+z2;z2=coefficient[2]*x-coefficient[4]*y;
        if(std::abs(z1)<1e-30)z1=0;if(std::abs(z2)<1e-30)z2=0;
        return clean(static_cast<float>(y));
    }
};
// Two ordered bilinear one-pole lowpasses form a passive three-band feedback EQ.
// H = gH + (gM-gH)*LP(high) + (gL-gM)*LP(low). For 0 <= gains <= G
// and low <= high its magnitude never exceeds G (each of the eight gain-box
// vertices is bounded). Thus every feedback loop remains strictly below unity.
struct Comb {
    std::vector<float> data;unsigned length=1,position=0;float low=0;
    std::array<double,3> gains{},gainTarget{};
    double bandLow=0,bandHigh=0;
    float process(float input,float feedback,float damp){
        const float value=data[position];low=value*(1-damp)+low*damp;
        if(std::abs(low)<1e-30f)low=0;
        data[position]=clean(input+low*feedback);if(++position==length)position=0;return value;
    }
    float extended(float input,float feedback,float damp,double blend,double bands,double lowCoefficient,double highCoefficient,double smoothing){
        const float value=data[position];low=value*(1-damp)+low*damp;
        if(std::abs(low)<1e-30f)low=0;
        for(unsigned i=0;i<3;++i)gains[i]+=(gainTarget[i]-gains[i])*smoothing;
        const double lowStep=(value-bandLow)*lowCoefficient,lo=lowStep+bandLow;bandLow=lo+lowStep;
        const double highStep=(value-bandHigh)*highCoefficient,hi=highStep+bandHigh;bandHigh=hi+highStep;
        if(std::abs(bandLow)<1e-30)bandLow=0;if(std::abs(bandHigh)<1e-30)bandHigh=0;
        const double filtered=gains[2]*value+(gains[1]-gains[2])*hi+(gains[0]-gains[1])*lo;
        const double newFeedback=(1-bands)*low*gains[1]+bands*filtered;
        data[position]=clean(static_cast<float>(input+(1-blend)*low*feedback+blend*newFeedback));
        if(++position==length)position=0;return value;
    }
};
struct Allpass {
    std::vector<float> data;unsigned length=1,position=0;
    float process(float input){const float value=data[position];data[position]=clean(input+value*.5f);if(++position==length)position=0;return value-input;}
};
uint32_t warmup(const Settings& a,const Advanced& aa,const Settings& b,const Advanced& ab,uint32_t rate){
    double seconds=.1;
    for(unsigned which=0;which<2;++which){
        const auto& s=which?b:a;const auto& v=which?ab:aa;
        const double feedback=.7+.28*s[2]/1000.;
        const double longest=1640./(s[1]?44100.:48000.);
        double tail=s[1]&&v[1]?v[2]/100.:longest*std::log(.001)/std::log(feedback);
        if(s[1]&&v[3])tail*=std::max({1.,v[4]/100.,v[5]/100.});
        tail+=.05+(s[1]&&s[7]?s[8]/10000.:0.);
        if(s[1]&&v[8])tail+=5*v[12]/1000.;
        seconds=std::max(seconds,tail);
    }
    return static_cast<uint32_t>(std::ceil(std::min(seconds,72.)*rate));
}
}
bool valid(const int32_t* v,size_t n){
    if(!v || n!=kWords)return false;
    constexpr int lo[]{1,0,0,0,0,0,0,0,0,0,20,0,1000,0,0,0,-120,0};
    constexpr int hi[]{1,1,1000,1000,1000,1000,1000,1,2000,1,1000,1,20000,1,1000,1,0,1};
    for(unsigned i=0;i<kWords;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return true;
}
bool validAdvanced(const int32_t* v,size_t n){
    if(!v || n!=kAdvancedWords)return false;
    constexpr int lo[]{1,0,20,0,25,25,80,1000,0,-600,0,1,50,0,0};
    constexpr int hi[]{1,1,1000,1,400,200,1000,16000,1,0,240,200,3000,1,0};
    for(unsigned i=0;i<kAdvancedWords;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return true;
}
bool validTools(const int32_t* v,size_t n){
    if(!v || (n!=kToolsWords && n!=kAdvancedToolsWords) || v[0]<0 || v[0]>6 || v[1]<0 || v[1]>1 || v[2]<0 || (v[0]>=4 && v[2]==0))return false;
    if(n==kToolsWords)return valid(v+3,kWords) && valid(v+3+kWords,kWords);
    return valid(v+3,kWords) && validAdvanced(v+3+kWords,kAdvancedWords) && valid(v+3+kWords+kAdvancedWords,kWords) && validAdvanced(v+3+2*kWords+kAdvancedWords,kAdvancedWords);
}
struct Kernel::State {
    Settings settings=defaults;
    Advanced advanced=advancedDefaults;
    CRevModel legacy;
    std::array<std::array<Comb,8>,2> comb;
    std::array<std::array<Allpass,4>,2> allpass;
    std::array<std::vector<float>,2> predelay{};
    std::array<std::array<Biquad,2>,2> filter;
    unsigned position=0;uint32_t rate=48000;bool legacyPath=false;
    // room, width, damp, dry gain, wet gain, delay samples, low blend, high blend, output gain
    std::array<double,9> current{},target{};
    double smoothing=.002;
    // overall extended-decay blend, band blend, low/high TPT coefficients, duck blend
    std::array<double,5> advancedCurrent{},advancedTarget{};
    double envelopePower=0,duckGain=1,attack=0,release=0;
    std::array<int32_t,4> prediction{};
    void predict(){
        using C=std::complex<double>;
        const double lowHz=std::min(double(advanced[6]),rate*.45),highHz=std::min(double(advanced[7]),rate*.45);
        const double frequencies[]{std::min(80.,lowHz/4),std::sqrt(lowHz*highHz),std::min(rate*.45,std::max(8000.,highHz*2))};
        prediction={};
        for(const auto& channel:comb)for(const auto& line:channel){
            const double delay=double(line.length)/rate;
            const double reference=advanced[1]?advanced[2]/100.:delay*std::log(.001)/std::log(target[0]);
            prediction[0]=std::max(prediction[0],int(std::lround(reference*1000)));
            for(unsigned band=0;band<3;++band){
                const C z=std::polar(1.,-2*pi*frequencies[band]/rate);
                C response;
                if(advanced[3]){
                    const double al=advancedTarget[2],ah=advancedTarget[3];
                    const C lo=al*(1.+z)/(1.-(1.-2*al)*z),hi=ah*(1.+z)/(1.-(1.-2*ah)*z);
                    response=line.gainTarget[2]+(line.gainTarget[1]-line.gainTarget[2])*hi+(line.gainTarget[0]-line.gainTarget[1])*lo;
                }else response=line.gainTarget[1]*(1.-target[2])/(1.-target[2]*z);
                const double magnitude=std::clamp(std::abs(response),1e-12,.999999999);
                const double seconds=delay*std::log(.001)/std::log(magnitude);
                prediction[band+1]=std::max(prediction[band+1],int(std::lround(std::min(60.,seconds)*1000)));
            }
        }
    }
    void tune(const Settings& s,const Advanced& v,bool immediate){
        settings=s;advanced=v;
        const double legacyDamping=.4*s[4]/1000.;
        const double damping=legacyDamping>0?std::pow(legacyDamping,44100./rate):0.;
        target={.7+.28*s[2]/1000.,s[3]/1000.,damping,s[13]?1-s[14]/1000.:s[6]/500.,s[13]?s[14]/1000.:s[5]*.003,
            s[7]?s[8]*rate/10000.:0.,double(s[9]),double(s[11]),s[15]?std::pow(10.,s[16]/200.):1.};
        if(immediate)current=target;
        const double lowT=std::tan(pi*std::min(double(v[6]),rate*.45)/rate),highT=std::tan(pi*std::min(double(v[7]),rate*.45)/rate);
        advancedTarget={double(v[1]||v[3]),double(v[3]),lowT/(1+lowT),highT/(1+highT),double(v[8])};
        if(immediate)advancedCurrent=advancedTarget;
        attack=1-std::exp(-1./(v[11]*.001*rate));release=1-std::exp(-1./(v[12]*.001*rate));
        for(auto& channel:comb)for(auto& line:channel){
            const double feedback=v[1]?std::pow(.001,double(line.length)/rate/(v[2]/100.)):target[0];
            line.gainTarget={std::pow(feedback,100./v[4]),feedback,std::pow(feedback,100./v[5])};
            if(immediate)line.gains=line.gainTarget;
        }
        predict();
        for(auto& channel:filter){channel[0].tune(s[10],rate,true,immediate);channel[1].tune(s[12],rate,false,immediate);}
        legacy.SetRoomSize(s[2]/1000.f);legacy.SetWidth(s[3]/1000.f);legacy.SetDamp(s[4]/1000.f);legacy.SetWet(s[5]/1000.f);legacy.SetDry(s[6]/1000.f);
    }
};
Kernel::Kernel():state(std::make_unique<State>()){}
Kernel::~Kernel()=default;
void Kernel::configure(const Settings& s,uint32_t rate,bool comparison,const Advanced& advanced){
    auto& q=*state;q.rate=std::clamp(rate,8000u,768000u);q.legacyPath=comparison&&!s[1];q.smoothing=1-std::exp(-1./(.01*q.rate));q.position=0;q.envelopePower=0;q.duckGain=1;
    q.legacy.SetSamplingRate(q.rate);
    constexpr unsigned lengths[]{1116,1188,1277,1356,1422,1491,1557,1617},passes[]{556,441,341,225};
    for(unsigned c=0;c<2;++c){
        // Allocate only at control-thread configuration, scaled to the real
        // host rate. No fixed 192 kHz cap may truncate a requested 200 ms delay.
        q.predelay[c].assign(unsigned(std::ceil(.2*q.rate))+2,0.f);
        for(unsigned i=0;i<8;++i){auto& comb=q.comb[c][i];comb.low=0;comb.bandLow=comb.bandHigh=0;comb.position=0;comb.length=std::max(1u,unsigned(std::lround((lengths[i]+23*c)*q.rate/44100.)));comb.data.assign(comb.length,0.f);}
        for(unsigned i=0;i<4;++i){auto& pass=q.allpass[c][i];pass.position=0;pass.length=std::max(1u,unsigned(std::lround((passes[i]+23*c)*q.rate/44100.)));pass.data.assign(pass.length,0.f);}
        for(auto& filter:q.filter[c]){filter.z1=filter.z2=0;}
    }
    q.tune(s,advanced,true);q.legacy.ResetComparisonState();
}
void Kernel::retune(const Settings& s,const Advanced& advanced){state->tune(s,advanced,false);}
void Kernel::decay(int32_t* r)const{
    const auto& q=*state;std::fill_n(r,kDecayWords,0);r[0]=1;r[2]=q.rate;
    r[5]=q.advanced[1];r[6]=q.advanced[3];r[7]=q.advanced[8];r[8]=q.advanced[13];
    std::copy(q.prediction.begin(),q.prediction.end(),r+9);
    r[13]=int(std::lround(-2000*std::log10(std::max(q.duckGain,1e-12))));
    r[14]=int(std::min(double(q.advanced[6]),q.rate*.45));r[15]=int(std::min(double(q.advanced[7]),q.rate*.45));
}
void Kernel::process(const float* input,float* output,float* wet){
    auto& q=*state;
    if(q.legacyPath){
        output[0]=input[0];output[1]=input[1];q.legacy.ProcessReplace(output,output+1,1);
        for(unsigned c=0;c<2;++c)wet[c]=output[c]-input[c]*(q.settings[6]/500.f);
        return;
    }
    for(unsigned i=0;i<q.current.size();++i)q.current[i]+=(q.target[i]-q.current[i])*q.smoothing;
    for(unsigned i=0;i<q.advancedCurrent.size();++i){q.advancedCurrent[i]+=(q.advancedTarget[i]-q.advancedCurrent[i])*q.smoothing;if(std::abs(q.advancedCurrent[i])<1e-12)q.advancedCurrent[i]=0;}
    const auto& advanced=q.advancedCurrent;
    if(advanced[4]>0 || q.advanced[8]){
        const double detector=std::max(double(clean(input[0]))*clean(input[0]),double(clean(input[1]))*clean(input[1]));
        q.envelopePower+=(detector-q.envelopePower)*(detector>q.envelopePower?q.attack:q.release);
        const double over=10*std::log10(std::max(q.envelopePower,1e-30))-q.advanced[9]/10.;
        const double reduction=q.advanced[10]/10.*std::clamp(over/12.,0.,1.);
        q.duckGain=1+advanced[4]*(std::pow(10.,-reduction/20.)-1);
    }else{q.duckGain=1;q.envelopePower=0;}
    const auto& p=q.current;float delayed[2];
    for(unsigned c=0;c<2;++c){
        auto& ring=q.predelay[c];ring[q.position]=clean(input[c]);const auto integer=static_cast<unsigned>(p[5]);const double fraction=p[5]-integer;
        delayed[c]=static_cast<float>(ring[(q.position+ring.size()-integer)%ring.size()]*(1-fraction)+ring[(q.position+ring.size()-integer-1)%ring.size()]*fraction);
    }
    if(++q.position==q.predelay[0].size())q.position=0;
    const float drive=(delayed[0]+delayed[1])*.015f;float tail[2]{};
    for(unsigned c=0;c<2;++c){
        for(auto& comb:q.comb[c])tail[c]+=advanced[0]?comb.extended(drive,p[0],p[2],advanced[0],advanced[1],advanced[2],advanced[3],q.smoothing):comb.process(drive,p[0],p[2]);
        for(auto& pass:q.allpass[c])tail[c]=pass.process(tail[c]);
    }
    for(unsigned c=0;c<2;++c){
        float value=static_cast<float>((tail[c]*(p[1]/2+.5)+tail[1-c]*(1-p[1])/2)*p[4]);
        const float low=q.filter[c][0].process(value,q.smoothing);value+=static_cast<float>((low-value)*p[6]);
        const float high=q.filter[c][1].process(value,q.smoothing);value+=static_cast<float>((high-value)*p[7]);
        value=static_cast<float>(value*q.duckGain);
        wet[c]=clean(static_cast<float>(value*p[8]));output[c]=clean(static_cast<float>((input[c]*p[3]+value)*p[8]));
    }
}
Engine::Engine(){reset();}
void Engine::reset(){
    fadeLength=std::max(1u,rate/100);peakDecay=static_cast<float>(std::exp(-1./(.5*rate)));normal.configure(settings,rate,false,advanced);clearTools();last={};fadeFrom={};toolFade=0;processedFrames=0;
}
void Engine::clearTools(){
    if(mode){fadeFrom=last;toolFade=fadeLength;}mode=0;match=false;epoch=0;measurement=0;warmFrames=warmTarget=measureFrames=0;
    energyIn=energyA=energyB=0;gainA=gainB=1;peaks={};clips=0;
}
void Engine::setRate(uint32_t fs){fs=std::clamp(fs,8000u,768000u);if(fs!=rate){rate=fs;reset();}}
bool Engine::set(const int32_t* v,size_t n){
    if(!valid(v,n))return false;Settings next;std::copy_n(v,kWords,next.begin());if(next==settings)return true;
    const bool activating=!settings[1]&&next[1];settings=next;if(activating)normal.configure(settings,rate,false,advanced);else normal.retune(settings,advanced);clearTools();return true;
}
bool Engine::setAdvanced(const int32_t* v,size_t n){
    if(!validAdvanced(v,n))return false;Advanced next;std::copy_n(v,kAdvancedWords,next.begin());if(next==advanced)return true;
    const bool graphOnly=std::equal(next.begin(),next.begin()+13,advanced.begin());advanced=next;
    normal.retune(settings,advanced);if(!graphOnly)clearTools();return true;
}
bool Engine::setTools(const int32_t* v,size_t n){
    if(!validTools(v,n) || (v[0] && !enabled()))return false;
    Settings nextA,nextB;Advanced nextAdvancedA=advancedDefaults,nextAdvancedB=advancedDefaults;
    std::copy_n(v+3,kWords,nextA.begin());
    if(n==kAdvancedToolsWords){
        std::copy_n(v+3+kWords,kAdvancedWords,nextAdvancedA.begin());
        std::copy_n(v+3+kWords+kAdvancedWords,kWords,nextB.begin());
        std::copy_n(v+3+2*kWords+kAdvancedWords,kAdvancedWords,nextAdvancedB.begin());
    }else std::copy_n(v+3+kWords,kWords,nextB.begin());
    const bool changed=epoch!=v[2] || a!=nextA || b!=nextB || advancedA!=nextAdvancedA || advancedB!=nextAdvancedB;
    if((v[0]==4 || v[0]==5) && v[1] && (changed || measurement!=3))return false;
    if(changed){clearTools();a=nextA;b=nextB;advancedA=nextAdvancedA;advancedB=nextAdvancedB;epoch=v[2];comparisonA.configure(a,rate,true,advancedA);comparisonB.configure(b,rate,true,advancedB);}
    if(v[0]==6 && mode!=6){
        comparisonA.configure(a,rate,true,advancedA);comparisonB.configure(b,rate,true,advancedB);warmFrames=measureFrames=0;warmTarget=warmup(a,advancedA,b,advancedB,rate);
        measurement=1;energyIn=energyA=energyB=0;gainA=gainB=1;
    }
    if(mode==6 && v[0]!=6 && (measurement==1 || measurement==2))measurement=0;
    if(mode!=v[0]){fadeFrom=last;toolFade=fadeLength;}mode=v[0];match=v[1];return true;
}
void Engine::decay(int32_t* r,bool moduleEnabled)const{
    normal.decay(r);r[3]=moduleEnabled;r[4]=enabled();
    if(!moduleEnabled || !enabled()){r[5]=r[6]=r[7]=0;r[13]=0;}
}
void Engine::process(const float* input,float* output){
    float normalOut[2],wet[2];normal.process(input,normalOut,wet);float selected[2]{normalOut[0],normalOut[1]};
    if(mode==1){selected[0]=input[0];selected[1]=input[1];}
    else if(mode==2)for(unsigned c=0;c<2;++c)selected[c]=(normalOut[c]-input[c])*.5f;
    else if(mode==3){selected[0]=wet[0];selected[1]=wet[1];}
    if(mode>=4){
        float aOut[2],bOut[2],unused[2];comparisonA.process(input,aOut,unused);comparisonB.process(input,bOut,unused);
        if(mode==4 || mode==5)for(unsigned c=0;c<2;++c)selected[c]=(mode==4?aOut[c]:bOut[c])*(match?(mode==4?gainA:gainB):1.f);
        if(mode==6 && measurement==1){if(++warmFrames>=warmTarget)measurement=2;}
        else if(mode==6 && measurement==2){
            ++measureFrames;for(unsigned c=0;c<2;++c){energyIn+=double(input[c])*input[c];energyA+=double(aOut[c])*aOut[c];energyB+=double(bOut[c])*bOut[c];}
            if(measureFrames>=rate*2){
                const double minimum=rate*4.*1e-8;
                if(energyIn>minimum && energyA>minimum && energyB>minimum){const double target=std::min({energyIn,energyA,energyB});gainA=static_cast<float>(std::sqrt(target/energyA));gainB=static_cast<float>(std::sqrt(target/energyB));measurement=3;}
                else{measurement=4;gainA=gainB=1;}
            }
        }
    }
    const float weight=toolFade?1.f-static_cast<float>(toolFade)/fadeLength:1.f;
    for(unsigned c=0;c<2;++c){output[c]=last[c]=clean(toolFade?fadeFrom[c]+(selected[c]-fadeFrom[c])*weight:selected[c]);}
    if(toolFade)--toolFade;if(processedFrames<std::numeric_limits<int32_t>::max())++processedFrames;
    if(settings[17]){
        const float values[]{input[0],input[1],wet[0],wet[1],output[0],output[1]};for(unsigned i=0;i<6;++i)peaks[i]=std::max(std::abs(clean(values[i])),peaks[i]*peakDecay);
        if((std::abs(output[0])>=1.f || std::abs(output[1])>=1.f) && clips<std::numeric_limits<int32_t>::max())++clips;
    }
}
void Engine::runtime(int32_t* r,bool moduleEnabled)const{
    std::fill_n(r,kRuntimeWords,0);r[0]=1;r[2]=rate;r[3]=moduleEnabled;r[4]=enabled();r[5]=mode;r[6]=match;r[7]=measurement;r[8]=int(measureFrames*1000ull/rate);
    r[9]=level(gainA);r[10]=level(gainB);r[11]=epoch;r[12]=toolFade!=0;r[13]=settings[7]?settings[8]:0;
    r[14]=settings[9]?int(std::min(double(settings[10]),rate*.45)):0;r[15]=settings[11]?int(std::min(double(settings[12]),rate*.45)):0;
    for(unsigned i=0;i<6;++i)r[16+i]=moduleEnabled&&enabled()&&settings[17]?level(peaks[i]):std::numeric_limits<int32_t>::min();
    r[22]=clips;r[23]=settings[17];r[24]=int(warmFrames*1000ull/rate);r[25]=int(warmTarget*1000ull/rate);r[26]=2000;r[27]=r[28]=epoch>0;r[29]=processedFrames;r[30]=moduleEnabled&&enabled();
}
}

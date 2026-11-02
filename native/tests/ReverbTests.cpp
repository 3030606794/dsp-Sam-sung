#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <thread>
#include <vector>
#include "../viper/src/viper/effects/Reverberation.h"
#include "essential.h"
extern "C" audio_effect_library_t AELI;
namespace {
using reverbtuning::Settings;
using reverbtuning::Advanced;
constexpr double pi=3.14159265358979323846;
std::atomic<bool> countNew{false};std::atomic<unsigned> allocations{0};
Settings precise(){auto p=reverbtuning::defaults;p[1]=1;return p;}
std::array<int32_t,39> tools(int mode,const Settings& a,const Settings& b,int token=1,bool match=false){
    std::array<int32_t,39> t{};t[0]=mode;t[1]=match;t[2]=token;std::copy(a.begin(),a.end(),t.begin()+3);std::copy(b.begin(),b.end(),t.begin()+21);return t;
}
std::vector<float> signal(unsigned rate,unsigned frames){
    std::vector<float> v(frames*2);uint32_t seed=123;
    for(unsigned i=0;i<frames;++i){seed=seed*1664525+1013904223;const float noise=int32_t(seed)/2147483648.f*.01f;v[2*i]=.13f*std::sin(2*pi*500*i/rate)+noise;v[2*i+1]=.09f*std::cos(2*pi*1200*i/rate)-noise;}
    return v;
}
void legacy(){
    unsigned cases=0;
    for(unsigned rate:{8000,44100,48000,96000,192000})for(const auto values:{std::array<float,5>{0,0,0,0,.5},std::array<float,5>{.37,.61,.42,.13,.47},std::array<float,5>{1,1,1,1,1}}){
        Reverberation actual;CRevModel expected;actual.SetSamplingRate(rate);expected.SetSamplingRate(rate);
        actual.SetRoomSize(values[0]);actual.SetWidth(values[1]);actual.SetDamp(values[2]);actual.SetWet(values[3]);actual.SetDry(values[4]);
        expected.SetRoomSize(values[0]);expected.SetWidth(values[1]);expected.SetDamp(values[2]);expected.SetWet(values[3]);expected.SetDry(values[4]);
        actual.SetEnable(true);expected.Reset();auto p=reverbtuning::defaults;
        p[2]=981;p[3]=349;p[4]=572;p[5]=813;p[6]=7;p[7]=p[9]=p[11]=p[13]=p[15]=p[17]=1;p[8]=1377;p[10]=1000;p[12]=1000;p[14]=999;p[16]=-120;
        assert(actual.SetProfessional(p.data(),p.size()));
        auto advanced=reverbtuning::advancedDefaults;advanced[1]=advanced[3]=advanced[8]=advanced[13]=1;advanced[2]=1000;advanced[4]=400;advanced[5]=200;advanced[10]=240;
        assert(actual.SetAdvanced(advanced.data(),advanced.size()));auto input=signal(rate,8192),a=input,b=input;
        for(unsigned pos=0;pos<8192;){const unsigned size=std::min(8192-pos,pos%3==0?1u:pos%3==1?13u:511u);actual.Process(a.data()+pos*2,size);expected.ProcessReplace(b.data()+pos*2,b.data()+pos*2+1,size);pos+=size;}
        assert(a==b);++cases;
    }
    std::printf("legacy: %u rate/parameter cases bit-identical with every new feature set while professional is off\n",cases);
}
void dryAndLinked(){
    for(unsigned rate:{8000,44100,48000,96000,192000}){
        reverbtuning::Kernel kernel;auto p=precise();kernel.configure(p,rate);auto input=signal(rate,2000);float out[2],wet[2];
        for(unsigned i=0;i<2000;++i){kernel.process(input.data()+i*2,out,wet);assert(out[0]==input[i*2] && out[1]==input[i*2+1]);assert(wet[0]==0 && wet[1]==0);}
        // Disabled feature values have no signal effect, including filters and output attenuation.
        p[8]=2000;p[10]=1000;p[12]=1000;p[14]=1000;p[16]=-120;kernel.configure(p,rate);
        for(unsigned i=0;i<2000;++i){kernel.process(input.data()+i*2,out,wet);assert(out[0]==input[i*2] && out[1]==input[i*2+1]);}
        p[13]=1;p[14]=0;p[6]=0;p[5]=1000;kernel.configure(p,rate);float in[]{.1f,-.2f};kernel.process(in,out,wet);assert(out[0]==in[0] && out[1]==in[1]);
        p[14]=1000;kernel.configure(p,rate);kernel.process(in,out,wet);assert(out[0]==0 && out[1]==0); // Fully wet has no direct impulse.
        p=precise();p[15]=1;p[16]=-37;kernel.configure(p,rate);kernel.process(in,out,wet);const double gain=std::pow(10.,-37/200.);
        for(unsigned c=0;c<2;++c)assert(std::abs(out[c]-in[c]*gain)<1e-7);
    }
    reverbtuning::Engine engine;auto p=precise();assert(engine.set(p.data(),18));float input[]{.5f,.5f},out[2];engine.process(input,out);p[6]=0;assert(engine.set(p.data(),18));engine.process(input,out);
    assert(out[0]>.49f && out[0]<.5f);
    std::puts("dry/mix: exact neutral bypass at five rates, independent/linear-linked gains, 0.1 dB trim and state-preserving smoothing");
}
void precisionAndStability(){
    auto p=precise();p[2]=p[3]=p[4]=p[5]=p[6]=500;
    for(unsigned control=2;control<=6;++control){
        reverbtuning::Kernel base,changed;base.configure(p,44100);auto next=p;++next[control];changed.configure(next,44100);double difference=0;
        for(unsigned i=0;i<6000;++i){float input[]{i==0?.5f:0.f,0},a[2],b[2],wet[2];base.process(input,a,wet);changed.process(input,b,wet);difference+=std::abs(a[0]-b[0])+std::abs(a[1]-b[1]);}
        assert(difference>1e-6);
    }
    for(unsigned rate:{8000,44100,48000,96000,192000}){
        reverbtuning::Kernel kernel;auto endpoint=precise();endpoint[2]=endpoint[3]=endpoint[5]=endpoint[6]=1000;endpoint[7]=endpoint[9]=endpoint[11]=1;endpoint[8]=1;endpoint[10]=20;endpoint[12]=20000;kernel.configure(endpoint,rate);
        float out[2],wet[2];double energy=0;
        for(unsigned i=0;i<rate/2;++i){float input[]{i==0?.5f:0.f,i==0?.5f:0.f};kernel.process(input,out,wet);for(float sample:out){assert(std::isfinite(sample) && std::abs(sample)<2);energy+=sample*sample;}}
        assert(energy>0);
        endpoint[4]=1000;kernel.retune(endpoint);for(unsigned i=0;i<2000;++i){float input[2]{};kernel.process(input,out,wet);assert(std::isfinite(out[0]) && std::isfinite(out[1]));}
    }
    std::puts("precision/stability: every original core responds to 0.1 percent, bounded maximum feedback and damp retuning at five host rates");
}
void predelay(){
    unsigned cases=0;
    for(unsigned rate:{8000,44100,48000,96000,192000})for(int tenths:{1,13,2000}){
        auto p=precise();p[3]=1000;p[5]=1000;p[6]=0;reverbtuning::Kernel base,delayed;base.configure(p,rate);p[7]=1;p[8]=tenths;delayed.configure(p,rate);
        const double samples=tenths*rate/10000.;const unsigned whole=unsigned(samples);const double fraction=samples-whole;
        const unsigned frames=rate/10+whole+2;std::vector<std::array<float,2>> reference(frames);float wet[2],out[2];
        for(unsigned i=0;i<frames;++i){float input[]{i==0?.5f:0.f,i==0?.25f:0.f};base.process(input,reference[i].data(),wet);delayed.process(input,out,wet);
            for(unsigned c=0;c<2;++c){const double expected=(i>=whole?reference[i-whole][c]*(1-fraction):0)+(i>whole?reference[i-whole-1][c]*fraction:0);assert(std::abs(out[c]-expected)<2e-7);}}
        ++cases;
    }
    std::printf("pre-delay: %u independently checked fractional impulse shifts, 0.1 ms through 200 ms\n",cases);
}
struct FilterOracle {
    double b0,b1,b2,a1,a2,x1=0,x2=0,y1=0,y2=0;
    FilterOracle(double frequency,unsigned rate,bool high){const double angle=2*pi*std::min(frequency,rate*.45)/rate,cs=std::cos(angle),alpha=std::sin(angle)/std::sqrt(2.),a0=1+alpha;
        b0=(high?1+cs:1-cs)/(2*a0);b1=(high?-1-cs:1-cs)/a0;b2=b0;a1=-2*cs/a0;a2=(1-alpha)/a0;}
    double process(double x){const double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;x2=x1;x1=x;y2=y1;y1=y;return y;}
};
void wetFilters(){
    unsigned cases=0;
    for(unsigned rate:{8000,44100,48000,96000,192000})for(int mask=1;mask<=3;++mask){
        auto p=precise();p[2]=431;p[3]=713;p[4]=325;p[5]=617;p[6]=389;reverbtuning::Kernel base,filtered;base.configure(p,rate);
        p[9]=mask&1;p[10]=317;p[11]=bool(mask&2);p[12]=20000;filtered.configure(p,rate);
        std::array<FilterOracle,2> low{FilterOracle(317,rate,true),FilterOracle(317,rate,true)},high{FilterOracle(20000,rate,false),FilterOracle(20000,rate,false)};
        float out[2],wet[2],baseOut[2],baseWet[2];const unsigned count=rate/5;
        for(unsigned i=0;i<count;++i){float input[]{i==0?.5f:0.f,i==0?-.1f:0.f};base.process(input,baseOut,baseWet);filtered.process(input,out,wet);
            for(unsigned c=0;c<2;++c){double expected=baseWet[c];if(mask&1)expected=low[c].process(expected);if(mask&2)expected=high[c].process(expected);
                assert(std::abs(wet[c]-expected)<2e-7);assert(std::abs(out[c]-(input[c]*.778+expected))<2e-7);}}
        ++cases;
    }
    std::printf("wet filters: %u stereo impulse responses match independent 12 dB/oct Butterworth oracle; dry is untouched and high cutoff respects Nyquist\n",cases);
}
void dampingRateResponse(){
    using Complex=std::complex<double>;
    constexpr unsigned comb[]{1116,1188,1277,1356,1422,1491,1557,1617},allpass[]{556,441,341,225};
    unsigned cases=0;
    for(unsigned rate:{8000,44100,48000,96000,192000})for(double frequency:{200.,1000.}){
        auto p=precise();p[3]=1000;p[4]=500;p[5]=1000;p[6]=0;reverbtuning::Kernel kernel;kernel.configure(p,rate);
        const float damping=std::pow(.2,44100./rate),feedback=.7f;
        const double tau=-1./(rate*std::log(double(damping)));
        assert(std::abs(tau-(-1./(44100*std::log(.2))))<1e-11);
        const Complex z=std::polar(1.,-2*pi*frequency/rate);std::array<Complex,2> expected{},measured{};
        for(unsigned c=0;c<2;++c){
            for(auto length:comb){const unsigned samples=unsigned(std::lround((length+c*23)*rate/44100.));const Complex delay=std::pow(z,samples);expected[c]+=delay/(1.-double(feedback)*(1-double(damping))/(1.-double(damping)*z)*delay);}
            for(auto length:allpass){const unsigned samples=unsigned(std::lround((length+c*23)*rate/44100.));const Complex delay=std::pow(z,samples);expected[c]*=(1.5*delay-1.)/(1.-.5*delay);}
            expected[c]*=double(.015f)*3.;
        }
        const unsigned warm=rate,window=rate/5;
        for(unsigned i=0;i<warm+window;++i){float input[]{float(.1*std::cos(2*pi*frequency*i/rate)),0},out[2],wet[2];kernel.process(input,out,wet);
            if(i>=warm)for(unsigned c=0;c<2;++c)measured[c]+=double(out[c])*std::polar(1.,-2*pi*frequency*i/rate);}
        for(unsigned c=0;c<2;++c){measured[c]*=2/(.1*window);if(std::abs(measured[c]-expected[c])>=3e-4)std::fprintf(stderr,"damping oracle mismatch rate=%u hz=%.1f channel=%u actual=(%.9g,%.9g) expected=(%.9g,%.9g) error=%.9g pole=%.9g\n",rate,frequency,c,measured[c].real(),measured[c].imag(),expected[c].real(),expected[c].imag(),std::abs(measured[c]-expected[c]),damping);assert(std::abs(measured[c]-expected[c])<3e-4);}
        ++cases;
    }
    std::printf("damping/rate: %u measured complex wet responses match independent comb/allpass transfer functions with constant pole time scale\n",cases);
}
void snapshotsAndLifecycle(){
    auto p=precise();p[2]=777;p[3]=918;p[4]=855;p[5]=720;p[6]=321;
    reverbtuning::Kernel kernel;auto original=p;original[1]=0;kernel.configure(original,96000,true);CRevModel reference;
    reference.SetSamplingRate(96000);reference.SetRoomSize(.777f);reference.SetWidth(.918f);reference.SetDamp(.855f);reference.SetWet(.720f);reference.SetDry(.321f);reference.Reset();
    auto input=signal(96000,5000);float output[2],wet[2];
    for(unsigned i=0;i<5000;++i){float expected[]{input[i*2],input[i*2+1]};reference.ProcessReplace(expected,expected+1,1);kernel.process(input.data()+i*2,output,wet);assert(output[0]==expected[0] && output[1]==expected[1]);}
    kernel.configure(original,96000,true);float silent[2]{};for(unsigned i=0;i<10000;++i){kernel.process(silent,output,wet);assert(output[0]==0 && output[1]==0);}
    reverbtuning::Engine first,second;assert(first.set(p.data(),18) && second.set(p.data(),18));
    for(unsigned i=0;i<5000;++i){first.process(input.data()+i*2,output);second.process(input.data()+i*2,wet);}
    first.clearTools();for(unsigned i=0;i<5000;++i){first.process(silent,output);second.process(silent,wet);assert(output[0]==wet[0] && output[1]==wet[1]);}
    p[1]=0;assert(first.set(p.data(),18));p[1]=1;assert(first.set(p.data(),18));for(unsigned i=0;i<10000;++i){first.process(silent,output);assert(output[0]==0 && output[1]==0);}
    std::puts("snapshot/lifecycle: original-model A/B snapshots, no stale comb damping state, stopping tools preserves normal tails, professional restart clears old tails");
}
void auditionAndMeters(){
    reverbtuning::Engine engine;auto p=precise();p[17]=1;assert(engine.set(p.data(),18));auto a=p,b=p;a[1]=0;b[6]=250;
    auto t=tools(6,a,b);assert(engine.setTools(t.data(),39));int32_t runtime[32];engine.runtime(runtime,true);assert(runtime[7]==1 && runtime[25]>100 && runtime[25]<2000);
    float in[]{.1f,-.1f},out[2];for(unsigned i=0;i<48000*4;++i)engine.process(in,out);engine.runtime(runtime,true);assert(runtime[7]==3 && runtime[8]==2000 && runtime[9]<-590 && runtime[9]>-610 && runtime[10]==0);
    double energies[2]{};for(unsigned choice=0;choice<2;++choice){t=tools(4+choice,a,b,1,true);assert(engine.setTools(t.data(),39));for(unsigned i=0;i<2000;++i){engine.process(in,out);if(i>1000)energies[choice]+=out[0]*out[0]+out[1]*out[1];}}
    assert(std::abs(10*std::log10(energies[0]/energies[1]))<.001);
    t=tools(6,a,b);assert(engine.setTools(t.data(),39));float silence[2]{};for(unsigned i=0;i<48000*4;++i)engine.process(silence,out);engine.runtime(runtime,true);assert(runtime[7]==4 && runtime[9]==0 && runtime[10]==0);
    t=tools(4,a,b,1,true);assert(!engine.setTools(t.data(),39));
    // Separate A/B states both run on the same input; off snapshot's wet tail cannot leak into another capture.
    // Both original and professional delay lines now preserve physical time at
    // 8 kHz; the old unscaled original bank incorrectly predicted a70s tail.
    a[2]=1000;b[2]=1000;engine.setRate(8000);assert(engine.set(p.data(),18));t=tools(6,a,b,2);assert(engine.setTools(t.data(),39));engine.runtime(runtime,true);assert(runtime[25]>12000 && runtime[25]<14000);
    engine.setRate(48000);engine.runtime(runtime,true);assert(runtime[5]==0 && runtime[7]==0 && runtime[11]==0);
    for(unsigned mode=1;mode<=3;++mode){t=tools(mode,p,p);assert(engine.setTools(t.data(),39));for(unsigned i=0;i<2000;++i)engine.process(in,out);if(mode==1)assert(out[0]==in[0]);else if(mode==2 || mode==3)assert(out[0]==0);}
    t=tools(0,p,p);assert(engine.setTools(t.data(),39));p[6]=1000;assert(engine.set(p.data(),18));float clip[]{.8f,-.8f};for(unsigned i=0;i<3000;++i)engine.process(clip,out);engine.runtime(runtime,true);assert(runtime[22]>0 && runtime[20]>300 && runtime[16]<0);
    allocations=0;countNew=true;for(unsigned i=0;i<10000;++i)engine.process(in,out);countNew=false;assert(allocations==0);
    engine.clearTools();engine.runtime(runtime,true);assert(runtime[22]==0 && runtime[16]==std::numeric_limits<int32_t>::min());
    std::puts("audition/meters: tail-dependent warmup, same-window 2 s RMS, attenuation-only A/B, silence rejection, raw/wet/difference, clip counters and no callback allocation");
}
std::array<int32_t,69> extendedTools(int mode,const Settings& a,const Advanced& aa,const Settings& b,const Advanced& ab,int token=1){
    std::array<int32_t,69> t{};t[0]=mode;t[2]=token;
    std::copy(a.begin(),a.end(),t.begin()+3);std::copy(aa.begin(),aa.end(),t.begin()+21);
    std::copy(b.begin(),b.end(),t.begin()+36);std::copy(ab.begin(),ab.end(),t.begin()+54);return t;
}
void advancedDefaultsAndDucking(){
    for(unsigned rate:{8000,44100,48000,96000,192000}){
        auto p=precise();p[2]=417;p[3]=723;p[4]=531;p[5]=617;p[6]=379;
        auto v=reverbtuning::advancedDefaults;v[2]=1000;v[4]=400;v[5]=25;v[6]=1000;v[7]=16000;v[9]=-600;v[10]=240;v[11]=200;v[12]=3000;v[13]=1;
        reverbtuning::Kernel original,disabled;original.configure(p,rate);disabled.configure(p,rate,false,v);
        auto input=signal(rate,rate/5);float a[2],b[2],aw[2],bw[2];
        for(unsigned i=0;i<rate/5;++i){original.process(input.data()+2*i,a,aw);disabled.process(input.data()+2*i,b,bw);assert(a[0]==b[0] && a[1]==b[1] && aw[0]==bw[0] && aw[1]==bw[1]);}
        v=reverbtuning::advancedDefaults;v[8]=1;original.configure(p,rate);disabled.configure(p,rate,false,v);
        double envelope=0;const double attack=1-std::exp(-1./(.02*rate)),release=1-std::exp(-1./(.35*rate));
        bool attenuated=false,recovered=false;
        for(unsigned i=0;i<2*rate;++i){
            float in[]{i<rate/5?.3f:0.f,i<rate/5?-.1f:0.f};original.process(in,a,aw);disabled.process(in,b,bw);
            const double power=std::max(double(in[0])*in[0],double(in[1])*in[1]);envelope+=(power-envelope)*(power>envelope?attack:release);
            const double over=10*std::log10(std::max(envelope,1e-30))+24;
            const double gain=std::pow(10.,-9*std::clamp(over/12.,0.,1.)/20.);
            for(unsigned c=0;c<2;++c){assert(std::abs(bw[c]-aw[c]*gain)<2e-7);assert(std::abs(b[c]-(in[c]*.758+bw[c]))<2e-7);}
            if(i==rate/5-1){assert(gain<.5);attenuated=true;}if(i==2*rate-1){assert(gain==1);recovered=true;}
        }
        assert(attenuated && recovered);int32_t report[16];disabled.decay(report);assert(report[13]==0);
    }
    std::puts("round2 defaults/ducking: five-rate bit identity with all additions off, passive graph, independent stereo-linked envelope oracle, wet-only attenuation and release to unity");
}
std::complex<double> advancedFeedback(unsigned length,unsigned rate,double frequency,const Settings& p,const Advanced& v){
    using C=std::complex<double>;
    const double mid=v[1]?std::pow(.001,double(length)/rate/(v[2]/100.)):.7+.28*p[2]/1000.;
    const C z=std::polar(1.,-2*pi*frequency/rate);
    if(!v[3]){const double damp=p[4]?std::pow(.4*p[4]/1000.,44100./rate):0.;return mid*(1-damp)/(1.-damp*z);}
    // Direct transfer-function oracle; no implementation state or coefficients are reused.
    const C warped=(1.-z)/(1.+z);
    const C low=1./(1.+warped/std::tan(pi*std::min(double(v[6]),rate*.45)/rate));
    const C high=1./(1.+warped/std::tan(pi*std::min(double(v[7]),rate*.45)/rate));
    const double bass=std::pow(mid,100./v[4]),treble=std::pow(mid,100./v[5]);
    return bass*low+mid*(high-low)+treble*(1.-high);
}
void advancedDecayResponse(){
    using C=std::complex<double>;
    constexpr unsigned comb[]{1116,1188,1277,1356,1422,1491,1557,1617},allpass[]{556,441,341,225};
    unsigned cases=0;
    for(unsigned rate:{8000,44100,48000,96000,192000})for(unsigned variant=0;variant<3;++variant){
        auto p=precise();p[2]=223;p[3]=1000;p[4]=variant==0?375:1000;p[5]=1000;p[6]=0;
        auto v=reverbtuning::advancedDefaults;v[1]=variant!=2;v[2]=20;v[3]=variant!=0;v[4]=25;v[5]=150;v[6]=317;v[7]=2317;v[13]=1;
        const double frequency=variant==0?200:variant==1?1000:3000;
        reverbtuning::Kernel kernel;kernel.configure(p,rate,false,v);
        const C z=std::polar(1.,-2*pi*frequency/rate);std::array<C,2> expected{},measured{};
        for(unsigned c=0;c<2;++c){
            for(auto length:comb){const unsigned samples=unsigned(std::lround((length+c*23)*rate/44100.));const C delay=std::pow(z,samples);expected[c]+=delay/(1.-advancedFeedback(samples,rate,frequency,p,v)*delay);}
            for(auto length:allpass){const unsigned samples=unsigned(std::lround((length+c*23)*rate/44100.));const C delay=std::pow(z,samples);expected[c]*=(1.5*delay-1.)/(1.-.5*delay);}
            expected[c]*=double(.015f)*3.;
        }
        const unsigned warm=rate*2,window=rate/5;
        for(unsigned i=0;i<warm+window;++i){float input[]{float(.1*std::cos(2*pi*frequency*i/rate)),0},out[2],wet[2];kernel.process(input,out,wet);
            if(i>=warm)for(unsigned c=0;c<2;++c)measured[c]+=double(out[c])*std::polar(1.,-2*pi*frequency*i/rate);}
        for(unsigned c=0;c<2;++c){measured[c]*=2/(.1*window);assert(std::abs(measured[c]-expected[c])<3e-4);}
        int32_t report[16];kernel.decay(report);const double lowHz=std::min(double(v[6]),rate*.45),highHz=std::min(double(v[7]),rate*.45);
        const double frequencies[]{std::min(80.,lowHz/4),std::sqrt(lowHz*highHz),std::min(rate*.45,std::max(8000.,highHz*2))};
        for(unsigned band=0;band<3;++band){double longest=0;for(unsigned c=0;c<2;++c)for(auto length:comb){const unsigned count=unsigned(std::lround((length+c*23)*rate/44100.));const double magnitude=std::abs(advancedFeedback(count,rate,frequencies[band],p,v));longest=std::max(longest,double(count)/rate*std::log(.001)/std::log(magnitude));}assert(std::abs(report[10+band]-longest*1000)<=.51);}
        if(v[1])assert(report[9]==200);++cases;
    }
    // Neutral band ratios give exactly the requested per-delay seconds, independent of sample rate.
    for(unsigned rate:{8000,48000,192000})for(int cs:{20,150,1000}){
        auto p=precise();auto v=reverbtuning::advancedDefaults;v[1]=v[3]=1;v[2]=cs;
        reverbtuning::Kernel kernel;kernel.configure(p,rate,false,v);int32_t r[16];kernel.decay(r);assert(r[9]==cs*10 && r[10]==cs*10 && r[11]==cs*10 && r[12]==cs*10);
    }
    std::printf("round2 decay: %u measured complex responses against independent seconds/three-band feedback oracle, plus nine exact neutral RT60 predictions\n",cases);
}
void measuredTailTimes(){
    // Time-domain energy slope checks seconds against the rendered impulse tail,
    // independent of both the response-report code and the frequency oracle.
    for(unsigned rate:{8000,48000,192000})for(int centiseconds:{50,150}){
        auto p=precise();p[3]=1000;p[5]=1000;p[6]=0;
        auto v=reverbtuning::advancedDefaults;v[1]=v[3]=1;v[2]=centiseconds;
        reverbtuning::Kernel kernel;kernel.configure(p,rate,false,v);
        const double requested=centiseconds/100.,start=.25*requested,bin=.1*requested;
        std::array<double,10> energy{};
        for(unsigned i=0;i<unsigned((start+10*bin)*rate);++i){
            float input[]{i==0?.5f:0.f,0},out[2],wet[2];kernel.process(input,out,wet);
            const int index=int(std::floor((double(i)/rate-start)/bin));
            if(index>=0 && index<10)energy[index]+=double(out[0])*out[0]+double(out[1])*out[1];
        }
        double sumX=0,sumY=0,sumXX=0,sumXY=0;
        for(unsigned i=0;i<10;++i){assert(energy[i]>0);const double x=start+(i+.5)*bin,y=10*std::log10(energy[i]);sumX+=x;sumY+=y;sumXX+=x*x;sumXY+=x*y;}
        const double slope=(10*sumXY-sumX*sumY)/(10*sumXX-sumX*sumX),measured=-60/slope;
        if(std::abs(measured/requested-1)>=.08)std::fprintf(stderr,"rendered RT60 mismatch fs=%u requested=%.3f measured=%.6f\n",rate,requested,measured);
        assert(std::abs(measured/requested-1)<.08);
    }
    std::puts("round2 RT60 time-domain: six actual stereo impulse-energy decay fits within 8 percent of requested seconds");
}
void advancedStabilityAndSnapshots(){
    for(unsigned rate:{8000,44100,48000,96000,192000})for(unsigned variation=0;variation<4;++variation){
        auto p=precise();p[2]=p[3]=p[5]=1000;p[4]=1000;p[6]=0;
        auto v=reverbtuning::advancedDefaults;v[1]=v[3]=1;v[2]=1000;v[4]=variation&1?25:400;v[5]=variation&2?25:200;v[6]=variation&1?80:1000;v[7]=variation&2?1000:16000;
        // Independent dense frequency check of the passive feedback network at all extremes.
        const unsigned length=unsigned(std::lround(1640.*rate/44100.));
        const double upper=std::pow(.001,double(length)/rate/(10*std::max({1.,v[4]/100.,v[5]/100.})));
        for(unsigned i=0;i<1024;++i)assert(std::abs(advancedFeedback(length,rate,(i+.5)*rate/2048.,p,v))<=upper+1e-12);
        reverbtuning::Kernel kernel;kernel.configure(p,rate,false,v);float out[2],wet[2];
        for(unsigned i=0;i<rate/2;++i){float input[]{i==0?.5f:0.f,0};kernel.process(input,out,wet);assert(std::isfinite(out[0]) && std::isfinite(out[1]) && std::abs(out[0])<2 && std::abs(out[1])<2);}
        v[1]=v[3]=0;kernel.retune(p,v);float silent[2]{};for(unsigned i=0;i<rate/10;++i){kernel.process(silent,out,wet);assert(std::isfinite(out[0]) && std::isfinite(out[1]));}
    }
    auto p=precise();p[3]=1000;p[5]=700;p[6]=0;auto aa=reverbtuning::advancedDefaults,bb=aa;aa[1]=aa[3]=1;aa[2]=20;aa[4]=25;bb[1]=1;bb[2]=150;
    reverbtuning::Engine engine;assert(engine.set(p.data(),18));auto t=extendedTools(4,p,aa,p,bb);assert(engine.setTools(t.data(),69));
    reverbtuning::Kernel reference;reference.configure(p,48000,true,aa);float out[2],expected[2],wet[2];
    auto invalid=t;invalid[69-1]=1;assert(!engine.setTools(invalid.data(),invalid.size()));
    invalid=t;invalid[21+2]=19;assert(!engine.setTools(invalid.data(),invalid.size()));
    assert(!engine.setTools(t.data(),68));auto invalidAdvanced=aa;invalidAdvanced[4]=401;assert(!engine.setAdvanced(invalidAdvanced.data(),15));
    int32_t beforeFailure[32];engine.runtime(beforeFailure,true);assert(beforeFailure[5]==4 && beforeFailure[11]==1);
    auto graphOnly=reverbtuning::advancedDefaults;graphOnly[13]=1;assert(engine.setAdvanced(graphOnly.data(),15));engine.runtime(beforeFailure,true);assert(beforeFailure[5]==4 && beforeFailure[11]==1);
    auto input=signal(48000,12000);for(unsigned i=0;i<12000;++i){engine.process(input.data()+i*2,out);reference.process(input.data()+i*2,expected,wet);if(i>480)assert(out[0]==expected[0] && out[1]==expected[1]);}
    aa[2]=1000;aa[4]=400;t=extendedTools(6,p,aa,p,bb,2);assert(engine.setTools(t.data(),69));int32_t runtime[32];engine.runtime(runtime,true);assert(runtime[25]>=40000 && runtime[25]<41000);
    allocations=0;countNew=true;for(unsigned i=0;i<12000;++i)engine.process(input.data()+i*2,out);countNew=false;assert(allocations==0);
    auto old=tools(4,p,p,3);assert(engine.setTools(old.data(),39));reference.configure(p,48000,true);for(unsigned i=0;i<12000;++i){engine.process(input.data()+i*2,out);reference.process(input.data()+i*2,expected,wet);if(i>480)assert(out[0]==expected[0] && out[1]==expected[1]);}
    assert(engine.setAdvanced(aa.data(),15));engine.runtime(runtime,true);assert(runtime[5]==0 && runtime[11]==0);
    std::puts("round2 stability/snapshots: twenty endpoint render cases, 20480 passive-loop frequency checks, exact advanced A/B capture, 40 s tail warmup, legacy tools compatibility and zero render allocations");
}
int command(effect_handle_t h,uint32_t code,uint32_t bytes=0,void* data=nullptr){int reply=-999;uint32_t size=4;const int rc=(*h)->command(h,code,bytes,data,&size,&reply);return rc?rc:reply;}
int send(effect_handle_t h,uint32_t id,const int32_t* value,unsigned words,unsigned extra=0){std::array<uint32_t,128> packet{};packet[1]=4;packet[2]=words*4;packet[3]=id;if(words)std::memcpy(packet.data()+4,value,words*4);return command(h,EFFECT_CMD_SET_PARAM,16+words*4+extra,packet.data());}
std::array<int32_t,32> get(effect_handle_t h){std::array<uint32_t,4> q{0,4,128,131222};std::array<uint32_t,36> response{};uint32_t bytes=sizeof(response);const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&bytes,response.data());assert(rc==0 || rc==-EAGAIN);std::array<int32_t,32> r{};if(!rc){assert(bytes==sizeof(response) && response[0]==0);std::memcpy(r.data(),response.data()+4,128);assert(!(r[1]&1));}return r;}
void protocol(){
    constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    auto v=reverbtuning::advancedDefaults;assert(send(h,65648,v.data(),15)==0 && send(h,65648,v.data(),14)==-EINVAL && send(h,65648,v.data(),15,4)==-EINVAL);
    constexpr int advLow[]{1,0,20,0,25,25,80,1000,0,-600,0,1,50,0,0},advHigh[]{1,1,1000,1,400,200,1000,16000,1,0,240,200,3000,1,0};
    for(unsigned i=0;i<15;++i)for(int value:{advLow[i]-1,advHigh[i]+1}){auto bad=v;bad[i]=value;assert(send(h,65648,bad.data(),15)==-EINVAL);}
    auto p=precise();assert(send(h,65645,p.data(),18)==0);assert(send(h,65645,p.data(),17)==-EINVAL && send(h,65645,p.data(),18,4)==-EINVAL);
    constexpr int low[]{1,0,0,0,0,0,0,0,0,0,20,0,1000,0,0,0,-120,0},high[]{1,1,1000,1000,1000,1000,1000,1,2000,1,1000,1,20000,1,1000,1,0,1};
    for(unsigned i=0;i<18;++i)for(int value:{low[i]-1,high[i]+1}){auto bad=p;bad[i]=value;assert(send(h,65645,bad.data(),18)==-EINVAL);}
    int32_t on=1,off=0;assert(send(h,65647,&off,1)==-EINVAL);auto t=tools(1,p,p);assert(send(h,65646,t.data(),39)==-EINVAL);
    effect_config_t config{};config.inputCfg.samplingRate=config.outputCfg.samplingRate=48000;config.inputCfg.channels=config.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;config.inputCfg.format=config.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;config.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;config.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;config.inputCfg.mask=config.outputCfg.mask=EFFECT_CONFIG_ALL;
    assert(command(h,EFFECT_CMD_SET_CONFIG,sizeof(config),&config)==0 && command(h,EFFECT_CMD_ENABLE)==0 && send(h,65559,&on,1)==0);
    p[17]=1;assert(send(h,65645,p.data(),18)==0 && send(h,65646,t.data(),39)==0);assert(get(h)[5]==1);
    auto extended=extendedTools(1,p,v,p,v);assert(send(h,65646,extended.data(),69)==0 && send(h,65646,extended.data(),68)==-EINVAL);
    {std::array<uint32_t,4> q{0,4,64,131223};std::array<uint32_t,20> reply{};uint32_t bytes=sizeof(reply);assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&bytes,reply.data())==0 && bytes==sizeof(reply) && reply[4]==1 && reply[6]==48000 && !(reply[5]&1));}
    std::atomic<bool> stop{false};std::thread poll([&]{while(!stop){const auto r=get(h);if(r[0])assert(r[2]==48000 && r[5]==1 && r[30]==1);}});
    std::array<float,512> input{},output{};for(unsigned i=0;i<input.size();++i)input[i]=.1f;
    audio_buffer_t inBuffer{},outBuffer{};inBuffer.frameCount=outBuffer.frameCount=256;inBuffer.raw=input.data();outBuffer.raw=output.data();for(unsigned i=0;i<100;++i)assert((*h)->process(h,&inBuffer,&outBuffer)==0);stop=true;poll.join();
    assert(send(h,65559,&off,1)==0 && get(h)[5]==0);assert(send(h,65646,t.data(),39)==-EINVAL);assert(send(h,65559,&on,1)==0 && send(h,65646,t.data(),39)==0);
    assert(command(h,EFFECT_CMD_DISABLE)==0);auto r=get(h);assert(r[5]==0 && r[30]==0 && r[16]==std::numeric_limits<int32_t>::min());
    assert(command(h,EFFECT_CMD_ENABLE)==0 && command(h,EFFECT_CMD_RESET)==0);r=get(h);assert(r[5]==0 && r[7]==0);AELI.release_effect(h);
    std::puts("protocol: strict 18/39-word payload and bounds, private cleanup rejection, independent epoch polling concurrent with rendering and lifecycle invalidation");
}
}
void* operator new(std::size_t size){if(countNew)++allocations;if(void* p=std::malloc(size))return p;throw std::bad_alloc();}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
int main(){legacy();dryAndLinked();precisionAndStability();predelay();wetFilters();dampingRateResponse();snapshotsAndLifecycle();auditionAndMeters();advancedDefaultsAndDucking();advancedDecayResponse();measuredTailTimes();advancedStabilityAndSnapshots();protocol();std::puts("ViPER reverb professional rounds 1 and 2 passed");}

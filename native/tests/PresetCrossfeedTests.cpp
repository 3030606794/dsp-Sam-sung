#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <thread>
#include <vector>
#include "essential.h"
#include "../viper/src/viper/utils/HeadphoneCrossfeed.h"
extern "C" {
#include "jdsp/jdsp_header.h"
#include "jdsp/Effects/presetCrossfeed.h"
void CrossfeedEnableUi27Reference(JamesDSPLib*,char);
void CrossfeedChangeModeUi27Reference(JamesDSPLib*,int);
void CrossfeedProcessUi27Reference(JamesDSPLib*,size_t);
void JamesDSPRefreshBlob(JamesDSPLib*,double);
void FFTConvolver2x4x2TestAllocationFailure(int);
void FFTConvolver2x4x2TestThreadFailure(int);
unsigned long FFTConvolver2x4x2TestAllocationCount(void);
extern audio_effect_library_t AELI;
}
#undef min
#undef max
thread_local bool watchNew=false;
thread_local unsigned newCount=0;
void* operator new(std::size_t n){if(watchNew)++newCount;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete[](void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
void operator delete[](void* p,std::size_t)noexcept{std::free(p);}
namespace {
constexpr double pi=3.14159265358979323846;
using S=std::array<int32_t,9>;
S base(int mode,bool pro=true){return {1,pro?1:0,mode,mode==1?650:700,mode==1?95:60,100,0,0,0};}
std::vector<float> signal(unsigned fs,unsigned frames){std::vector<float> v(frames*2);uint32_t seed=13;
    for(unsigned i=0;i<frames;++i){seed=seed*1664525+1013904223;const float noise=int32_t(seed)/2147483648.f*.001f;
        v[2*i]=float(.03*std::sin(2*pi*500*i/fs))+noise;v[2*i+1]=float(.025*std::cos(2*pi*1500*i/fs))-noise;}return v;}
struct Core {
    std::unique_ptr<JamesDSPLib> dsp=std::make_unique<JamesDSPLib>();
    bool reference;
    Core(unsigned fs,int mode,bool ref=false):reference(ref){
        JamesDSPInit(dsp.get(),512,float(fs));
        if(unsigned(dsp->fs)!=fs){dsp->fs=float(fs);JamesDSPRefreshBlob(dsp.get(),fs);PresetCrossfeedRefresh(dsp->advXF.professional,dsp.get());}
        if(ref){CrossfeedChangeModeUi27Reference(dsp.get(),mode);CrossfeedEnableUi27Reference(dsp.get(),1);}
        else{CrossfeedChangeMode(dsp.get(),mode);CrossfeedEnable(dsp.get(),1);}
    }
    ~Core(){JamesDSPFree(dsp.get());}
    void set(const S& v){assert(PresetCrossfeedSet(dsp->advXF.professional,dsp.get(),v.data(),v.size()));}
    auto status(){std::array<int32_t,24> v;PresetCrossfeedSnapshot(dsp->advXF.professional,v.data());return v;}
    std::vector<float> render(const std::vector<float>& input,unsigned chunk=509){
        std::vector<float> output(input.size());
        for(size_t pos=0;pos<input.size()/2;pos+=chunk){const auto n=std::min(size_t(chunk),input.size()/2-pos);
            for(size_t i=0;i<n;++i){dsp->tmpBuffer[0][i]=input[2*(pos+i)];dsp->tmpBuffer[1][i]=input[2*(pos+i)+1];}
            watchNew=true;if(reference)CrossfeedProcessUi27Reference(dsp.get(),n);else CrossfeedProcess(dsp.get(),n);watchNew=false;assert(newCount==0);
            for(size_t i=0;i<n;++i){output[2*(pos+i)]=dsp->tmpBuffer[0][i];output[2*(pos+i)+1]=dsp->tmpBuffer[1][i];}
        }return output;
    }
    void warm(unsigned fs){render(std::vector<float>(fs/2,0));}
};
void legacy(){
    // A fresh native child starts in mode 0 while the existing UI defaults to 5.
    // Even an identical configuration packet must repair a legacy-mode mismatch.
    {Core actual(48000,0),reference(48000,5,true);const auto p=base(5,false);actual.set(p);assert(actual.dsp->advXF.mode==5);
        const auto input=signal(48000,4096);assert(actual.render(input)==reference.render(input));
        CrossfeedChangeMode(actual.dsp.get(),0);actual.set(p);assert(actual.dsp->advXF.mode==5);}
    unsigned cases=0;
    for(unsigned fs:{44100u,48000u,88200u,96000u,192000u})for(int mode=0;mode<6;++mode){
        Core actual(fs,mode),reference(fs,mode,true);auto p=base(mode,false);p[3]=1999;p[4]=137;p[5]=17;p[6]=377;p[7]=-83;p[8]=59;actual.set(p);
        auto input=signal(fs,4096);assert(actual.render(input,173)==reference.render(input,173));++cases;
    }
    std::printf("Six-preset legacy: %u rate/mode cases are bit-identical to the isolated ui27 implementation with fine tuning disabled.\n",cases);
}
void neutralAndDry(){
    for(int mode=0;mode<6;++mode){Core actual(48000,mode),reference(48000,mode,true);auto p=base(mode);actual.set(p);actual.warm(48000);reference.warm(48000);
        const auto input=signal(48000,10000),a=actual.render(input,173),b=reference.render(input,173);
        for(size_t i=0;i<a.size();++i)assert(std::abs(a[i]-b[i])<2e-6f);
        p[5]=0;p[7]=-20;actual.set(p);actual.warm(48000);const auto dry=actual.render(input);
        for(size_t i=0;i<dry.size();++i)assert(std::abs(dry[i]-input[i]*std::pow(10.,-.1))<2e-6);
    }
    Core switched(48000,0);switched.set(base(0));switched.warm(48000);switched.set(base(0,false));switched.warm(48000);
    switched.set(base(3,false));switched.set(base(3));const auto changed=switched.render(signal(48000,4096));
    for(float value:changed)assert(std::isfinite(value));assert(switched.status()[12]==3);
    std::puts("Six-preset neutral controls preserve all six base sounds; 0% mix is module-local dry with output trim.");
}
std::complex<double> bsResponse(const S& s,unsigned fs,double hz,bool cross){
    using C=std::complex<double>;const C z=std::polar(1.,-2*pi*hz/fs);const double feed=s[4]/10.,gl=std::pow(10.,(-feed*5/6-3)/20),gh=1-std::pow(10.,(feed/6-3)/20);
    const double fc=s[3]*std::pow(2.,((-feed*5/6-3)-20*std::log10(gh))/12),bl=std::exp(-2*pi*s[3]/fs),bh=std::exp(-2*pi*fc/fs);
    const double delay=s[6]*fs/1e6;const unsigned integer=unsigned(delay);const double fraction=delay-integer;
    C h=cross?gl*(1-bl)/(1.-bl*z)*std::pow(z,integer)*((1-fraction)+fraction*z):((1-gh*(1-bh))-bh*z)/(1.-bh*z);
    h=h/(1-gh+gl)*(s[5]/100.);if(!cross)h+=1-s[5]/100.;return h*std::pow(10.,s[7]/200.);
}
void bsMatrix(){
    unsigned cases=0;
    for(unsigned fs:{44100u,48000u,96000u})for(int mode:{0,1})for(double hz:{50.,700.,6000.}){
        Core actual(fs,mode);auto s=base(mode);s[3]=1053;s[4]=73;s[5]=43;s[6]=137;s[7]=-17;actual.set(s);
        const unsigned count=fs/5;std::vector<float> input((fs+count)*2);
        for(unsigned i=0;i<fs+count;++i)input[2*i]=float(.03*std::cos(2*pi*hz*i/fs));const auto out=actual.render(input);
        std::complex<double> h[2]{};for(unsigned i=fs;i<fs+count;++i)for(unsigned c=0;c<2;++c)h[c]+=double(out[2*i+c])*std::polar(1.,-2*pi*hz*i/fs);
        for(unsigned c=0;c<2;++c){h[c]*=2/(.03*count);assert(std::abs(h[c]-bsResponse(s,fs,hz,c!=0))<2e-5);}++cases;
    }
    std::printf("Six-preset BS2B: %u independent frequency/phase checks cover cutoff, 0.1 dB feed, mix, fractional delay and trim.\n",cases);
}
void spatialImpulses(){
    for(int mode=2;mode<6;++mode)for(unsigned direction=0;direction<2;++direction){
        Core actual(48000,mode);auto s=base(mode);s[5]=71;s[6]=137;s[7]=-12;s[8]=-31;actual.set(s);actual.warm(48000);
        const auto* dsp=actual.dsp.get();const unsigned length=mode<5?dsp->blobsResampledLen:dsp->frameLenSVirResampled;
        const float* direct=mode<5?(direction?dsp->blobsCh4[mode-2]:dsp->blobsCh1[mode-2]):dsp->hrtfblobsResampled[direction?3:0];
        const float* cross=mode<5?(direction?dsp->blobsCh3[mode-2]:dsp->blobsCh2[mode-2]):dsp->hrtfblobsResampled[direction?2:1];
        std::vector<float> input((length+512)*2);input[direction]=.03f;const auto output=actual.render(input,173);
        const double delay=s[6]*.048;const unsigned integer=unsigned(delay);const double fraction=delay-integer;
        const double gain=std::pow(10.,s[7]/200.),crossGain=std::pow(10.,s[8]/200.),mix=s[5]/100.;
        for(unsigned i=0;i<length+512;++i){
            const double d=(i<length?.03*direct[i]:0)*mix*gain+(i==0?.03*(1-mix)*gain:0);
            double c=0;if(i>=integer && i-integer<length)c+=cross[i-integer]*(1-fraction);
            if(i>integer && i-integer-1<length)c+=cross[i-integer-1]*fraction;c*=.03*mix*gain*crossGain;
            assert(std::abs(output[2*i+direction]-d)<3e-6);assert(std::abs(output[2*i+1-direction]-c)<3e-6);
        }
    }
    std::puts("Four spatial models: both input directions match the original four-channel impulses with independent cross-branch level/fractional delay, mix and trim.");
}
std::array<int32_t,21> tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,21> v{};v[0]=mode;v[1]=match;v[2]=epoch;std::copy(a.begin(),a.end(),v.begin()+3);std::copy(b.begin(),b.end(),v.begin()+12);return v;}
void auditions(){
    Core actual(48000,0);auto a=base(0),b=base(3);b[5]=60;b[7]=-30;actual.set(a);actual.warm(48000);
    auto v=tools(6,a,b);assert(PresetCrossfeedTools(actual.dsp->advXF.professional,actual.dsp.get(),v.data(),21));
    const auto input=signal(48000,48000*4);actual.render(input);auto r=actual.status();assert(r[6]==2 && r[8]<=0 && r[9]<=0);
    double energy[2]{};
    for(unsigned slot=0;slot<2;++slot){v=tools(4+slot,a,b,true);assert(PresetCrossfeedTools(actual.dsp->advXF.professional,actual.dsp.get(),v.data(),21));const auto out=actual.render(input);
        for(size_t i=48000*2;i<out.size();++i)energy[slot]+=double(out[i])*out[i];}
    assert(std::abs(10*std::log10(energy[0]/energy[1]))<.05);
    PresetCrossfeedClear(actual.dsp->advXF.professional);r=actual.status();assert(r[5]==0 && r[6]==0 && r[10]==0);
    v=tools(4,a,b,true);assert(!PresetCrossfeedTools(actual.dsp->advXF.professional,actual.dsp.get(),v.data(),21));
    v=tools(6,a,b);assert(PresetCrossfeedTools(actual.dsp->advXF.professional,actual.dsp.get(),v.data(),21));actual.render(std::vector<float>(48000*8));assert(actual.status()[6]==3);
    v=tools(1,a,b);assert(PresetCrossfeedTools(actual.dsp->advXF.professional,actual.dsp.get(),v.data(),21));const auto dry=actual.render(input);
    for(size_t i=1000;i<dry.size();++i)assert(std::abs(dry[i]-input[i])<2e-6);
    CrossfeedDisable(actual.dsp.get());r=actual.status();assert(!r[3] && !r[5]);
    std::puts("Six-preset auditions: cross-model same-input fixed-RMS A/B, dry, silence rejection, module-off cleanup and zero C++ callback allocations.");
}
using A=std::array<int32_t,10>;
constexpr A neutralAdvanced{1,0,1,0,0,0,3000,1,0,0};
void advanced(Core& core,const A& value){assert(PresetCrossfeedAdvancedSet(core.dsp->advXF.professional,core.dsp.get(),value.data(),10));}
void presetSwitchStability(){
    Core actual(48000,2);actual.set(base(2,false));const auto old0=actual.dsp->advXF.conv[0];
    FFTConvolver2x4x2TestAllocationFailure(-1);
    for(unsigned i=0;i<100;++i)CrossfeedEnable(actual.dsp.get(),1);
    assert(FFTConvolver2x4x2TestAllocationCount()==0 && old0==actual.dsp->advXF.conv[0]);
    for(int from=0;from<6;++from)for(int to=0;to<6;++to){
        actual.set(base(from,false));actual.render(signal(48000,173),173);actual.set(base(to,false));
        CrossfeedEnable(actual.dsp.get(),1);auto output=actual.render(signal(48000,173),173);
        for(float x:output)assert(std::isfinite(x));
    }
    assert(FFTConvolver2x4x2TestAllocationCount()==0);
    for(int mode:{2,3,4,5}){
        actual.set(base(mode,false));std::vector<float> impulse(2);impulse[0]=.2f;actual.render(impulse,1);
        actual.set(base(0,false));actual.render(std::vector<float>(34));actual.set(base(mode,false));
        const auto silence=actual.render(std::vector<float>(4096));for(float x:silence)assert(x==0);
    }
    for(int fail:{0,1,5,20,55,100}){
        const auto before=actual.dsp->advXF.conv[0];actual.dsp->crossfeedForceRefresh=1;
        FFTConvolver2x4x2TestAllocationFailure(fail);CrossfeedEnable(actual.dsp.get(),1);
        assert(before==actual.dsp->advXF.conv[0] && actual.dsp->crossfeedEnabled);
        assert(actual.dsp->crossfeedForceRefresh);FFTConvolver2x4x2TestAllocationFailure(-1);
        actual.dsp->crossfeedForceRefresh=0;
    }
    Core control(48000,0),failed(48000,0);control.set(base(0));failed.set(base(0));
    for(auto* c:{&control,&failed}){c->warm(48000);c->set(base(3));c->render(signal(48000,173),173);}
    auto next=base(5);FFTConvolver2x4x2TestAllocationFailure(0);
    assert(!PresetCrossfeedSet(failed.dsp->advXF.professional,failed.dsp.get(),next.data(),9));
    FFTConvolver2x4x2TestAllocationFailure(-1);
    const auto input=signal(48000,8192);assert(failed.render(input)==control.render(input));
    for(auto* c:{&control,&failed}){auto t=tools(4,base(3),base(0));assert(PresetCrossfeedTools(c->dsp->advXF.professional,c->dsp.get(),t.data(),21));c->render(input);}
    auto t=tools(4,base(0),base(5),false,2);FFTConvolver2x4x2TestAllocationFailure(0);
    assert(!PresetCrossfeedTools(failed.dsp->advXF.professional,failed.dsp.get(),t.data(),21));FFTConvolver2x4x2TestAllocationFailure(-1);
    assert(failed.render(input)==control.render(input));
    for(int from=0;from<6;++from)for(int to=0;to<6;++to){actual.set(base(from));actual.render(signal(48000,31),31);actual.set(base(to));
        for(float x:actual.render(signal(48000,31),31))assert(std::isfinite(x));}
    std::puts("Preset switching: both 6x6 transition matrices, repeated enables allocate zero, returning presets contain no stale tails, failures preserve live fades and A/B kernels.");
}
void allocationFailures(){
    std::array<float,512> ir{};ir[0]=.5f;ir[511]=.1f;
    FFTConvolver2x4x2 shortConv{};FFTConvolver2x4x2Init(&shortConv);
    FFTConvolver2x4x2TestAllocationFailure(-1);assert(FFTConvolver2x4x2LoadImpulseResponse(&shortConv,16,ir.data(),ir.data(),ir.data(),ir.data(),32));
    const auto shortCount=FFTConvolver2x4x2TestAllocationCount();FFTConvolver2x4x2Free(&shortConv);
    for(unsigned i=0;i<shortCount;++i){FFTConvolver2x4x2TestAllocationFailure(i);
        assert(!FFTConvolver2x4x2LoadImpulseResponse(&shortConv,16,ir.data(),ir.data(),ir.data(),ir.data(),32));
        FFTConvolver2x4x2Free(&shortConv);}
    TwoStageFFTConvolver2x4x2 longConv{};TwoStageFFTConvolver2x4x2Init(&longConv);
    FFTConvolver2x4x2TestAllocationFailure(-1);assert(TwoStageFFTConvolver2x4x2LoadImpulseResponse(&longConv,64,128,ir.data(),ir.data(),ir.data(),ir.data(),512));
    const auto longCount=FFTConvolver2x4x2TestAllocationCount();TwoStageFFTConvolver2x4x2Free(&longConv);
    for(unsigned i=0;i<longCount;++i){FFTConvolver2x4x2TestAllocationFailure(i);
        assert(!TwoStageFFTConvolver2x4x2LoadImpulseResponse(&longConv,64,128,ir.data(),ir.data(),ir.data(),ir.data(),512));
        TwoStageFFTConvolver2x4x2Free(&longConv);}
    FFTConvolver2x4x2TestAllocationFailure(-1);
    for(int stage=0;stage<4;++stage){
        FFTConvolver2x4x2TestThreadFailure(stage);
        assert(!TwoStageFFTConvolver2x4x2LoadImpulseResponse(&longConv,64,128,ir.data(),ir.data(),ir.data(),ir.data(),512));
        assert(longConv.shared_info.state==NOTHING);TwoStageFFTConvolver2x4x2Free(&longConv);
    }
    FFTConvolver2x4x2TestThreadFailure(-1);
    assert(TwoStageFFTConvolver2x4x2LoadImpulseResponse(&longConv,64,128,ir.data(),ir.data(),ir.data(),ir.data(),512));
    TwoStageFFTConvolver2x4x2Free(&longConv);
    std::puts("Worker setup: all four condition/mutex/create failure stages unwind without waiting for a nonexistent worker, and retry succeeds.");
    std::printf("Checked FFT allocations: every %lu short and %lu two-stage allocation failure safely unwinds.\n",shortCount,longCount);
}
void crossThreadWorkerLifecycle(){
    std::array<float,512> ir{};ir[0]=.5f;ir[511]=.1f;
    TwoStageFFTConvolver2x4x2 convolver{};TwoStageFFTConvolver2x4x2Init(&convolver);
    const auto interleaved=signal(48000,8192);std::vector<float> left(8192),right(8192),outLeft(8192),outRight(8192);
    for(unsigned i=0;i<8192;++i){left[i]=interleaved[2*i];right[i]=interleaved[2*i+1];}
    std::atomic<int> phase=0;
    // Keep all three callers alive concurrently, guaranteeing distinct thread identities.
    std::thread loader([&]{assert(TwoStageFFTConvolver2x4x2LoadImpulseResponse(&convolver,64,128,ir.data(),ir.data(),ir.data(),ir.data(),512));
        assert(convolver.workerStarted);phase.store(1);while(phase.load()<4)std::this_thread::yield();});
    std::thread processor([&]{while(phase.load()<1)std::this_thread::yield();
        for(unsigned offset=0;offset<8192;offset+=173){const unsigned count=std::min(173u,8192-offset);
            watchNew=true;TwoStageFFTConvolver2x4x2Process(&convolver,left.data()+offset,right.data()+offset,outLeft.data()+offset,outRight.data()+offset,count);
            watchNew=false;assert(newCount==0);}
        phase.store(2);while(phase.load()<4)std::this_thread::yield();});
    std::thread closer([&]{while(phase.load()<2)std::this_thread::yield();
        TwoStageFFTConvolver2x4x2ResetHistory(&convolver);
        std::array<float,1024> silence{},l{},r{};TwoStageFFTConvolver2x4x2Process(&convolver,silence.data(),silence.data(),l.data(),r.data(),1024);
        for(unsigned i=0;i<1024;++i)assert(l[i]==0 && r[i]==0);
        TwoStageFFTConvolver2x4x2Free(&convolver);assert(!convolver.workerStarted);phase.store(3);});
    while(phase.load()<3)std::this_thread::yield();phase.store(4);loader.join();processor.join();closer.join();
    for(unsigned i=0;i<8192;++i){const double expected=.5*(left[i]+right[i])+(i>=511?.1*(left[i-511]+right[i-511]):0);
        assert(std::abs(outLeft[i]-expected)<2e-6 && std::abs(outRight[i]-expected)<2e-6);}
    std::puts("Two-stage worker: distinct loader/audio/reset+release threads, actual long-tail jobs, independent FIR oracle, zero callback allocation, and pending-job shutdown.");
}
void liveRateAndRetry(){
    for(int mode=0;mode<6;++mode){
        Core actual(48000,mode);actual.set(base(mode,false));
        for(unsigned fs:{44100u,48000u,44100u}){
            JamesDSPSetSampleRate(actual.dsp.get(),float(fs),0);
            assert(actual.dsp->crossfeedEnabled && !actual.dsp->crossfeedForceRefresh);
            assert(actual.dsp->advXF.preparedRate==float(fs));
            Core reference(fs,mode,true);const auto input=signal(fs,4096);
            assert(actual.render(input,173)==reference.render(input,173));
        }
    }
    Core failed(48000,3),reference(48000,3);auto s=base(3);failed.set(s);reference.set(s);
    FFTConvolver2x4x2TestAllocationFailure(0);PresetCrossfeedRefresh(failed.dsp->advXF.professional,failed.dsp.get());
    assert(!failed.status()[23]);FFTConvolver2x4x2TestAllocationFailure(-1);
    failed.set(s);assert(failed.status()[23]);failed.warm(48000);reference.warm(48000);
    const auto input=signal(48000,8192);assert(failed.render(input)==reference.render(input));
    std::puts("Live format changes: all six active legacy presets rebuild at 48/44.1 kHz and match fresh references; identical professional settings recover after a failed refresh.");
}
void advancedPaths(){
    unsigned cases=0;
    for(unsigned fs:{8000u,48000u,96000u})for(int slope:{1,2})for(int direction:{0,1})for(double hz:{100.,2500.}){
        Core actual(fs,0);const auto s=base(0);actual.set(s);
        A a=neutralAdvanced;a[1]=1;a[2]=0;a[3]=-31;a[4]=27;a[5]=1;a[6]=1200;a[7]=slope;a[8]=1;a[9]=-17;advanced(actual,a);
        const unsigned count=fs/2;std::vector<float> input((fs+count)*2);
        for(unsigned i=0;i<fs+count;++i)input[2*i+direction]=float(.03*std::cos(2*pi*hz*i/fs));
        const auto out=actual.render(input);std::complex<double> measured[2]{};
        for(unsigned i=fs;i<fs+count;++i)for(unsigned c=0;c<2;++c)measured[c]+=double(out[2*i+c])*std::polar(1.,-2*pi*hz*i/fs);
        for(auto& h:measured)h*=2/(.03*count);
        const double k=std::tan(pi*1200/fs),pole=(1-k)/(1+k);const auto z=std::polar(1.,-2*pi*hz/fs);
        const auto low=(1-pole)*.5*(1.+z)/(1.-pole*z);
        const auto direct=bsResponse(s,fs,hz,false),cross=bsResponse(s,fs,hz,true)*std::pow(low,slope)*std::pow(10.,a[direction?4:3]/200.);
        const auto center=(direct+cross)*.5*(std::pow(10.,a[9]/200.)-1);
        assert(std::abs(measured[direction]-(direct+center))<2e-5);assert(std::abs(measured[1-direction]-(cross+center))<2e-5);++cases;
    }
    for(int mode=0;mode<6;++mode){Core a(48000,mode),b(48000,mode);a.set(base(mode));b.set(base(mode));
        A disabled=neutralAdvanced;disabled[3]=60;disabled[4]=-120;disabled[6]=300;disabled[7]=2;disabled[9]=60;advanced(a,disabled);
        a.warm(48000);b.warm(48000);const auto input=signal(48000,4096);assert(a.render(input)==b.render(input));}
    Core main(48000,0),reference(48000,0);auto setting=base(0);main.set(setting);reference.set(setting);
    A a=neutralAdvanced,b=neutralAdvanced;b[8]=1;b[9]=-60;advanced(reference,b);
    std::array<int32_t,21> av{};av[0]=7;std::copy(a.begin(),a.end(),av.begin()+1);std::copy(b.begin(),b.end(),av.begin()+11);
    assert(PresetCrossfeedAdvancedTools(main.dsp->advXF.professional,main.dsp.get(),av.data(),21));
    auto t=tools(5,setting,setting,false,7);assert(PresetCrossfeedTools(main.dsp->advXF.professional,main.dsp.get(),t.data(),21));
    main.warm(48000);reference.warm(48000);const auto input=signal(48000,4096),x=main.render(input),y=reference.render(input);
    for(unsigned i=0;i<x.size();++i)assert(std::abs(x[i]-y[i])<2e-6);
    std::printf("Advanced six-preset controls: %u independent complex matrix checks at three rates; disabled groups preserve all six samples exactly; A/B carries independent advanced settings.\n",cases);
}
void headphoneEnhancements(){
    using headphone::Kernel;using headphone::Settings;using headphone::Advanced;
    for(unsigned fs:{8000u,44100u,48000u,96000u,192000u})for(int slope=1;slope<=4;++slope){
        Settings p=headphone::defaults;p[1]=1;p[7]=1;p[4]=71;p[6]=-17;p[5]=137;
        Advanced a=headphone::advancedDefaults;a[1]=1;a[2]=100;a[3]=1;a[4]=43;a[5]=107;a[6]=71;a[7]=311;a[8]=1;a[9]=slope;a[10]=1;
        Kernel k;k.configure(p,fs,a);std::array<int32_t,192> curves;k.response(curves.data());
        const auto input=signal(fs,fs/2);float out[2],cross[2];
        for(unsigned i=0;i<fs/2;++i){k.process(input.data()+2*i,out,cross);const double expected=(input[2*i]+input[2*i+1])*std::pow(10.,p[6]/200.);
            assert(std::abs(out[0]+out[1]-expected)<2e-7);}
        // Asymmetry intentionally retains Mid -> Side differences. For linked paths each
        // mono output is flat; the preceding signal check verifies the Mid sum when unlinked.
        a[3]=0;k.configure(p,fs,a);k.response(curves.data());
        for(unsigned i=0;i<32;++i){const auto expected=int(std::llround(p[6]*10.));assert(std::abs(curves[i*6+4]-expected)<=1 && std::abs(curves[i*6+5]-expected)<=1);}
    }
    // The diagnostic curves must agree with measured direct/cross transfer, including post filters.
    for(int slope=1;slope<=4;++slope)for(int outputFilter:{0,1}){
        Settings p=headphone::defaults;p[1]=1;p[7]=outputFilter;p[4]=63;p[6]=-11;
        Advanced a=headphone::advancedDefaults;a[1]=1;a[2]=47;a[3]=1;a[4]=43;a[5]=107;a[6]=71;a[7]=311;a[8]=1;a[9]=slope;
        Kernel k;k.configure(p,48000,a);std::array<int32_t,192> curves;k.response(curves.data());
        const unsigned index=17;const double hz=20*std::pow(20000./20,index/31.);std::complex<double> measured[2]{};
        for(unsigned i=0;i<144000;++i){float in[2]={float(.03*std::cos(2*pi*hz*i/48000)),0},out[2],cross[2];k.process(in,out,cross);
            if(i>=48000)for(unsigned c=0;c<2;++c)measured[c]+=double(out[c])*std::polar(1.,-2*pi*hz*i/48000);}
        const unsigned curve[2]={0,3};for(unsigned c=0;c<2;++c){measured[c]*=2/(.03*96000);const double actual=20*std::log10(std::abs(measured[c]));assert(std::abs(actual-curves[index*6+curve[c]]/100.)<.02);}
    }
    std::puts("Headphone enhancements: full Mid preservation across five rates/four slopes, asymmetric paths, and measured direct/cross curves with both output filters.");
}
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
struct Effect {
    effect_handle_t h=nullptr;
    Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=48000;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;
        assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);assert(command(EFFECT_CMD_ENABLE)==0);}
    void rate(unsigned fs){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
    ~Effect(){AELI.release_effect(h);}
    int command(uint32_t id,uint32_t n=0,void* p=nullptr){int32_t status=0;uint32_t bytes=4;const auto r=(*h)->command(h,id,n,p,&bytes,&status);return r?r:status;}
    int raw(uint32_t id,const void* p,unsigned n,unsigned trailing=0){std::array<uint32_t,64> v{};v[1]=4;v[2]=n;v[3]=id;std::memcpy(v.data()+4,p,n);return command(EFFECT_CMD_SET_PARAM,16+n+trailing,v.data());}
    template<size_t N> auto get(unsigned id){std::array<uint32_t,4> q{0,4,N*4,id};std::array<uint32_t,N+4> r{};uint32_t n=sizeof(r);
        const int status=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(status==0 || status==-EAGAIN);std::array<int32_t,N> result{};
        if(!status){assert(n==16+N*4 && !r[0] && r[4]==1 && !(r[5]&1));std::memcpy(result.data(),r.data()+4,N*4);}return result;}
};
void protocol(){
    Effect e;auto s=base(0);assert(e.raw(1420,s.data(),36)==0);assert(e.raw(1420,s.data(),32)==-EINVAL);assert(e.raw(1420,s.data(),36,4)==-EINVAL);
    for(unsigned i=0;i<9;++i){auto bad=s;bad[i]=INT32_MAX;assert(e.raw(1420,bad.data(),36)==-EINVAL);}
    auto a=headphone::advancedDefaults;assert(e.raw(65643,a.data(),44)==0);assert(e.raw(65643,a.data(),40)==-EINVAL);assert(e.raw(65643,a.data(),44,4)==-EINVAL);
    for(unsigned i=0;i<11;++i){auto bad=a;bad[i]=INT32_MAX;assert(e.raw(65643,bad.data(),44)==-EINVAL);}
    A advanced=neutralAdvanced;assert(e.raw(1422,advanced.data(),40)==0);assert(e.raw(1422,advanced.data(),36)==-EINVAL);assert(e.raw(1422,advanced.data(),40,4)==-EINVAL);
    for(unsigned i=0;i<10;++i){auto bad=advanced;bad[i]=INT32_MAX;assert(e.raw(1422,bad.data(),40)==-EINVAL);}
    std::array<int32_t,21> advancedTools{};std::copy(advanced.begin(),advanced.end(),advancedTools.begin()+1);std::copy(advanced.begin(),advanced.end(),advancedTools.begin()+11);
    assert(e.raw(1423,advancedTools.data(),84)==0);assert(e.raw(1423,advancedTools.data(),80)==-EINVAL);
    int16_t on=1;assert(e.raw(1208,&on,2)==0);auto t=tools(1,s,s);assert(e.raw(1421,t.data(),84)==0);
    int32_t on32=1;assert(e.raw(65581,&on32,4)==0);auto hp=headphone::defaults;hp[1]=1;assert(e.raw(65640,hp.data(),32)==0);
    a[10]=1;assert(e.raw(65643,a.data(),44)==0);e.get<24>(131220);e.get<212>(131221);
    std::atomic<bool> done=false;std::atomic<unsigned> reads=0;
    std::thread audio([&]{std::array<float,1024> in{},out{};while(!done){audio_buffer_t x{},y{};x.frameCount=y.frameCount=512;x.f32=in.data();y.f32=out.data();assert((*e.h)->process(e.h,&x,&y)==0);}});
    for(unsigned i=0;i<1000;++i){e.get<24>(131220);e.get<212>(131221);++reads;}
    done=true;audio.join();assert(reads==1000);
    for(int mode:{0,3,5}){auto legacy=base(mode,false);assert(e.raw(1420,legacy.data(),36)==0);
        for(unsigned fs:{44100u,48000u}){e.rate(fs);auto status=e.get<24>(131220);assert(status[2]==int(fs) && status[3]==1);
            std::array<float,1024> in{},out{};in[0]=.03f;audio_buffer_t x{},y{};x.frameCount=y.frameCount=512;x.f32=in.data();y.f32=out.data();
            assert((*e.h)->process(e.h,&x,&y)==0);for(float value:out)assert(std::isfinite(value));}}
    assert(e.command(EFFECT_CMD_DISABLE)==0);assert(e.get<24>(131220)[5]==0);
    assert(e.command(FUSION_PRESET_XF_CLEAR)==-EINVAL);
    std::puts("Both crossfeed bridges: exact packet lengths/ranges, trailing-byte rejection, independent state, read-only coherent lock-free runtime under audio load and lifecycle cleanup.");
}
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);legacy();neutralAndDry();bsMatrix();spatialImpulses();auditions();allocationFailures();presetSwitchStability();crossThreadWorkerLifecycle();liveRateAndRetry();advancedPaths();headphoneEnhancements();protocol();std::puts("Crossfeed round two passed.");}

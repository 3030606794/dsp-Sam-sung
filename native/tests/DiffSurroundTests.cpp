#include "DiffSurroundReference.h"
#include "../viper/src/viper/effects/DiffSurround.h"
#include <cassert>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <vector>

static thread_local bool watchAllocations=false;
static thread_local unsigned allocations=0;
void* operator new(std::size_t size){if(watchAllocations)++allocations;if(void* p=std::malloc(size))return p;throw std::bad_alloc();}
void operator delete(void* p) noexcept{std::free(p);}
void operator delete(void* p,std::size_t) noexcept{std::free(p);}

namespace {
constexpr double pi=3.14159265358979323846;
using Params=std::array<int32_t,7>;
constexpr Params defaults{1,500,0,1000,0,150,12000};
std::vector<float> noise(unsigned frames) {
    std::vector<float> v(2*frames);uint32_t seed=33;
    for(float& x:v){seed=1664525*seed+1013904223;x=(int32_t(seed>>8)-8388608)/8388608.f;}return v;
}
template<class T>void render(T& e,std::vector<float>& data,unsigned block=127) {
    for(size_t i=0;i<data.size()/2;i+=block)e.Process(data.data()+i*2,std::min<size_t>(block,data.size()/2-i));
}
void configure(DiffSurround& e,unsigned fs,Params p=defaults) {e.SetSamplingRate(fs);assert(e.SetAdvanced(p.data(),p.size()));e.SetEnable(true);}
void legacy() {
    for(unsigned fs:{8000u,11025u,44100u,48000u,88200u,96000u,192000u})for(float delay:{0.f,.01f,.3f,1.f,5.f,20.f,50.f}) {
        DiffSurround actual;DiffSurroundReference expected;actual.SetSamplingRate(fs);expected.SetSamplingRate(fs);
        actual.SetDelayTime(delay);expected.SetDelayTime(delay);actual.SetEnable(true);expected.SetEnable(true);
        auto a=noise(fs/12+1025),b=a;render(actual,a,31);render(expected,b,1025);assert(a==b);
        actual.SetDelayTime(delay);expected.SetDelayTime(delay);a=noise(4097);b=a;render(actual,a,1);render(expected,b,512);assert(a==b);
        actual.SetDelayTime(1.23f);expected.SetDelayTime(1.23f);a=noise(4097);b=a;render(actual,a);render(expected,b);assert(a==b);
        actual.Reset();expected.Reset();a=noise(4097);b=a;render(actual,a);render(expected,b);assert(a==b);
    }
    std::puts("Diff legacy: 49 rate/delay scenarios, repeated commands, delay edits and reset match frozen ef77b01 exactly.");
}
void fractional() {
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u})for(int ticks:{0,1,30,123,2000})for(int channel:{0,1}) {
        Params p=defaults;p[1]=ticks;p[2]=channel;DiffSurround e;configure(e,fs,p);
        std::vector<float> v(2*(fs/20+2048));v[0]=v[1]=1;render(e,v);
        const double frames=float(fs*ticks)/100000.f;const unsigned n=frames;const float fraction=frames-n;
        for(unsigned i=0;i<v.size()/2;++i) {
            const unsigned selected=channel==0?1:0,other=1-selected;
            const float expected=i==n?1-fraction:i==n+1?fraction:0;
            assert(std::abs(v[2*i+selected]-expected)<1.e-6);assert(v[2*i+other]==(i==0?1.f:0.f));
        }
        assert(e.GetRuntime(0)==ticks*10);assert(std::abs(e.GetRuntime(1)-std::lround(frames*1000))<=1);assert(e.GetRuntime(3)==0);
    }
    std::puts("Diff precision: true fractional impulse weights, both directions and reported samples agree at six rates.");
}
double bandResponse(unsigned fs,const Params& p,double omega) {
    const unsigned m=std::lround(fs*.020);const double lo=p[5],hi=std::min(double(p[6]),fs*.5-100);
    double value=2*(hi-lo)/fs;
    for(unsigned k=1;k<=m;++k)value+=2*(std::sin(2*pi*hi*k/fs)-std::sin(2*pi*lo*k/fs))/(pi*k)*(1-k/double(m+1))*std::cos(omega*k);
    return value;
}
void firTransfer() {
    double largest=0;
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u})for(int bin:{3,17,103,701})for(int mix:{0,500,1000}) {
        Params p=defaults;p[1]=123;p[3]=mix;p[4]=1;DiffSurround e;configure(e,fs,p);
        const unsigned n=4096,warm=fs/5;const double omega=2*pi*bin/n;
        std::vector<float> v(2*(warm+n));for(unsigned i=0;i<warm+n;++i)v[2*i]=v[2*i+1]=.6f*std::sin(omega*i);
        const auto original=v;render(e,v,511);
        std::complex<double> left{},right{},input{};
        for(unsigned i=warm;i<warm+n;++i){const auto phase=std::polar(1.,-omega*i);left+=double(v[2*i])*phase;right+=double(v[2*i+1])*phase;input+=double(original[2*i])*phase;}
        left/=input;right/=input;
        const double b=bandResponse(fs,p,omega);assert(b>=-1.e-10 && b<=1+1.e-10);
        const double delay=float(fs*p[1])/100000.f;const unsigned integer=delay;const double fraction=delay-integer;
        const auto d=(1-fraction)*std::polar(1.,-omega*integer)+fraction*std::polar(1.,-omega*(integer+1));
        const auto common=std::polar(1.,-omega*e.GetRuntime(3));
        const auto expected=common*(1-p[3]/1000.*b+p[3]/1000.*b*d);
        largest=std::max(largest,std::abs(right-expected));assert(std::abs(left-common)<1.e-5);assert(std::abs(right-expected)<2.e-5);
        assert(std::abs(right)<=1.00002);
        assert(e.GetRuntime(4)==std::lround(e.GetRuntime(3)*1000000./fs));
    }
    // Zero differential delay is the same common linear-phase delay on both channels.
    Params p=defaults;p[1]=0;p[4]=1;DiffSurround e;configure(e,48000,p);auto a=noise(12000),original=a;render(e,a);
    const unsigned common=e.GetRuntime(3);for(unsigned i=common;i<a.size()/2;++i){assert(std::abs(a[2*i]-original[2*(i-common)])<2.e-6);assert(std::abs(a[2*i+1]-original[2*(i-common)+1])<2.e-6);}
    std::printf("Diff FIR: independent Fejer + fractional-delay complex response, common latency and no unintended gain passed; max error %.9g.\n",largest);
}
void metersAndAudition() {
    for(int kind=0;kind<5;++kind) {
        Params p=defaults;p[1]=0;DiffSurround e;configure(e,48000,p);std::vector<float> v(9600);
        for(unsigned i=0;i<4800;++i){const float x=.5f*std::sin(2*pi*1000*i/48000);v[2*i]=kind==4?0:x;v[2*i+1]=kind==0?x:kind==1?-x:kind==2?0:kind==3?.5f*std::cos(2*pi*1000*i/48000):0;}
        e.Process(v.data(),4799);assert(e.GetRuntime(17)==0);e.Process(v.data()+9598,1);assert(e.GetRuntime(17)==1);
        if(kind==0)assert(e.GetRuntime(9)==0 && e.GetRuntime(10)==0);
        if(kind==1 || kind==4)assert(e.GetRuntime(9)==ImagingMeter::invalid);
        if(kind==2 || kind==3)assert(std::abs(e.GetRuntime(9)+301)<=1);
        if(kind==2 || kind==4)assert(e.GetRuntime(7)==ImagingMeter::invalid);
        for(unsigned i=7;i<=16;++i){e.ClearRuntime();assert(e.GetRuntime(i)==ImagingMeter::invalid);}
    }
    for(int audition:{1,2}) {
        DiffSurround a,b;configure(a,48000);configure(b,48000);a.SetAudition(audition);
        auto x=noise(24000),y=x,source=x;render(a,x);render(b,y);
        for(size_t i=24000;i<x.size();i+=2)if(audition==1){assert(x[i]==source[i] && x[i+1]==source[i+1]);}
        else {assert(x[i]==x[i+1]);assert(x[i]==(y[i]+y[i+1])*.5f);}
        for(unsigned i=7;i<=17;++i)assert(a.GetRuntime(i)==b.GetRuntime(i));
        a.ClearRuntime();a.SetAudition(audition);assert(a.GetRuntime(18)==0);a.SetAudition(0);a.SetAudition(audition);assert(a.GetRuntime(18)==audition);
        a.Reset();a.SetAudition(audition);assert(a.GetRuntime(18)==0);a.SetAudition(0);a.SetAudition(audition);
        a.SetEnable(false);a.SetEnable(true);a.SetAudition(audition);assert(a.GetRuntime(18)==0);
    }
    std::puts("Diff telemetry: full aligned 100 ms Pearson/mono-fold/levels; meters precede bypass/mono listening; stale audition cannot revive after lifecycle cancellation.");
}
void transitions() {
    for(unsigned fs:{8000u,44100u,48000u,192000u}) {
        DiffSurround a,b;configure(a,fs);configure(b,fs);Params p=defaults;
        for(unsigned edit=0;edit<15;++edit) {
            p={1,edit%2?0:2000,int(edit%2),int(edit%3)*500,int(edit%2),edit%2?40:2000,edit%2?2000:20000};
            assert(a.SetAdvanced(p.data(),7) && b.SetAdvanced(p.data(),7));
            auto x=noise(1301),y=x;allocations=0;watchAllocations=true;render(a,x,1);render(b,y,1025);watchAllocations=false;
            assert(allocations==0);assert(x==y);for(float v:x)assert(std::isfinite(v) && std::abs(v)<4);
        }
        auto tail=noise(fs/2);render(a,tail);assert(a.GetRuntime(19)==0);
        p[5]=p[6];assert(!a.SetAdvanced(p.data(),7));assert(!a.SetAdvanced(nullptr,7));assert(!a.SetAdvanced(defaults.data(),6));
    }
    std::puts("Diff transitions: absolute-time rapid edits match with blocks 1 and 1025; no callback allocations; bounds remain finite and invalid packets are atomic.");
}
void auditionTransitions() {
    for(unsigned fs:{8000u,44100u,192000u}) {
        DiffSurround actual,normal;configure(actual,fs);configure(normal,fs);
        const unsigned settle=std::lround(fs*.015)+2;
        for(int mode:{1,2,0,2,1,0}) {
            actual.SetAudition(mode);auto input=noise(settle+37),a=input,b=input;render(actual,a,1);render(normal,b,1025);
            for(unsigned frame=0;frame<input.size()/2;++frame)for(unsigned channel=0;channel<2;++channel) {
                const unsigned i=2*frame+channel;const float mid=(b[2*frame]+b[2*frame+1])*.5f;
                assert(a[i]>=std::min({input[i],b[i],mid})-2.e-7f && a[i]<=std::max({input[i],b[i],mid})+2.e-7f);
                if(frame>=settle)assert(a[i]==(mode==0?b[i]:mode==1?input[i]:mid));
            }
        }
    }
    std::puts("Diff audition transitions: convex bounds during mode changes and exact destination after the bounded 15 ms fade passed at three rates.");
}
void preparedPlans() {
    DiffSurround effect;configure(effect,48000);auto p=defaults;p[4]=1;
    auto wrongRate=DiffSurround::PrepareAdvanced(p.data(),7,192000);
    assert(wrongRate && !effect.ApplyPrepared(wrongRate));assert(effect.GetRuntime(23)==0);
    auto prepared=DiffSurround::PrepareAdvanced(p.data(),7,48000);assert(prepared);
    allocations=0;watchAllocations=true;assert(effect.ApplyPrepared(prepared));watchAllocations=false;assert(allocations==0);
    assert(prepared); // The replaced plan is returned for destruction outside the audio/control lock.
    auto data=noise(12000);render(effect,data);assert(effect.GetRuntime(23)==1 && effect.GetRuntime(19)==0);
    effect.SetDelayTime(5);p[0]=0;prepared=DiffSurround::PrepareAdvanced(p.data(),7,48000);assert(effect.ApplyPrepared(prepared));
    data=noise(12000);const auto original=data;render(effect,data);for(unsigned i=4000;i<data.size()/2;++i){assert(data[2*i]==original[2*i]);assert(data[2*i+1]==original[2*(i-240)+1]);}
    assert(!DiffSurround::PrepareAdvanced(p.data(),6,48000));assert(!DiffSurround::PrepareAdvanced(p.data(),7,7999));
    std::puts("Diff prepared plans: lock-external FFT design, allocation-free pointer commit, stale-rate rejection and preserved legacy delay on mode return passed.");
}
}
int main(){legacy();fractional();firTransfer();metersAndAudition();transitions();auditionTransitions();preparedPlans();std::puts("All differential delay audio tests passed.");}

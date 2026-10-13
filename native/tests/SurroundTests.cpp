// SPDX-License-Identifier: GPL-3.0-or-later
#include "../surround/SurroundProcessor.h"
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
#ifdef NDEBUG
#error Surround audio tests require assertions.
#endif
namespace {
thread_local bool forbidAllocation=false;
}
void* operator new(std::size_t size) {
    assert(!forbidAllocation);
    if(void* result=std::malloc(size?size:1)) return result;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* ptr) noexcept { std::free(ptr); }
void operator delete[](void* ptr) noexcept { std::free(ptr); }
void operator delete(void* ptr,std::size_t) noexcept { std::free(ptr); }
void operator delete[](void* ptr,std::size_t) noexcept { std::free(ptr); }
using dspfusion::SurroundProcessor;
namespace {
constexpr double pi=3.1415926535897932384626433832795;
using Packet=std::array<int32_t,6>;
const Packet defaults{1,1,200,9000,5500,35};
void set(SurroundProcessor& effect,const Packet& p) { assert(effect.setParameters(p.data(),p.size())); }
void process(SurroundProcessor& effect,float* data,size_t frames) {
    forbidAllocation=true;effect.process(data,static_cast<uint32_t>(frames));forbidAllocation=false;
}
void render(SurroundProcessor& effect,std::vector<float>& data,size_t chunk=512) {
    for(size_t frame=0;frame<data.size()/2;frame+=chunk)
        process(effect,data.data()+2*frame,std::min(chunk,data.size()/2-frame));
}
void warm(SurroundProcessor& effect,uint32_t rate) {
    std::vector<float> silence(rate/25*2);render(effect,silence);
}
double maxDifference(const std::vector<float>& a,const std::vector<float>& b) {
    assert(a.size()==b.size());double maximum=0;
    for(size_t i=0;i<a.size();++i) {
        assert(std::isfinite(a[i])&&std::isfinite(b[i]));
        maximum=std::max(maximum,std::abs(double(a[i])-b[i]));
    }
    return maximum;
}
std::vector<float> noise(size_t frames) {
    uint32_t seed=31415926;std::vector<float> result(frames*2);
    for(auto& x:result) {seed=1664525*seed+1013904223;x=float((seed>>8)/16777216.-.5)*.1f;}
    return result;
}
std::complex<double> filterResponse(double corner,uint32_t rate,double hz,bool high) {
    // Independent z-domain Butterworth transfer function, not the processor's
    // TDF-II states or zero-delay algebra. Tests verify magnitude AND phase.
    const double omega=2*pi*corner/rate,alpha=std::sin(omega)/std::sqrt(2.);
    const auto z=std::polar(1.,-2*pi*hz/rate);
    const double b=(high?1+std::cos(omega):1-std::cos(omega))/2;
    return (b+(high?-2:2)*b*z+b*z*z)/
        (1+alpha-2*std::cos(omega)*z+(1-alpha)*z*z);
}
std::complex<double> expected(const Packet& p,uint32_t rate,double hz,bool opposite) {
    const double scale=std::min(1.,rate*.45/p[3]);
    const double delay=rate*p[5]/1000000.;
    const auto z=std::polar(1.,-2*pi*hz/rate);
    const auto d=std::pow(z,std::floor(delay))*((1-(delay-std::floor(delay)))+(delay-std::floor(delay))*z);
    const auto h=std::pow(10.,-p[4]/20000.)*d*
        filterResponse(p[2]*scale,rate,hz,true)*filterResponse(p[3]*scale,rate,hz,false);
    return 1./(1.+(opposite?-h:h));
}
void testTransfer() {
    for(uint32_t rate:{8000u,44100u,48000u,96000u,192000u})
        for(int delay:{20,35,210}) for(bool opposite:{false,true})
            for(double hz:{125.,1000.,3000.}) {
                Packet p=defaults;p[5]=delay;
                SurroundProcessor effect;effect.configure(rate);set(effect,p);
                const size_t frames=rate;
                std::vector<float> signal(frames*2);
                for(size_t i=0;i<frames;++i) {
                    signal[2*i]=float(.01*std::cos(2*pi*hz*i/rate));
                    signal[2*i+1]=signal[2*i]*(opposite?-1:1);
                }
                render(effect,signal,333);
                std::complex<double> measured{};
                const size_t start=frames/2;
                for(size_t i=start;i<frames;++i)
                    measured+=double(signal[2*i])*std::polar(1.,-2*pi*hz*i/rate);
                measured*=2./((frames-start)*.01);
                const auto ideal=expected(p,rate,hz,opposite);
                if(std::abs(measured-ideal)>2e-5) {
                    std::printf("Transfer error rate=%u delay=%d opposite=%d hz=%g error=%g\n",rate,delay,opposite,hz,std::abs(measured-ideal));
                    assert(false);
                }
            }
    std::puts("RACE: measured in-phase/antiphase complex responses match independent feedback oracle at five rates and fractional delays.");
}
void testStreaming() {
    const auto source=noise(16383);
    for(uint32_t rate:{8000u,44100u,48000u,192000u}) {
        SurroundProcessor one;one.configure(rate);set(one,defaults);
        auto expectedSignal=source;render(one,expectedSignal,16383);
        for(size_t chunk:{size_t(1),size_t(17),size_t(512),size_t(960)}) {
            SurroundProcessor effect;effect.configure(rate);set(effect,defaults);
            auto actual=source;render(effect,actual,chunk);
            assert(maxDifference(expectedSignal,actual)==0);
        }
        auto swapped=source;
        for(size_t i=0;i<swapped.size();i+=2) std::swap(swapped[i],swapped[i+1]);
        SurroundProcessor other;other.configure(rate);set(other,defaults);render(other,swapped,17);
        for(size_t i=0;i<swapped.size();i+=2) std::swap(swapped[i],swapped[i+1]);
        assert(maxDifference(expectedSignal,swapped)==0);
    }
    SurroundProcessor stable,duplicate;set(stable,defaults);set(duplicate,defaults);
    auto first=source,second=source;
    for(size_t frame=0;frame<source.size()/2;frame+=512) {
        const size_t count=std::min(size_t(512),source.size()/2-frame);
        set(duplicate,defaults);
        process(stable,first.data()+frame*2,count);process(duplicate,second.data()+frame*2,count);
    }
    assert(maxDifference(first,second)==0);
    std::puts("RACE: exact chunk invariance, stereo symmetry, duplicate-state retention, no audio-thread allocations.");
}
void testValidationAndBypass() {
    SurroundProcessor effect;
    auto source=noise(1024),copy=source;render(effect,copy);
    assert(std::memcmp(source.data(),copy.data(),copy.size()*sizeof(float))==0);
    set(effect,defaults);warm(effect,48000);
    for(size_t field=0;field<defaults.size();++field) {
        auto invalid=defaults;invalid[field]=-1;assert(!effect.setParameters(invalid.data(),invalid.size()));
        invalid=defaults;invalid[field]=std::numeric_limits<int32_t>::max();assert(!effect.setParameters(invalid.data(),invalid.size()));
    }
    assert(!effect.setParameters(nullptr,6));assert(!effect.setParameters(defaults.data(),5));
    auto off=defaults;off[1]=0;set(effect,off);warm(effect,48000);
    copy=source;render(effect,copy);assert(std::memcmp(source.data(),copy.data(),copy.size()*sizeof(float))==0);
    effect.reset();copy=source;render(effect,copy);assert(maxDifference(source,copy)==0);
    std::puts("RACE: invalid protocol/ranges rejected, initial/disabled/reset bypass preserves dry bytes.");
}
void testExtremesAndReset() {
    for(uint32_t rate:{8000u,44100u,48000u,96000u,192000u})
        for(int low:{10,800}) for(int high:{4000,16000})
            for(int attenuation:{500,10000}) for(int delay:{20,210}) {
                const Packet p{1,1,low,high,attenuation,delay};
                SurroundProcessor effect;effect.configure(rate);set(effect,p);warm(effect,rate);
                auto signal=noise(rate/4);render(effect,signal,960);
                for(auto x:signal) assert(std::isfinite(x)&&std::abs(x)<10);
                std::vector<float> tail(rate*2*2,0.f);render(effect,tail,512);
                double late=0;for(size_t i=tail.size()-rate/10;i<tail.size();++i) late=std::max(late,std::abs(double(tail[i])));
                assert(late<1e-5);
                effect.reset();std::array<float,512> silent{};process(effect,silent.data(),256);
                for(float x:silent) assert(x==0);
            }
    SurroundProcessor effect;set(effect,defaults);warm(effect,48000);
    std::array<float,6> invalid{std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN(),-.1f,.1f,0,0};
    process(effect,invalid.data(),3);for(float x:invalid) assert(std::isfinite(x));
    std::puts("RACE: all parameter corners stable, tails decay, reset clears history, malformed upstream floats contained.");
}
void testUpdates() {
    SurroundProcessor effect;set(effect,defaults);
    std::atomic<bool> done=false;
    std::thread control([&] {
        for(int i=0;i<4000;++i) {
            Packet p{1,i%3?1:0,10+i%791,4000+i%12001,500+i%9501,20+i%191};
            set(effect,p);
        }
        auto off=defaults;off[1]=0;set(effect,off);done=true;
    });
    const auto source=noise(128);auto outputSignal=source;
    do {
        outputSignal=source;process(effect,outputSignal.data(),128);
        for(float x:outputSignal) assert(std::isfinite(x)&&std::abs(x)<10);
    } while(!done);
    control.join();warm(effect,48000);warm(effect,48000);
    outputSignal=source;process(effect,outputSignal.data(),128);assert(maxDifference(source,outputSignal)==0);
    std::puts("RACE: concurrent rapid updates remain finite and converge to final disabled state.");
}
}
int main() {
    testValidationAndBypass();testStreaming();testTransfer();testExtremesAndReset();testUpdates();
    std::puts("All surround tests passed.");
}

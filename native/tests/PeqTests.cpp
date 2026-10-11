// SPDX-License-Identifier: GPL-3.0-or-later
#include "../peq/PeqProcessor.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <thread>
#include <vector>
#ifdef NDEBUG
#error PEQ audio tests require assertions.
#endif
using dspfusion::PeqProcessor;
namespace {
constexpr double pi=3.14159265358979323846;
using Band=std::array<int32_t,5>;
std::vector<int32_t> packet(std::initializer_list<Band> bands,bool enabled=true) {
    std::vector<int32_t> p{1,int(enabled),int32_t(bands.size())};
    for(const auto& b:bands) p.insert(p.end(),b.begin(),b.end());
    return p;
}
void set(PeqProcessor& eq,const std::vector<int32_t>& p) { assert(eq.setParameters(p.data(),p.size())); }
std::vector<float> signal(size_t frames,uint32_t rate,double hz=1000) {
    std::vector<float> x(frames*2);
    for(size_t i=0;i<frames;++i) x[2*i]=x[2*i+1]=float(.01*std::sin(2*pi*hz*i/rate));
    return x;
}
void render(PeqProcessor& eq,std::vector<float>& x,size_t chunk=512) {
    for(size_t frame=0;frame<x.size()/2;frame+=chunk)
        eq.process(x.data()+frame*2,uint32_t(std::min(chunk,x.size()/2-frame)));
}
double difference(const std::vector<float>& a,const std::vector<float>& b) {
    assert(a.size()==b.size());double error=0;
    for(size_t i=0;i<a.size();++i) {assert(std::isfinite(a[i])&&std::isfinite(b[i]));error=std::max(error,std::abs(double(a[i])-b[i]));}
    return error;
}
double measuredDb(const std::vector<int32_t>& p,uint32_t rate,double hz) {
    PeqProcessor eq;eq.configure(rate);set(eq,p);
    auto source=signal(rate,rate,hz),processed=source;render(eq,processed,960);
    double before=0,after=0;
    for(size_t i=rate;i<source.size();i+=2) {before+=source[i]*double(source[i]);after+=processed[i]*double(processed[i]);}
    return 10*std::log10(std::max(after,1e-300)/before);
}
void testTransferFunctions() {
    for(uint32_t rate:{44100u,48000u,96000u,192000u}) {
        assert(std::abs(measuredDb(packet({{0,1,1000000,6000,1000}}),rate,1000)-6)<.002);
        assert(std::abs(measuredDb(packet({{0,1,1000000,-12000,30000}}),rate,1000)+12)<.002);
        for(int type:{1,2})
            assert(std::abs(measuredDb(packet({{type,1,1000000,12000,707}}),rate,1000)-6)<.002);
        for(int type:{3,4})
            assert(std::abs(measuredDb(packet({{type,1,1000000,24000,707}}),rate,1000)-20*std::log10(.707))<.002);
        assert(measuredDb(packet({{5,1,1000000,0,1000}}),rate,1000)<-90);
        assert(std::abs(measuredDb(packet({{6,1,1000000,0,1000}}),rate,1000))<.002);
    }
    assert(measuredDb(packet({{3,1,1000000,0,707}}),48000,10000)<-40);
    assert(measuredDb(packet({{4,1,1000000,0,707}}),48000,100)<-39);
    assert(std::abs(measuredDb(packet({{1,1,1000000,6000,707}}),48000,20)-6)<.01);
    assert(std::abs(measuredDb(packet({{2,1,1000000,6000,707}}),48000,18000)-6)<.01);
    // Equal/opposite peak filters must cancel in both magnitude and phase.
    PeqProcessor eq;set(eq,packet({{0,1,731123,12000,1721},{0,1,731123,-12000,1721}}));
    auto source=signal(20000,48000,823),processed=source;render(eq,processed,333);
    assert(difference(source,processed)<1e-7);
    std::puts("PEQ: seven filter transfer functions, sample rates, boost/cut cancellation passed.");
}
void testNeutralValidationAndFullCount() {
    std::vector<int32_t> p{1,1,170};
    for(int i=0;i<170;++i) p.insert(p.end(),{i%3,1,20000+i*100000,0,1000});
    PeqProcessor eq;set(eq,p);auto original=signal(10000,48000,987),out=original;render(eq,out);
    assert(original==out);
    for(size_t index:{0u,1u,2u,3u,4u,5u,6u,7u}) {
        auto invalid=p;invalid[index]=std::numeric_limits<int32_t>::max();
        assert(!eq.setParameters(invalid.data(),invalid.size()));
    }
    assert(!eq.setParameters(p.data(),p.size()-1));assert(!eq.setParameters(nullptr,0));
    assert(!eq.setParameters(p.data(),2));
    auto invalid=packet({{0,1,19999,0,1000}});assert(!eq.setParameters(invalid.data(),invalid.size()));
    invalid=packet({{0,1,1000000,-24001,1000}});assert(!eq.setParameters(invalid.data(),invalid.size()));
    invalid=packet({{0,1,1000000,0,99}});assert(!eq.setParameters(invalid.data(),invalid.size()));
    out=original;render(eq,out);assert(original==out); // Rejected snapshots have no side effects.
    // The 170th section must actually run; the first 169 remain neutral.
    p[3+169*5]=0;p[3+169*5+2]=1000000;p[3+169*5+3]=12000;
    assert(std::abs(measuredDb(p,48000,1000)-12)<.002);
    // A requested frequency at/above Nyquist is bypassed rather than moved.
    PeqProcessor lowRate;lowRate.configure(8000);set(lowRate,packet({{3,1,4000000,0,707},{0,1,20000000,24000,30000}}));
    out=original;render(lowRate,out);assert(out==original);
    std::puts("PEQ: exact neutral bypass, strict packets, 170th band, Nyquist handling passed.");
}
void testContinuityAndSmoothing() {
    const auto p=packet({{0,1,20500,24000,30000},{1,1,173432,-9000,1430},{4,1,20000,0,707}});
    PeqProcessor a,b;set(a,p);set(b,p);
    auto source=signal(65536,48000,53),x=source,y=source;
    render(a,x,512);render(b,y,960);assert(difference(x,y)<1e-7);
    // No stereo cross-talk, including during bank transitions.
    for(size_t i=1;i<source.size();i+=2) source[i]=0;
    PeqProcessor stereo;set(stereo,p);render(stereo,source,71);
    for(size_t i=1;i<source.size();i+=2) assert(source[i]==0.f);
    PeqProcessor fade;std::vector<float> dc(4800,.05f);
    set(fade,packet({{1,1,1000000,12000,707}}));render(fade,dc);
    assert(dc[0]==.05f);
    for(size_t i=2;i<dc.size();i+=2) assert(std::abs(dc[i]-dc[i-2])<.005);
    const float boosted=dc.back();assert(std::abs(boosted-.05*std::pow(10.,12./20))<1e-5);
    set(fade,packet({},false));std::fill(dc.begin(),dc.end(),.05f);render(fade,dc);
    assert(std::abs(dc[0]-boosted)<1e-5);assert(dc.back()==.05f);
    for(size_t i=2;i<dc.size();i+=2) assert(std::abs(dc[i]-dc[i-2])<.005);
    // Keep the newest complete edit during an ongoing transition.
    for(int gain=0;gain<=12000;gain+=1000) {
        set(fade,packet({{1,1,1000000,gain,707}}));
        std::vector<float> small(40,.05f);render(fade,small);
    }
    std::fill(dc.begin(),dc.end(),.05f);render(fade,dc);
    assert(std::abs(dc.back()-boosted)<1e-5);
    // Reconfiguration rebuilds filters for the new rate, with bounded output.
    fade.configure(96000);std::fill(dc.begin(),dc.end(),.05f);render(fade,dc);
    for(float s:dc) assert(std::isfinite(s)&&std::abs(s)<1);
    fade.reset();std::fill(dc.begin(),dc.end(),.05f);render(fade,dc);
    assert(dc[0]==.05f);assert(std::abs(dc.back()-boosted)<1e-5);
    std::puts("PEQ: variable callback sizes, stereo isolation and smooth updates passed.");
}
void testFullCountExtremes() {
    for(uint32_t rate:{8000u,44100u,48000u,96000u,192000u}) {
        PeqProcessor eq;eq.configure(rate);
        for(int type=0;type<=6;++type) for(int side:{0,1}) {
            std::vector<int32_t> p{1,1,170};
            for(int band=0;band<170;++band)
                p.insert(p.end(),{type,1,band%2?20000000:20000,side?24000:-24000,side?30000:100});
            set(eq,p);eq.reset();auto x=signal(4096,rate,73);render(eq,x,37);
            for(float s:x) assert(std::isfinite(s)&&std::abs(s)<=1e6f);
        }
        set(eq,packet({},false));eq.reset();auto x=signal(4096,rate),dry=x;render(eq,x);
        assert(x==dry);
    }
    std::puts("PEQ: all 7 types at 170 bands, boundary gains/Q/frequencies, 8–192 kHz and reset recovery passed.");
}
void testFiniteAndConcurrentUpdates() {
    PeqProcessor eq;set(eq,packet({{0,1,1000000,24000,30000}}));
    auto x=signal(10000,48000);x[4]=std::numeric_limits<float>::infinity();x[9]=std::numeric_limits<float>::quiet_NaN();
    render(eq,x);for(float s:x) assert(std::isfinite(s));
    std::vector<int32_t> extreme{1,1,170};
    for(int i=0;i<170;++i) extreme.insert(extreme.end(),{1,1,1000000,24000,30000});
    set(eq,extreme);x=signal(4096,48000);render(eq,x);
    for(float s:x) assert(std::isfinite(s)&&std::abs(s)<=1e6f);
    PeqProcessor concurrent;const auto p=packet({{1,1,1000000,12000,707}});set(concurrent,p);
    std::vector<float> warm(8000,.05f);render(concurrent,warm);
    const float expected=warm.back();std::atomic<bool> done=false;
    std::thread writer([&] {for(int i=0;i<2000;++i) set(concurrent,p);done.store(true);});
    do {
        std::array<float,1024> block;block.fill(.05f);concurrent.process(block.data(),512);
        for(float s:block) assert(std::abs(s-expected)<1e-6);
    } while(!done.load());
    writer.join();
    std::puts("PEQ: nonfinite input, pathological 170-band gains and concurrent publication passed.");
}
void benchmark() {
    constexpr uint32_t rate=48000,frames=rate*5;
    std::vector<int32_t> p{1,1,170};
    for(int i=0;i<170;++i) p.insert(p.end(),{0,1,int32_t(20000*std::pow(1000.,i/169.)),(i%2?300:-300),1000});
    PeqProcessor eq;set(eq,p);
    auto warm=signal(4096,rate);render(eq,warm);
    auto samples=signal(frames,rate,827);
    const auto start=std::chrono::steady_clock::now();render(eq,samples,512);
    const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
    double energy=0;for(float s:samples) {assert(std::isfinite(s));energy+=s*double(s);}
    assert(energy>1e-4);
    std::printf("PEQ benchmark: 170 enabled nonzero bands, stereo 48 kHz, 5.000 s audio in %.6f s (%.2fx realtime; host only).\n",seconds,5./seconds);
}
}
int main() {
    std::setvbuf(stdout,nullptr,_IONBF,0);
    testTransferFunctions();testNeutralValidationAndFullCount();testContinuityAndSmoothing();testFiniteAndConcurrentUpdates();testFullCountExtremes();benchmark();
    std::puts("PEQ audio tests passed.");
}

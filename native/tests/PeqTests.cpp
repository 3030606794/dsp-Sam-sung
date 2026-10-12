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
namespace dspfusion {
// Inspect retention only: output correctness is checked independently with
// measured audio and the long-double direct-form RBJ reference below.
struct PeqTestAccess {
    static size_t preparedCount(const PeqProcessor& eq) { return eq.pending_.count; }
};
}
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
double measuredDb(const std::vector<int32_t>& p,uint32_t rate,double hz,unsigned seconds=1) {
    PeqProcessor eq;eq.configure(rate);set(eq,p);
    auto source=signal(rate*seconds,rate,hz),processed=source;render(eq,processed,960);
    double before=0,after=0;
    for(size_t i=source.size()-rate;i<source.size();i+=2) {before+=source[i]*double(source[i]);after+=processed[i]*double(processed[i]);}
    return 10*std::log10(std::max(after,1e-300)/before);
}
void testTransferFunctions() {
    for(uint32_t rate:{44100u,48000u,96000u,192000u}) {
        assert(std::abs(measuredDb(packet({{0,1,1000000,6000,1000}}),rate,1000)-6)<.002);
        assert(std::abs(measuredDb(packet({{0,1,1000000,-12000,30000}}),rate,1000)+12)<.002);
        for(int type:{1,2})
            assert(std::abs(measuredDb(packet({{type,1,1000000,12000,707}}),rate,1000)-6)<.002);
        for(int type:{3,4})
            assert(std::abs(measuredDb(packet({{type,1,1000000,24000,707}}),rate,1000)-24-20*std::log10(.707))<.002);
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
void testQualityBandwidthDirection() {
    // Measure real audio on both sides of the center. Larger Q must move a
    // peak/notch's off-center response toward 0 dB and attenuate a band-pass
    // more: the affected frequency region becomes narrower in all three cases.
    for(uint32_t rate:{44100u,48000u,96000u}) for(int center:{1000,10000,20000})
        for(double probe:{center/1.25,center*1.1}) for(int type:{0,5,6})
            for(int gain:{-12000,12000}) {
                double previous=type==6?0:std::numeric_limits<double>::infinity();
                for(int q:{100,250,500,1000,5000,20000,100000}) {
                    const double db=measuredDb(packet({{type,1,center*1000,gain,q}}),rate,probe);
                    const double depth=std::abs(db-(type>=3?gain/1000.:0.));
                    assert(std::isfinite(db));
                    assert(type==6?depth>previous:depth<previous);
                    previous=depth;
                }
            }
    // BW is an octave value, not Q or 1/Q. Exercise independently converted,
    // quantized wire values across the full valid Q range at low/high centers.
    // Increasing BW reverses the Q direction and therefore widens the response.
    for(int center:{1000,10000,20000}) for(int type:{0,5,6}) {
        constexpr uint32_t rate=48000;
        const double omega=2*pi*center/rate;
        const double scale=2/std::log(2.)*std::sin(omega)/omega;
        const double minimum=scale*std::asinh(1./200.);
        const double maximum=scale*std::asinh(5.);
        double previous=type==6?std::numeric_limits<double>::infinity():0;
        int previousQ=100001;
        for(int step=0;step<=8;++step) {
            const double bw=minimum*std::pow(maximum/minimum,step/8.);
            const double q=1/(2*std::sinh(bw/scale));
            const int wireQ=int(std::lround(q*1000));
            assert(wireQ>=100&&wireQ<=100000&&wireQ<previousQ);
            const double db=measuredDb(packet({{type,1,center*1000,12000,wireQ}}),rate,center*1.1);
            const double depth=std::abs(db-(type>=3?12.:0.));
            assert(std::isfinite(db));
            assert(type==6?depth<previous:depth>previous);
            previous=depth;previousQ=wireQ;
        }
    }
    std::puts("PEQ: measured audio confirms increasing Q narrows and increasing octave BW widens peak/notch/band-pass responses.");
}
// A separate direct-form implementation is deliberately retained in tests: it
// catches transfer-function mistakes in the state-variable production engine.
std::vector<float> rbjReference(const Band& b,uint32_t rate,const std::vector<float>& source) {
    const long double w=2*std::acos(-1.L)*(b[2]/1000.L)/rate,c=std::cos(w);
    const long double alpha=std::sin(w)/(2*(b[4]/1000.L)),a=std::pow(10.L,b[3]/40000.L);
    long double b0=1,b1=-2*c,b2=1,a0=1+alpha,a1=-2*c,a2=1-alpha;
    switch(b[0]) {
        case 0:b0=1+alpha*a;b2=1-alpha*a;a0=1+alpha/a;a2=1-alpha/a;break;
        case 1: {
            const auto t=2*std::sqrt(a)*alpha;
            b0=a*((a+1)-(a-1)*c+t);b1=2*a*((a-1)-(a+1)*c);b2=a*((a+1)-(a-1)*c-t);
            a0=(a+1)+(a-1)*c+t;a1=-2*((a-1)+(a+1)*c);a2=(a+1)+(a-1)*c-t;break;
        }
        case 2: {
            const auto t=2*std::sqrt(a)*alpha;
            b0=a*((a+1)+(a-1)*c+t);b1=-2*a*((a-1)+(a+1)*c);b2=a*((a+1)+(a-1)*c-t);
            a0=(a+1)-(a-1)*c+t;a1=2*((a-1)-(a+1)*c);a2=(a+1)-(a-1)*c-t;break;
        }
        case 3:b0=b2=(1-c)/2;b1=1-c;break;
        case 4:b0=b2=(1+c)/2;b1=-1-c;break;
        case 5:break;
        case 6:b0=alpha;b1=0;b2=-alpha;break;
    }
    if(b[0]>=3) {
        const long double gain=std::pow(10.L,b[3]/20000.L);
        b0*=gain;b1*=gain;b2*=gain;
    }
    std::vector<float> out(source.size());
    long double x1[2]{},x2[2]{},y1[2]{},y2[2]{};
    for(size_t frame=0;frame<source.size()/2;++frame) for(size_t channel=0;channel<2;++channel) {
        const long double x=source[2*frame+channel];
        const auto y=(b0*x+b1*x1[channel]+b2*x2[channel]-a1*y1[channel]-a2*y2[channel])/a0;
        x2[channel]=x1[channel];x1[channel]=x;y2[channel]=y1[channel];y1[channel]=y;
        out[2*frame+channel]=float(y);
    }
    return out;
}
void testPassNotchOutputGain() {
    // Compare complete stereo waveforms against the independently rendered
    // zero-gain filter: gain changes amplitude only, including high-Q ringing.
    for(uint32_t rate:{44100u,48000u,96000u,192000u}) for(int type:{3,4,5,6})
        for(int frequency:{20000,731123,20000000}) for(int q:{100,100000}) {
            auto source=signal(12000,rate,731);
            source[0]+=.03125f;source[1]-=.025f;
            PeqProcessor neutral;neutral.configure(rate);set(neutral,packet({{type,1,frequency,0,q}}));
            assert(dspfusion::PeqTestAccess::preparedCount(neutral)==1);
            auto reference=source;render(neutral,reference,333);
            for(int gain:{-100000,-12000,12000,100000}) {
                PeqProcessor changed;changed.configure(rate);set(changed,packet({{type,1,frequency,gain,q}}));
                assert(dspfusion::PeqTestAccess::preparedCount(changed)==1);
                auto processed=source;render(changed,processed,333);
                const double scale=std::pow(10.,gain/20000.);
                double error=0,energy=0;
                for(size_t i=2*(rate/50+1);i<processed.size();++i) {
                    const double expected=reference[i]*scale;
                    assert(std::isfinite(processed[i]));
                    error+=std::pow(processed[i]-expected,2);energy+=expected*expected;
                }
                assert(energy>1e-30);
                assert(std::sqrt(error/energy)<2e-6);
            }
        }
    // Explicit measured stop-band checks catch an accidental gain=0 bypass.
    assert(measuredDb(packet({{3,1,1000000,0,707}}),48000,10000)<-40);
    assert(measuredDb(packet({{4,1,1000000,0,707}}),48000,100)<-39);
    assert(measuredDb(packet({{5,1,1000000,0,707}}),48000,1000)<-90);
    assert(measuredDb(packet({{6,1,1000000,0,707}}),48000,100)<-15);
    for(int type:{3,4,5,6}) for(double probe:{500.,1500.,5000.}) {
        const double baseline=measuredDb(packet({{type,1,1000000,0,1000}}),48000,probe);
        for(int gain:{-100000,-12000,12000,100000}) {
            const double shifted=measuredDb(packet({{type,1,1000000,gain,1000}}),48000,probe);
            assert(std::abs(shifted-baseline-gain/1000.)<.002);
        }
    }
    std::puts("PEQ: low/high-pass, notch and band-pass preserve shape with measured +/-12/100 dB output gain; zero-gain filtering and Q0.1/Q100 stereo waveforms passed.");
}
void testExtendedRangeTransfer() {
    for(int gain:{-100000,100000}) {
        // +100 dB/Q100 peak poles settle slowly. 25 seconds is intentional;
        // measuring a short transient would understate the supported gain.
        const auto measured=measuredDb(packet({{0,1,10000000,gain,100000}}),48000,10000,25);
        assert(std::abs(measured-gain/1000.)<.01);
        std::printf("PEQ measured center gain: requested %+.1f dB/Q100, actual %+.6f dB (10 kHz, 48 kHz sample rate).\n",gain/1000.,measured);
        for(int type:{1,2})
            assert(std::abs(measuredDb(packet({{type,1,10000000,gain,100000}}),48000,10000,3)-gain/2000.)<.01);
        for(int type:{3,4})
            assert(std::abs(measuredDb(packet({{type,1,10000000,gain,100000}}),48000,10000)-40-gain/1000.)<.002);
        assert(measuredDb(packet({{5,1,10000000,gain,100000}}),48000,10000)<gain/1000.-90);
        assert(std::abs(measuredDb(packet({{6,1,10000000,gain,100000}}),48000,10000)-gain/1000.)<.002);
    }
    for(uint32_t rate:{44100u,48000u,96000u,192000u}) for(int type=0;type<=6;++type)
        for(int gain:{-100000,100000}) for(int frequency:{20000,731123,20000000}) {
            const Band band{type,1,frequency,gain,100000};
            PeqProcessor eq;eq.configure(rate);set(eq,packet({band}));
            auto source=signal(12000,rate,731);
            source[0]+=.03125f;source[1]-=.025f;
            for(auto& s:source) s*=1e-6f;
            auto out=source;render(eq,out,333);const auto reference=rbjReference(band,rate,source);
            double delta=0,energy=0;
            for(size_t i=2*(rate/50+1);i<out.size();++i) {
                assert(std::isfinite(out[i]));
                delta+=std::pow(double(out[i])-reference[i],2);energy+=std::pow(double(reference[i]),2);
            }
            assert(std::sqrt(delta/std::max(energy,1e-300))<2e-5);
        }
    // Millihertz below Nyquist used to make normalized shelf Schur tests round
    // to zero and silently drop a legal section. All 170 now remain present.
    for(uint32_t rate:{8000u,40000u,44100u,48000u,96000u,192000u})
        for(int type=0;type<=6;++type) for(int gain:{-100000,100000}) {
            const int frequency=std::min(20000000,int(rate*500)-1);
            std::vector<int32_t> p{1,1,170};
            for(int i=0;i<170;++i) p.insert(p.end(),{type,1,frequency,gain,100000});
            PeqProcessor eq;eq.configure(rate);set(eq,p);
            assert(dspfusion::PeqTestAccess::preparedCount(eq)==170);
            auto x=signal(4096,rate,rate*.49);render(eq,x,127);
            for(float s:x) assert(std::isfinite(s)&&std::abs(s)<=1e6f);
        }
    std::puts("PEQ: measured +/-100 dB/Q100, seven-type long-double RBJ reference and near-Nyquist retention passed.");
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
    invalid=packet({{0,1,1000000,-100001,1000}});assert(!eq.setParameters(invalid.data(),invalid.size()));
    invalid=packet({{0,1,1000000,100001,1000}});assert(!eq.setParameters(invalid.data(),invalid.size()));
    invalid=packet({{0,1,1000000,0,100001}});assert(!eq.setParameters(invalid.data(),invalid.size()));
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
    const auto p=packet({{0,1,20500,100000,100000},{1,1,173432,-9000,1430},{4,1,20000,0,707}});
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
void testExtremeTransitions() {
    PeqProcessor changing,unchanged;
    const auto initial=packet({{0,1,10000000,100000,100000},{2,1,20123,-100000,100000}});
    set(changing,initial);set(unchanged,initial);
    auto x=signal(32000,48000,10000),reference=x;render(changing,x);render(unchanged,reference);
    set(changing,packet({{1,1,20000000,-100000,100000}}));
    float first[2]{.003f,-.001f},expected[2]{.003f,-.001f};
    changing.process(first,1);unchanged.process(expected,1);
    assert(first[0]==expected[0]&&first[1]==expected[1]); // New fade starts exactly at the old bank.
    for(uint32_t rate:{8000u,40000u,192000u,48000u}) {
        changing.configure(rate);
        for(int edit=0;edit<80;++edit) {
            set(changing,packet({{edit%7,1,edit%2?20000:std::min(20000000,int(rate*500)-1),
                                edit%2?100000:-100000,edit%3?100000:100}}));
            auto block=signal(64,rate,73);render(changing,block);
            for(float s:block) assert(std::isfinite(s)&&std::abs(s)<=1e6f);
        }
        // The final neutral snapshot must supersede queued extreme updates.
        set(changing,packet({},false));auto dry=signal(rate/10,rate,73),processed=dry;
        render(changing,processed,128);
        for(size_t i=processed.size()/2;i<processed.size();++i) assert(processed[i]==dry[i]);
        changing.reset();processed=dry;render(changing,processed);assert(processed==dry);
    }
    std::puts("PEQ: +/-100 dB/Q100 edits, exact first fade sample, rate changes and neutral/reset recovery passed.");
}
void testFullCountExtremes() {
    for(uint32_t rate:{8000u,40000u,44100u,48000u,96000u,192000u}) {
        PeqProcessor eq;eq.configure(rate);
        for(int type=0;type<=6;++type) for(int gain:{-100000,100000}) for(int q:{100,100000}) {
            std::vector<int32_t> p{1,1,170};
            for(int band=0;band<170;++band)
                p.insert(p.end(),{type,1,band%2?std::min(20000000,int(rate*500)-1):20000,gain,q});
            set(eq,p);eq.reset();auto x=signal(4096,rate,73);render(eq,x,37);
            assert(dspfusion::PeqTestAccess::preparedCount(eq)==170);
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
    for(int i=0;i<170;++i) extreme.insert(extreme.end(),{1,1,1000000,100000,100000});
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
    for(int i=0;i<170;++i) {p[3+i*5+3]=100000;p[3+i*5+4]=100000;}
    set(eq,p);render(eq,warm);samples=signal(frames,rate,827);
    const auto extremeStart=std::chrono::steady_clock::now();render(eq,samples,512);
    const double extremeSeconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-extremeStart).count();
    for(float s:samples) assert(std::isfinite(s)&&std::abs(s)<=1e6f);
    assert(dspfusion::PeqTestAccess::preparedCount(eq)==170);
    std::printf("PEQ extreme benchmark: 170 enabled +100 dB/Q100 bands, stereo 48 kHz, 5.000 s audio in %.6f s (%.2fx realtime; host only; numerical saturation expected).\n",extremeSeconds,5./extremeSeconds);
}
}
int main() {
    std::setvbuf(stdout,nullptr,_IONBF,0);
    testTransferFunctions();testQualityBandwidthDirection();testPassNotchOutputGain();testExtendedRangeTransfer();testNeutralValidationAndFullCount();testContinuityAndSmoothing();testExtremeTransitions();testFiniteAndConcurrentUpdates();testFullCountExtremes();benchmark();
    std::puts("PEQ audio tests passed.");
}

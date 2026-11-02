#include "../fusion/OutputSafetyLimiter.h"
#include <cassert>
#include <cstdio>
#include <limits>
#include <vector>

constexpr double pi=3.14159265358979323846;
using dspfusion::OutputSafetyLimiter;
int main() {
    for (unsigned rate : {44100u,48000u,96000u,192000u,384000u,768000u}) {
        OutputSafetyLimiter limiter;limiter.configure(rate);
        for (unsigned i=0;i<rate/10;++i) {
            const float original=.8f*std::sin(2*pi*55*i/rate);
            float l=original,r=original*.4f;limiter.process(l,r);
            assert(l==original && r==original*.4f);
        }
        // A sustained overloaded sine must retain its shape, not become a
        // clipped square wave. Linked gain must preserve the stereo ratio.
        double fundamental=0,third=0,peak=0;
        for (unsigned i=0;i<rate;++i) {
            float l=2.5f*std::sin(2*pi*1000*i/rate),r=l*.4f;
            limiter.process(l,r);assert(std::isfinite(l)&&std::isfinite(r));
            assert(std::abs(l)<=1 && std::abs(r-l*.4f)<1e-6f);
            if(i>=rate/2){fundamental+=l*std::sin(2*pi*1000*i/rate);third+=l*std::sin(2*pi*3000*i/rate);peak=std::max(peak,std::abs(double(l)));}
        }
        assert(peak>.98 && peak<1 && std::abs(third/fundamental)<.0005);
        limiter.reset();float l=std::numeric_limits<float>::max(),r=l*.5f;
        limiter.process(l,r);assert(std::isfinite(l)&&std::isfinite(r)&&l<=1&&r<=.501f);
        l=NAN;r=INFINITY;limiter.process(l,r);assert(l==0&&r==0);
        limiter.reset();l=.5;r=-.25;limiter.process(l,r);assert(l==.5&&r==-.25);
        std::printf("Output safety %u Hz: unity exact, linked peak %.6f, third/fundamental %.9f, finite extremes, reset passed.\n",rate,peak,std::abs(third/fundamental));
    }
    // Release is independent of Fs (including an in-flight Fs switch).
    double gains[2]{};
    for(unsigned j=0;j<2;++j){OutputSafetyLimiter l;l.configure(48000);float a=4,b=2;l.process(a,b);const unsigned rate=j?192000:48000;l.configure(rate);for(unsigned i=0;i<rate/5;++i){a=.5;b=.25;l.process(a,b);}gains[j]=a/.5;}
    assert(std::abs(gains[0]-gains[1])<.0002);
    std::puts("Output safety release in seconds across 48/192 kHz passed.");
}

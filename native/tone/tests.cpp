// SPDX-License-Identifier: GPL-3.0-or-later
#include "ToneProcessor.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

using dspfusion::ToneProcessor;
using Params=std::array<int32_t,11>;
constexpr double pi=3.14159265358979323846;
void check(bool result,const char* message) { if(!result) throw std::runtime_error(message); }
std::vector<float> signal(uint32_t rate,uint32_t frames) {
    std::vector<float> result(2*frames);
    uint32_t random=17;
    for(uint32_t i=0;i<frames;++i) {
        random=1664525*random+1013904223;
        result[2*i]=float(0.2*std::sin(2*pi*71*i/rate)+0.06*std::sin(2*pi*3100*i/rate));
        result[2*i+1]=float(int32_t(random)/2147483648.0*0.1);
    }
    return result;
}
void prepare(ToneProcessor& p,uint32_t rate,const Params& v) {
    p.configure(rate); check(p.setParameters(v.data(),v.size()),"valid parameters rejected"); p.reset();
}
double response(uint32_t rate,const Params& v,double hz) {
    ToneProcessor p; prepare(p,rate,v);
    std::vector<float> audio(rate*2);
    for(uint32_t i=0;i<rate;++i) audio[2*i]=audio[2*i+1]=float(0.1*std::sin(2*pi*hz*i/rate));
    p.process(audio.data(),rate);
    double energy=0;
    for(uint32_t i=rate/2;i<rate;++i) energy+=double(audio[2*i])*audio[2*i];
    return std::sqrt(energy/(rate/2));
}
double burstRatio(uint32_t rate,int32_t transient) {
    Params v{}; v[0]=1; v[9]=transient;
    ToneProcessor p; prepare(p,rate,v);
    std::vector<float> audio(rate*2,0);
    for(uint32_t i=rate/5;i<rate;++i) audio[2*i]=audio[2*i+1]=float(0.1*std::sin(2*pi*800*i/rate));
    p.process(audio.data(),rate);
    double attack=0,sustain=0;
    const auto size=rate/50;
    for(uint32_t i=0;i<size;++i) {
        attack+=double(audio[2*(rate/5+i)])*audio[2*(rate/5+i)];
        sustain+=double(audio[2*(rate*4/5+i)])*audio[2*(rate*4/5+i)];
    }
    return std::sqrt(attack/sustain);
}
int main() {
    try {
        for(uint32_t rate:{44100u,48000u,96000u}) {
            auto source=signal(rate,rate/2);
            Params neutral{}; neutral[0]=1;
            ToneProcessor p; prepare(p,rate,neutral);
            auto actual=source; p.process(actual.data(),rate/2);
            check(std::memcmp(actual.data(),source.data(),source.size()*sizeof(float))==0,"zero controls must be bit-exact");
            Params bypass{}; bypass.fill(100); bypass[0]=0;
            prepare(p,rate,bypass); p.process(actual.data(),rate/2);
            check(actual==source,"disabled processing must be bit-exact");

            for(int knob=1;knob<=10;++knob) {
                Params controls{}; controls[0]=1; controls[knob]=100;
                prepare(p,rate,controls); actual=source; p.process(actual.data(),rate/2);
                double difference=0;
                for(size_t i=0;i<actual.size();++i) {
                    check(std::isfinite(actual[i]),"nonfinite samples");
                    difference+=std::abs(actual[i]-source[i]);
                }
                check(difference>0.01,"every control must actually process sound");
            }
            Params a{}; a[0]=1;
            for(int i=1;i<=10;++i) a[i]=(i%2 ? 100 : -100);
            ToneProcessor whole,split; prepare(whole,rate,a); prepare(split,rate,a);
            auto one=source,two=source;
            whole.process(one.data(),rate/2);
            uint32_t done=0;
            while(done<rate/2) { const auto n=std::min(1u+(done%127u),rate/2-done); split.process(two.data()+2*done,n); done+=n; }
            check(one==two,"processing must not depend on block size");
            Params oversized=a;
            for(int i=1;i<=10;++i) oversized[i]*=10000;
            prepare(p,rate,oversized); actual=source; p.process(actual.data(),rate/2);
            check(actual==one,"out-of-range values must clamp");
            check(!p.setParameters(nullptr,11),"null payload must be rejected");
            check(!p.setParameters(a.data(),10),"short payload must be rejected");
            check(!p.setParameters(a.data(),12),"long payload must be rejected");

            // Relative response cancels intentional automatic headroom/loudness changes.
            for(const auto& target:std::array<std::array<double,3>,5>{{{{2,40,1000}},{{4,350,1500}},{{5,1800,150}},{{7,5500,1500}},{{10,16000,1000}}}}) {
                Params minus{},plus{}; minus[0]=plus[0]=1;
                minus[size_t(target[0])]=-100; plus[size_t(target[0])]=100;
                const double down=response(rate,minus,target[1])/response(rate,minus,target[2]);
                const double up=response(rate,plus,target[1])/response(rate,plus,target[2]);
                check(up>down*1.15,"tone direction is reversed or ineffective");
            }
            check(burstRatio(rate,100)>burstRatio(rate,-100)*1.1,"transient control must change attack relative to sustain");

            // Rapid parameter changes, impulses, silence and invalid source floats remain bounded.
            prepare(p,rate,a);
            std::array<float,128> buffer{};
            for(unsigned iteration=0;iteration<300;++iteration) {
                for(int i=1;i<=10;++i) a[i]=((iteration+i)%3==0 ? 100 : -100);
                check(p.setParameters(a.data(),a.size()),"parameter update failed");
                buffer.fill(0); buffer[0]=iteration%2 ? 1.0f : -1.0f;
                if(iteration==7) buffer[31]=std::numeric_limits<float>::quiet_NaN();
                if(iteration==8) buffer[31]=std::numeric_limits<float>::infinity();
                p.process(buffer.data(),64);
                for(float x:buffer) check(std::isfinite(x) && std::abs(x)<=64,"unstable realtime parameter update");
            }
            Params disabled{}; p.setParameters(disabled.data(),disabled.size());
            actual=source; p.process(actual.data(),rate/2);
            actual=source; p.process(actual.data(),rate/2);
            check(actual==source,"bypass must settle to exact dry input");
        }
        std::cout<<"ToneProcessor: neutral, bypass, 10 controls, response direction, transient dynamics, bounds, finite output, smoothing and block invariance passed at 44.1/48/96 kHz.\n";
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
    return 0;
}

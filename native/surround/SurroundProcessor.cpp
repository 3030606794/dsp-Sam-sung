// SPDX-License-Identifier: GPL-3.0-or-later
#include "SurroundProcessor.h"
#include <algorithm>
#include <cmath>

namespace dspfusion {
namespace {
constexpr double pi=3.1415926535897932384626433832795;
// Circuit breakers for malformed upstream floats, not an audible limiter.
double finiteState(double value) {
    if(!std::isfinite(value)) return 0.;
    if(std::abs(value)<1e-30) return 0.;
    return std::clamp(value,-1e12,1e12);
}
double input(float value) { return std::isfinite(value)?double(value):0.; }
float output(double value) { return std::isfinite(value)?float(std::clamp(value,-1e6,1e6)):0.f; }
}

SurroundProcessor::Prepared SurroundProcessor::prepare(const Settings& settings) const {
    Prepared result;
    if(!settings.enabled) return result;
    result.enabled=true;
    // Keep both corner frequencies in the representable band at low host rates,
    // preserving their ratio instead of allowing inverted/equal corners.
    const double scale=std::min(1.,sampleRate_*.45/settings.highHz);
    const auto design=[&](double hz,bool highPass) {
        const double omega=2*pi*hz/sampleRate_,cs=std::cos(omega);
        const double alpha=std::sin(omega)/std::sqrt(2.);
        const double inv=1/(1+alpha);
        const double b=(highPass?(1+cs):(1-cs))*.5*inv;
        return Coeff{b,(highPass?-2:2)*b,b,-2*cs*inv,(1-alpha)*inv};
    };
    result.highPass=design(settings.lowHz*scale,true);
    result.lowPass=design(settings.highHz*scale,false);
    result.gain=std::pow(10.,-settings.attenuation/20000.);
    const double delay=sampleRate_*settings.delayUs/1000000.;
    result.delay=static_cast<size_t>(std::floor(delay));
    result.fraction=delay-result.delay;
    return result;
}

bool SurroundProcessor::setParameters(const int32_t* words,size_t count) {
    if(!words || count!=kWords || words[0]!=1 || (words[1]!=0&&words[1]!=1) ||
       words[2]<10 || words[2]>800 || words[3]<4000 || words[3]>16000 ||
       words[4]<500 || words[4]>10000 || words[5]<20 || words[5]>210) return false;
    Settings next{words[1]!=0,words[2],words[3],words[4],words[5]};
    std::lock_guard<std::mutex> lock(updateMutex_);
    settings_=next;pending_=prepare(next);++revision_;
    return true;
}

void SurroundProcessor::configure(uint32_t sampleRate) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    if(sampleRate<8000 || sampleRate>192000 || sampleRate==sampleRate_) return;
    sampleRate_=sampleRate;fadeFrames_=std::max(1u,sampleRate/50);
    pending_=prepare(settings_);++revision_;
    for(auto& bank:banks_) {bank.filters={};bank.clear();}
    active_=0;transitioning_=false;fadePosition_=0;
}

void SurroundProcessor::reset() {
    std::lock_guard<std::mutex> lock(updateMutex_);
    for(auto& bank:banks_) {bank.filters={};bank.clear();}
    active_=0;transitioning_=false;fadePosition_=0;++revision_;
}

void SurroundProcessor::adoptPending() {
    if(transitioning_) return;
    std::unique_lock<std::mutex> lock(updateMutex_,std::try_to_lock);
    if(!lock.owns_lock() || revision_==acceptedRevision_) return;
    acceptedRevision_=revision_;
    if(banks_[active_].filters==pending_) return;
    auto& target=banks_[1-active_];target.filters=pending_;target.clear();
    fadePosition_=0;transitioning_=true;
}

void SurroundProcessor::Bank::clear() {
    states={};delayLine={};position=0;
}

double SurroundProcessor::Bank::previous(size_t channel,size_t delay) const {
    return delayLine[channel][(position+kRingSize-delay)%kRingSize];
}

double SurroundProcessor::Bank::offset(size_t channel) const {
    // Cascade output = highPass.b0*lowPass.b0*input + this state offset.
    return filters.lowPass.b0*states[channel][0].s1+states[channel][1].s1;
}

double SurroundProcessor::Bank::filter(size_t channel,double sample) {
    const std::array<Coeff,2> coefficients{filters.highPass,filters.lowPass};
    for(size_t i=0;i<2;++i) {
        const auto& c=coefficients[i];auto& state=states[channel][i];
        const double value=finiteState(c.b0*sample+state.s1);
        state.s1=finiteState(c.b1*sample-c.a1*value+state.s2);
        state.s2=finiteState(c.b2*sample-c.a2*value);
        sample=value;
    }
    return sample;
}

void SurroundProcessor::Bank::tick(double& left,double& right) {
    if(!filters.enabled) return;
    const auto delayedOffset=[&](size_t channel) {
        return (1-filters.fraction)*(filters.delay?previous(channel,filters.delay):offset(channel))+
               filters.fraction*previous(channel,filters.delay+1);
    };
    const double l=left-filters.gain*delayedOffset(1);
    const double r=right-filters.gain*delayedOffset(0);
    // A sub-sample delay contains today's filtered output. Solve both channels
    // together, rather than rounding 20 us to zero or adding a hidden sample.
    const double cross=filters.delay?0:filters.gain*(1-filters.fraction)*
        filters.highPass.b0*filters.lowPass.b0;
    const double denominator=1-cross*cross;
    left=finiteState((l-cross*r)/denominator);
    right=finiteState((r-cross*l)/denominator);
    delayLine[0][position]=filter(0,left);
    delayLine[1][position]=filter(1,right);
    position=(position+1)%kRingSize;
}

void SurroundProcessor::process(float* samples,uint32_t frames) {
    if(!samples || !frames) return;
    adoptPending();
    if(!transitioning_ && !banks_[active_].filters.enabled) return; // Exact dry bytes.
    for(uint32_t frame=0;frame<frames;++frame) {
        if(!transitioning_ && !banks_[active_].filters.enabled) return;
        const double dryL=input(samples[2*frame]),dryR=input(samples[2*frame+1]);
        double left=dryL,right=dryR;
        banks_[active_].tick(left,right);
        if(transitioning_) {
            double nextL=dryL,nextR=dryR;banks_[1-active_].tick(nextL,nextR);
            const double mix=double(fadePosition_+1)/fadeFrames_;
            left+=(nextL-left)*mix;right+=(nextR-right)*mix;
            if(++fadePosition_>=fadeFrames_) {active_=1-active_;transitioning_=false;}
        }
        samples[2*frame]=output(left);samples[2*frame+1]=output(right);
    }
}
}

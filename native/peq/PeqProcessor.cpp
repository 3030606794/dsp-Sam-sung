// SPDX-License-Identifier: GPL-3.0-or-later
#include "PeqProcessor.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace dspfusion {
namespace {
constexpr double pi=3.1415926535897932384626433832795;
// These are numerical circuit breakers, far above the audio range. They are
// not a compressor, automatic preamp, or substitute for James' output limiter.
constexpr double stateCeiling=1e100,outputCeiling=1e6;
double input(float x) { return std::isfinite(x)?double(x):0.; }
float output(double x) { return std::isfinite(x)?float(std::clamp(x,-outputCeiling,outputCeiling)):0.f; }
double boundedState(double x) {
    // Each section keeps its filtered output on overflow; it never substitutes
    // dry input. Bounding both states and output prevents 170 stacked +100 dB
    // bands from exceeding double's exponent range. Ordinary audio is untouched.
    return std::isnan(x)?0.:std::clamp(x,-stateCeiling,stateCeiling);
}
}

bool PeqProcessor::design(const Band& band,uint32_t rate,Coeff& c) {
    const double requestedHz=band.frequency/1000.;
    const double nyquist=rate*.5;
    if(!band.enabled || requestedHz>nyquist || (band.type<=HighShelf && band.gain==0)) return false;
    // The exact endpoint is the left-hand limit, one protocol frequency quantum
    // inside Nyquist. Keep the requested value; never move an above-Nyquist band.
    // tan(pi/2) is singular and must not turn a legal endpoint into a disabled band.
    const double hz=requestedHz==nyquist?nyquist-.001:requestedHz;
    double quality=band.q;
    if(band.constantSkirt && band.type==Peak) {
        // Preserve broad low-Q responses, then smoothly decouple a narrow
        // peak's outer skirt from gain. Boost and cut use the same effective Q.
        const double t=std::clamp(std::log10(quality),0.,1.);
        const double weight=t*t*(3-2*t);
        quality*=std::pow(10.,std::abs(double(band.gain))*weight/40000.);
    }
    double g=std::tan(pi*hz/rate),k=1./quality;
    const double a=std::pow(10.,band.gain/40000.);
    double high=1,mid=k,low=1;
    // RBJ transfer functions expressed as high/band/low state-variable outputs.
    // Shelves still use Q, not slope S; band-pass has unity center gain at 0 dB.
    // Trapezoidal integration is algebraically equivalent to the bilinear RBJ
    // biquad, without forming 1 +/- a1 + a2 (which can round to zero at Nyquist).
    // References: https://www.w3.org/TR/audio-eq-cookbook/
    // https://cytomic.com/files/dsp/SvfLinearTrapAllOutputs.pdf
    switch(band.type) {
        case Peak: mid=k*a;k/=a;break;
        case LowShelf: g/=std::sqrt(a);mid=k*a;low=a*a;break;
        case HighShelf:g*=std::sqrt(a);high=a*a;mid=k*a;break;
        case LowPass:high=0;mid=0;break;
        case HighPass:mid=0;low=0;break;
        case Notch:mid=0;break;
        case BandPass:high=0;low=0;break;
        default:return false;
    }
    if(band.type>=LowPass) {
        // Protocol 5: these filters keep their poles/zeros and apply gain to
        // the complete filtered output. A zero gain does not bypass a filter.
        const double gain=a*a;
        high*=gain;mid*=gain;low*=gain;
    }
    const double inverse=1/(1+g*(g+k));
    c={inverse,(g+k)*inverse,g*inverse,g*(g+k)*inverse,g*g*inverse,high,mid,low};
    // Validated frequencies below Nyquist give finite g>0 and damping k>0.
    // Every such band is retained; there is no rounded Schur test or pole clamp.
    return true;
}

PeqProcessor::Prepared PeqProcessor::prepare(const Settings& settings) const {
    Prepared result;
    if(settings.enabled) for(size_t i=0;i<settings.count;++i) {
        Coeff c;
        if(design(settings.bands[i],sampleRate_,c)) result.coefficients[result.count++]=c;
    }
    return result;
}

bool PeqProcessor::setParameters(const int32_t* words,size_t count) {
    if(!words || count<3 || words[0]<1 || words[0]>3 || (words[1]!=0&&words[1]!=1) ||
       words[2]<0 || words[2]>int32_t(kMaxBands))return false;
    const size_t stride=words[0]==3?6:5;
    if(count!=3+stride*words[2])return false;
    Settings next;next.enabled=words[1]!=0;next.count=words[2];
    for(size_t i=0;i<next.count;++i) {
        const auto* p=words+3+i*stride;
        if(p[0]<Peak || p[0]>BandPass || (p[1]!=0&&p[1]!=1) ||
           p[2]<1000 || p[2]>768000000 || p[3]<-100000 || p[3]>100000) return false;
        double q;
        if(words[0]==1) {
            // Keep every original packet's units and validation unchanged.
            if(p[4]<100 || p[4]>100000)return false;
            q=p[4]/1000.;
        } else if(words[0]==2) {
            float value;std::memcpy(&value,p+4,sizeof(value));
            // Compare float boundaries: 0.0001f rounds just below double 0.0001.
            // Retain the exact decoded Q so the app can plot the same transfer.
            if(!std::isfinite(value) || value<float(kMinQ) || value>float(kMaxQ))return false;
            q=value;
        } else {
            const uint64_t bits=uint32_t(p[4])|(uint64_t(uint32_t(p[5]))<<32);
            std::memcpy(&q,&bits,sizeof(q));
            if(!std::isfinite(q) || q<kMinQ || q>kMaxQ)return false;
        }
        next.bands[i]={p[0],p[1],p[2],p[3],q,words[0]==3};
    }
    std::lock_guard<std::mutex> lock(updateMutex_);
    settings_=next;pending_=prepare(next);++revision_;
    pcmParameterHash_=peqcapture::hash(words,count);
    pcm_.configuration(revision_,next.enabled,pcmParameterHash_);
    return true;
}

void PeqProcessor::configure(uint32_t sampleRate) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    if(sampleRate<8000 || sampleRate>768000 || sampleRate==sampleRate_) return;
    sampleRate_=sampleRate;fadeFrames_=std::max(1u,sampleRate/50);
    pending_=prepare(settings_);++revision_;
    // A rate change invalidates delay states. Fade in freshly designed filters.
    for(auto& bank:banks_) {bank.filters.count=0;bank.states={};}
    active_=0;transitioning_=false;fadePosition_=0;
    pcm_.discontinuity();
    pcm_.configuration(revision_,settings_.enabled,pcmParameterHash_);
}

void PeqProcessor::reset() {
    // The host excludes process() while resetting. Reapply the newest settings
    // through the normal fade so stale high-Q delay states cannot reappear.
    std::lock_guard<std::mutex> lock(updateMutex_);
    for(auto& bank:banks_) {bank.filters.count=0;bank.states={};}
    active_=0;transitioning_=false;fadePosition_=0;
    ++revision_;
    pcm_.discontinuity();
    pcm_.configuration(revision_,settings_.enabled,pcmParameterHash_);
}

void PeqProcessor::adoptPending() {
    if(transitioning_) return;
    std::unique_lock<std::mutex> lock(updateMutex_,std::try_to_lock);
    if(!lock.owns_lock() || revision_==acceptedRevision_) return;
    acceptedRevision_=revision_;
    const auto& current=banks_[active_].filters;
    bool equal=current.count==pending_.count;
    for(size_t i=0;equal && i<current.count;++i) equal=current.coefficients[i]==pending_.coefficients[i];
    if(equal) return; // Redundant UI sync must not erase ringing/filter history.
    auto& target=banks_[1-active_];target.filters=pending_;target.states={};
    fadePosition_=0;transitioning_=true;
}

void PeqProcessor::Bank::tick(double& left,double& right) {
    for(size_t i=0;i<filters.count;++i) {
        const auto& c=filters.coefficients[i];auto& s=states[i];
        const auto channel=[&c](double x,double& s1,double& s2) {
            const double delta=x-s2;
            const double step1=c.step1*delta-c.feedback1*s1;
            const double step2=c.step2*delta+c.step1*s1;
            const double high=c.highInput*delta-c.highState*s1;
            const double band=s1+step1,low=s2+step2;
            s1=boundedState(s1+2*step1);s2=boundedState(s2+2*step2);
            return boundedState(c.mixHigh*high+c.mixBand*band+c.mixLow*low);
        };
        left=channel(left,s.l1,s.l2);right=channel(right,s.r1,s.r2);
    }
}

void PeqProcessor::Bank::flushDenormals() {
    for(size_t i=0;i<filters.count;++i) {
        auto& s=states[i];
        if(std::abs(s.l1)<1e-30) s.l1=0;if(std::abs(s.l2)<1e-30) s.l2=0;
        if(std::abs(s.r1)<1e-30) s.r1=0;if(std::abs(s.r2)<1e-30) s.r2=0;
    }
}

void PeqProcessor::process(float* samples,uint32_t frames) {
    if(!samples || !frames) return;
    adoptPending();
    pcm_.begin(sampleRate_,acceptedRevision_,transitioning_);
    for(uint32_t frame=0;frame<frames;++frame) {
        const float observedInputLeft=samples[frame*2],observedInputRight=samples[frame*2+1];
        const bool observedTransition=transitioning_;
        double left=input(samples[frame*2]),right=input(samples[frame*2+1]);
        double oldLeft=left,oldRight=right;
        banks_[active_].tick(oldLeft,oldRight);
        if(transitioning_) {
            banks_[1-active_].tick(left,right);
            const double mix=double(fadePosition_)/fadeFrames_;
            oldLeft+=(left-oldLeft)*mix;oldRight+=(right-oldRight)*mix;
            if(++fadePosition_>=fadeFrames_) {
                active_=1-active_;transitioning_=false;
                // Adopting another pending snapshot waits until the next block.
            }
        }
        samples[frame*2]=output(oldLeft);samples[frame*2+1]=output(oldRight);
        pcm_.push(observedInputLeft,observedInputRight,samples[frame*2],samples[frame*2+1],observedTransition);
    }
    pcm_.advance(frames);
    banks_[active_].flushDenormals();
    if(transitioning_) banks_[1-active_].flushDenormals();
}
}

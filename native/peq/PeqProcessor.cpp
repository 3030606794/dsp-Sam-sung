// SPDX-License-Identifier: GPL-3.0-or-later
#include "PeqProcessor.h"
#include <algorithm>
#include <cmath>

namespace dspfusion {
namespace {
constexpr double pi=3.1415926535897932384626433832795;
// These are numerical circuit breakers, far above the audio range. They are
// not a compressor, automatic preamp, or substitute for James' output limiter.
constexpr double stateCeiling=1e100,outputCeiling=1e6;
double input(float x) { return std::isfinite(x)?double(x):0.; }
float output(double x) { return std::isfinite(x)?float(std::clamp(x,-outputCeiling,outputCeiling)):0.f; }
}

bool PeqProcessor::design(const Band& band,uint32_t rate,Coeff& c) {
    const double hz=band.frequency/1000.;
    if(!band.enabled || hz>=rate*.5 || (band.type<=HighShelf && band.gain==0)) return false;
    const double w=2*pi*hz/rate,cs=std::cos(w),sn=std::sin(w);
    const double alpha=sn/(2*(band.q/1000.)),a=std::pow(10.,band.gain/40000.);
    double b0=1,b1=0,b2=0,a0=1,a1=-2*cs,a2=1-alpha;
    // Independent implementation of the standard transfer functions:
    // https://www.w3.org/TR/audio-eq-cookbook/ . Shelves use Q, not slope S.
    switch(band.type) {
        case Peak:
            b0=1+alpha*a;b1=-2*cs;b2=1-alpha*a;
            a0=1+alpha/a;a2=1-alpha/a;break;
        case LowShelf: {
            const double t=2*std::sqrt(a)*alpha;
            b0=a*((a+1)-(a-1)*cs+t);b1=2*a*((a-1)-(a+1)*cs);b2=a*((a+1)-(a-1)*cs-t);
            a0=(a+1)+(a-1)*cs+t;a1=-2*((a-1)+(a+1)*cs);a2=(a+1)+(a-1)*cs-t;break;
        }
        case HighShelf: {
            const double t=2*std::sqrt(a)*alpha;
            b0=a*((a+1)+(a-1)*cs+t);b1=-2*a*((a-1)+(a+1)*cs);b2=a*((a+1)+(a-1)*cs-t);
            a0=(a+1)-(a-1)*cs+t;a1=2*((a-1)-(a+1)*cs);a2=(a+1)-(a-1)*cs-t;break;
        }
        case LowPass:b0=(1-cs)*.5;b1=1-cs;b2=b0;a0=1+alpha;break;
        case HighPass:b0=(1+cs)*.5;b1=-1-cs;b2=b0;a0=1+alpha;break;
        case Notch:b0=1;b1=-2*cs;b2=1;a0=1+alpha;break;
        case BandPass:b0=alpha;b1=0;b2=-alpha;a0=1+alpha;break;
        default:return false;
    }
    c={b0/a0,b1/a0,b2/a0,a1/a0,a2/a0};
    // The normalized second-order denominator must remain strictly Schur stable.
    return std::isfinite(c.b0)&&std::isfinite(c.b1)&&std::isfinite(c.b2)&&
        std::isfinite(c.a1)&&std::isfinite(c.a2)&&std::abs(c.a2)<1 &&
        1+c.a1+c.a2>0 && 1-c.a1+c.a2>0;
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
    if(!words || count<3 || words[0]!=1 || (words[1]!=0&&words[1]!=1) ||
       words[2]<0 || words[2]>int32_t(kMaxBands) || count!=size_t(3+5*words[2])) return false;
    Settings next;next.enabled=words[1]!=0;next.count=words[2];
    for(size_t i=0;i<next.count;++i) {
        const auto* p=words+3+i*5;
        if(p[0]<Peak || p[0]>BandPass || (p[1]!=0&&p[1]!=1) ||
           p[2]<20000 || p[2]>20000000 || p[3]<-24000 || p[3]>24000 || p[4]<100 || p[4]>30000) return false;
        next.bands[i]={p[0],p[1],p[2],p[3],p[4]};
    }
    std::lock_guard<std::mutex> lock(updateMutex_);
    settings_=next;pending_=prepare(next);++revision_;
    return true;
}

void PeqProcessor::configure(uint32_t sampleRate) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    if(sampleRate<8000 || sampleRate>192000 || sampleRate==sampleRate_) return;
    sampleRate_=sampleRate;fadeFrames_=std::max(1u,sampleRate/50);
    pending_=prepare(settings_);++revision_;
    // A rate change invalidates delay states. Fade in freshly designed filters.
    for(auto& bank:banks_) {bank.filters.count=0;bank.states={};}
    active_=0;transitioning_=false;fadePosition_=0;
}

void PeqProcessor::reset() {
    // The host excludes process() while resetting. Reapply the newest settings
    // through the normal fade so stale high-Q delay states cannot reappear.
    std::lock_guard<std::mutex> lock(updateMutex_);
    for(auto& bank:banks_) {bank.filters.count=0;bank.states={};}
    active_=0;transitioning_=false;fadePosition_=0;
    ++revision_;
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
        double l=c.b0*left+s.l1,r=c.b0*right+s.r1;
        const double l1=c.b1*left-c.a1*l+s.l2,r1=c.b1*right-c.a1*r+s.r2;
        const double l2=c.b2*left-c.a2*l,r2=c.b2*right-c.a2*r;
        if(!std::isfinite(l1)||!std::isfinite(l2)||!std::isfinite(r1)||!std::isfinite(r2)||
           std::abs(l)>stateCeiling || std::abs(r)>stateCeiling) {
            s={};l=std::clamp(std::isfinite(left)?left:0.,-outputCeiling,outputCeiling);
            r=std::clamp(std::isfinite(right)?right:0.,-outputCeiling,outputCeiling);
        } else s={l1,l2,r1,r2};
        left=l;right=r;
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
    for(uint32_t frame=0;frame<frames;++frame) {
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
    }
    banks_[active_].flushDenormals();
    if(transitioning_) banks_[1-active_].flushDenormals();
}
}

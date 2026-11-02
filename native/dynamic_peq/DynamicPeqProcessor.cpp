// SPDX-License-Identifier: GPL-3.0-or-later
#include "DynamicPeqProcessor.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace dspfusion {
namespace {
constexpr double pi=3.14159265358979323846;
constexpr uint32_t controlFrames=16;
double finiteInput(float value){return std::isfinite(value)?value:0.;}
double bounded(double value){return std::isfinite(value)?std::clamp(value,-1e12,1e12):0.;}
int32_t dbWord(double amplitude){return int32_t(std::lround(std::clamp(20000.*std::log10(std::max(amplitude,1e-6)),-120000.,48000.)));}
}
DynamicPeqProcessor::DynamicPeqProcessor(){publish(0,0,false);}

void DynamicPeqProcessor::Circuit::approach(const Circuit& target,double t){
    g+=(target.g-g)*t;k+=(target.k-k)*t;high+=(target.high-high)*t;
    mid+=(target.mid-mid)*t;low+=(target.low-low)*t;
}
DynamicPeqProcessor::Circuit DynamicPeqProcessor::circuit(const PreparedBand& p,double gainDb){
    const double a=std::pow(10.,gainDb/40.);
    Circuit c{p.g,p.k,1,p.k,1};
    if(p.band.type==0){c.k/=a;c.mid*=a;}
    else if(p.band.type==1){c.g/=std::sqrt(a);c.mid*=a;c.low=a*a;}
    else {c.g*=std::sqrt(a);c.high=a*a;c.mid*=a;}
    return c;
}
double DynamicPeqProcessor::channel(const Circuit& c,double x,double& s1,double& s2){
    // Trapezoidal SVF: smooth positive circuit parameters, never interpolate
    // biquad feedback coefficients. The damping remains strictly positive.
    const double inverse=1./(1.+c.g*(c.g+c.k));
    const double delta=x-s2,step1=c.g*inverse*(delta-(c.g+c.k)*s1);
    const double step2=c.g*inverse*(c.g*delta+s1);
    const double high=inverse*(delta-(c.g+c.k)*s1),band=s1+step1,low=s2+step2;
    s1=bounded(s1+2*step1);s2=bounded(s2+2*step2);
    return bounded(c.high*high+c.mid*band+c.low*low);
}
DynamicPeqProcessor::Prepared DynamicPeqProcessor::prepare(const Settings& settings) const {
    Prepared result;result.settings=settings;result.fingerprint=1;
    const auto hash=[&](int32_t word){result.fingerprint=31u*result.fingerprint+uint32_t(word);};
    hash(1);hash(settings.enabled);hash(int32_t(settings.count));
    for(size_t i=0;i<settings.count;++i){const auto& b=settings.bands[i];
        for(int32_t value:{b.type,b.enabled,b.frequency,b.gain,b.q,b.dynamic,b.threshold,b.ratio,b.range,b.attack,b.release,b.knee})hash(value);
    }
    for(size_t i=0;i<settings.count;++i){
        auto& p=result.bands[i];p.band=settings.bands[i];
        // Keep the request intact. At/near Nyquist the section is unavailable;
        // it becomes available automatically when a higher host rate arrives.
        p.available=settings.enabled && p.band.enabled && p.band.frequency/1000.<rate_*.499;
        if(!p.available)continue;
        p.g=std::tan(pi*(p.band.frequency/1000.)/rate_);p.k=1000./p.band.q;
        p.attack=std::exp(-1./(p.band.attack*1e-6*rate_));
        p.release=std::exp(-1./(p.band.release*1e-6*rate_));
        // Bell detection is unity at center regardless of Q. Shelf detectors
        // are monotonic unity-gain LP/HP, without high-Q resonant over-reading.
        const double detectorK=p.band.type==0?p.k:std::max(std::sqrt(2.),p.k);
        p.detector={p.g,detectorK,0,p.band.type==0?detectorK:0,p.band.type==1?1.:0.};
        if(p.band.type==2)p.detector.high=1;
        p.initial=circuit(p,p.band.gain/1000.);
        if(p.band.gain || (p.band.dynamic && p.band.range && p.band.ratio>1000))result.active=true;
    }
    return result;
}
bool DynamicPeqProcessor::setParameters(const int32_t* words,size_t count){
    if(!words || count<3 || words[0]!=1 || (words[1]!=0&&words[1]!=1) || words[2]<0 || words[2]>int32_t(kMaxBands) || count!=3+size_t(words[2])*kBandWords)return false;
    Settings next;next.enabled=words[1]!=0;next.count=words[2];
    for(size_t i=0;i<next.count;++i){
        const int32_t* p=words+3+i*kBandWords;
        if(p[0]<0||p[0]>2||(p[1]!=0&&p[1]!=1)||p[2]<20000||p[2]>20000000||p[3]<-24000||p[3]>24000||p[4]<100||p[4]>30000||(p[5]!=0&&p[5]!=1)||p[6]<-90000||p[6]>0||p[7]<1000||p[7]>20000||p[8]<0||p[8]>24000||p[9]<100||p[9]>500000||p[10]<5000||p[10]>3000000||p[11]<0||p[11]>24000)return false;
        next.bands[i]={p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8],p[9],p[10],p[11]};
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if(next!=settings_){settings_=next;pending_=prepare(next);++revision_;}
    requestedEnabled_.store(next.enabled,std::memory_order_release);
    requestedCount_.store(int32_t(next.count),std::memory_order_release);
    return true;
}
void DynamicPeqProcessor::configure(uint32_t sampleRate){
    if(sampleRate<8000||sampleRate>768000)return;
    std::lock_guard<std::mutex> lock(mutex_);
    if(rate_==sampleRate)return;
    rate_=sampleRate;publishedRate_.store(sampleRate,std::memory_order_release);
    fadeFrames_=std::max(1u,rate_/50);pending_=prepare(settings_);++revision_;
    banks_={};active_=0;transitioning_=false;fadePosition_=0;publish(0,0,false);
}
void DynamicPeqProcessor::reset(){
    std::lock_guard<std::mutex> lock(mutex_);
    banks_={};active_=0;transitioning_=false;fadePosition_=0;++revision_;publish(0,0,false);
}
void DynamicPeqProcessor::adoptPending(){
    std::unique_lock<std::mutex> lock(mutex_,std::try_to_lock);
    if(!lock.owns_lock()||revision_==acceptedRevision_)return;
    if(transitioning_&&pending_.settings.enabled)return;
    acceptedRevision_=revision_;
    auto& old=banks_[active_];auto& next=banks_[1-active_];
    next={};next.prepared=pending_;
    for(size_t i=0;i<pending_.settings.count;++i){
        auto& runtime=next.bands[i];runtime.circuit=runtime.target=pending_.bands[i].initial;
        if(!pending_.bands[i].available)continue;
        for(size_t j=0;j<old.prepared.settings.count;++j)
            if(old.prepared.bands[j].available && pending_.bands[i].band==old.prepared.bands[j].band){runtime=old.bands[j];break;}
    }
    if(!pending_.settings.enabled || (!old.prepared.active&&!pending_.active)){
        active_=1-active_;transitioning_=false;fadePosition_=0;return;
    }
    transitioning_=true;fadePosition_=0;
}
double DynamicPeqProcessor::gainReduction(const Band& b,double level){
    if(!b.dynamic||b.ratio<=1000||!b.range)return 0.;
    const double over=level-b.threshold/1000.,knee=b.knee/1000.;
    double above=0;
    if(knee>0 && over>-knee*.5 && over<knee*.5)above=(over+knee*.5)*(over+knee*.5)/(2*knee);
    else if(over>0)above=over;
    return std::clamp(above*(1.-1000./b.ratio),0.,b.range/1000.);
}
void DynamicPeqProcessor::Bank::tick(double dryLeft,double dryRight,double& left,double& right){
    left=dryLeft;right=dryRight;
    for(size_t i=0;i<prepared.settings.count;++i){
        const auto& p=prepared.bands[i];if(!p.available)continue;
        auto& s=bands[i];auto& d=s.detector;
        const double dl=channel(p.detector,dryLeft,d.l1,d.l2),dr=channel(p.detector,dryRight,d.r1,d.r2);
        // Linked maximum channel power prevents anti-phase cancellation and
        // gives exactly the same attenuation to both channels.
        const double power=std::max(dl*dl,dr*dr),coefficient=power>s.envelope?p.attack:p.release;
        s.envelope=coefficient*s.envelope+(1-coefficient)*power;
        if(s.control==0){
            const double level=10.*std::log10(std::max(s.envelope,1e-24));
            s.reductionTarget=gainReduction(p.band,level);
            s.target=circuit(p,p.band.gain/1000.-s.reductionTarget);s.control=controlFrames;
        }
        const double t=1./s.control;s.circuit.approach(s.target,t);
        s.reduction+=(s.reductionTarget-s.reduction)*t;--s.control;
        auto& f=s.filter;
        if(p.band.gain==0 && !p.band.dynamic){continue;}
        left=channel(s.circuit,left,f.l1,f.l2);right=channel(s.circuit,right,f.r1,f.r2);
    }
}
void DynamicPeqProcessor::Bank::clearDenormals(){
    for(auto& b:bands){
        for(State* state:{&b.filter,&b.detector}){
            if(std::abs(state->l1)<1e-25)state->l1=0;if(std::abs(state->l2)<1e-25)state->l2=0;
            if(std::abs(state->r1)<1e-25)state->r1=0;if(std::abs(state->r2)<1e-25)state->r2=0;
        }
        if(b.envelope<1e-30)b.envelope=0;
    }
}
void DynamicPeqProcessor::publish(double inPeak,double outPeak,bool active){
    std::array<int32_t,kStatusWords> words{};words[0]=1;words[1]=requestedEnabled_.load();words[2]=requestedCount_.load();words[3]=rate_;
    words[4]=int32_t(processedFrames_&0x7fffffff);words[5]=dbWord(inPeak);words[6]=dbWord(outPeak);
    std::fill(words.begin()+8,words.begin()+24,-120000);
    const auto& bank=banks_[transitioning_?1-active_:active_];
    words[7]=transitioning_?0:int32_t(bank.prepared.fingerprint);
    if(active)for(size_t i=0;i<bank.prepared.settings.count;++i)if(bank.prepared.bands[i].available){
        words[8+i]=dbWord(std::sqrt(bank.bands[i].envelope));words[24+i]=int32_t(std::lround(bank.bands[i].reduction*1000.));
    }
    epoch_.fetch_add(1,std::memory_order_acq_rel);
    for(size_t i=0;i<words.size();++i)status_[i].store(words[i],std::memory_order_relaxed);
    epoch_.fetch_add(1,std::memory_order_release);
}
void DynamicPeqProcessor::readStatus(int32_t* words) const {
    if(!words)return;
    // Single audio writer (reset/configure are host-excluded), atomic payload.
    // Retry only on publication, without taking the audio/control mutex.
    for(;;){
        const auto before=epoch_.load(std::memory_order_acquire);if(before&1u)continue;
        for(size_t i=0;i<kStatusWords;++i)words[i]=status_[i].load(std::memory_order_relaxed);
        if(before==epoch_.load(std::memory_order_acquire))break;
    }
    words[1]=requestedEnabled_.load(std::memory_order_acquire);words[2]=requestedCount_.load(std::memory_order_acquire);
    words[3]=publishedRate_.load(std::memory_order_acquire);
    for(size_t i=size_t(words[2]);i<kMaxBands;++i){words[8+i]=-120000;words[24+i]=0;}
    if(!words[1]){words[5]=words[6]=-120000;std::fill(words+8,words+24,-120000);std::fill(words+24,words+40,0);}
}
void DynamicPeqProcessor::process(float* samples,uint32_t frames){
    if(!samples||!frames)return;
    adoptPending();
    // Off leaves every bit untouched, including signed zero and subnormals.
    if(!requestedEnabled_.load(std::memory_order_acquire)){publish(0,0,false);return;}
    const bool active=banks_[active_].prepared.active || (transitioning_&&banks_[1-active_].prepared.active);
    if(!active){publish(0,0,false);return;}
    double inPeak=0,outPeak=0;
    for(uint32_t frame=0;frame<frames;++frame){
        const double dryLeft=finiteInput(samples[2*frame]),dryRight=finiteInput(samples[2*frame+1]);
        inPeak=std::max({inPeak,std::abs(dryLeft),std::abs(dryRight)});
        double left,right;banks_[active_].tick(dryLeft,dryRight,left,right);
        if(transitioning_){
            double nextLeft,nextRight;banks_[1-active_].tick(dryLeft,dryRight,nextLeft,nextRight);
            const double mix=double(fadePosition_+1)/fadeFrames_;
            left+=(nextLeft-left)*mix;right+=(nextRight-right)*mix;
            if(++fadePosition_>=fadeFrames_){active_=1-active_;transitioning_=false;}
        }
        samples[2*frame]=float(bounded(left));samples[2*frame+1]=float(bounded(right));
        outPeak=std::max({outPeak,std::abs(left),std::abs(right)});
    }
    processedFrames_+=frames;banks_[active_].clearDenormals();
    if(transitioning_)banks_[1-active_].clearDenormals();publish(inPeak,outPeak,true);
}
}

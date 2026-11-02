// SPDX-License-Identifier: GPL-3.0-or-later
#include "SurroundListeningTools.h"
#include <algorithm>
#include <cmath>

namespace dspfusion {
namespace {
constexpr double pi=3.14159265358979323846;
int32_t milliDb(double gain){return int32_t(std::llround(20000*std::log10(std::max(gain,1e-12))));}
}
bool SurroundListeningTools::setParameters(const int32_t* w,size_t n){
    constexpr Packet lo{1,0,0,500,1,0,-600,20,20,21,1000,20,0};
    constexpr Packet hi{1,1,120,10000,INT32_MAX,4,-120,16000,16000,20000,30000,500,INT32_MAX};
    if(!w || n!=kWords)return false;
    for(size_t i=0;i<n;++i)if(w[i]<lo[i] || w[i]>hi[i])return false;
    if(w[8]>=w[9] || (w[5] && !w[12]))return false;
    std::lock_guard<std::mutex> lock(mutex_);std::copy_n(w,n,pending_.begin());return true;
}
void SurroundListeningTools::clearMatch(){ready_={};ratios_={};gains_={};count_=settle_=0;inPower_=outPower_=0;measuring_=progress_=0;quiet_=limited_=false;}
void SurroundListeningTools::clearGenerator(){mode_=0;position_=total_=tail_=0;envelope_=tailFrom_=0;phase_=hp_=lp_=0;}
void SurroundListeningTools::cancel(bool invalidate){
    std::lock_guard<std::mutex> lock(mutex_);if(pending_[12])blockedToken_.store(pending_[12]);pending_[5]=pending_[12]=0;cancel_.store(true);
    if(invalidate){clearMatch();clearGenerator();applied_=1;audition_=0;enabled_=false;publish();}
}
void SurroundListeningTools::begin(uint32_t rate,bool enabled,int audition){
    Packet next=config_;
    {std::unique_lock<std::mutex> lock(mutex_,std::try_to_lock);if(lock.owns_lock())next=pending_;}
    const bool cancelled=cancel_.exchange(false);
    if(rate!=rate_){rate_=rate;clearMatch();clearGenerator();applied_=1;}
    if(next[4]!=config_[4] || next[1]!=config_[1] || next[2]!=config_[2] || next[3]!=config_[3])clearMatch();
    enabled_=enabled;audition_=audition;alpha_=1-std::exp(-1./(rate_*.02));
    if(!enabled){clearMatch();clearGenerator();applied_=1;lastToken_=next[12];config_=next;publish();return;}
    if(mode_ && (!next[5] || cancelled) && !tail_){if(envelope_<=0)clearGenerator();else {tail_=std::max<uint64_t>(1,fade_);tailFrom_=envelope_;}}
    const bool fresh=next[5] && next[12]!=lastToken_ && next[12]!=blockedToken_.load();
    if(fresh && mode_ && !tail_){if(envelope_<=0)clearGenerator();else {tail_=std::max<uint64_t>(1,fade_);tailFrom_=envelope_;}}
    if(fresh && !mode_){
        clearGenerator();mode_=next[5];lastToken_=next[12];
        total_=uint64_t(rate_)*next[10]/1000;fade_=std::max<uint64_t>(1,uint64_t(rate_)*next[11]/1000);
        amplitude_=std::pow(10.,next[6]/200.);tone_=std::min(next[7],int(rate_*.45));
        const double scale=std::min(1.,rate_*.45/next[9]);high_=int(std::llround(next[9]*scale));low_=std::min(high_-1,std::max(1,int(std::llround(next[8]*scale))));
        const double kh=std::tan(pi*low_/rate_),kl=std::tan(pi*high_/rate_);
        hpB0_=1/(1+kh);hpA1_=(kh-1)/(1+kh);lpB0_=kl/(1+kl);lpA1_=(kl-1)/(1+kl);
        // Exact first-order l1 bounds keep the source below its peak setting without clipping.
        noiseGain_=1/(std::max(1.,2*hpB0_)*std::max(1.,2*lpB0_));random_=0x6d2b79f5;
    }
    if(!mode_){const double scale=std::min(1.,rate_*.45/next[9]);high_=int(std::llround(next[9]*scale));low_=std::min(high_-1,std::max(1,int(std::llround(next[8]*scale))));tone_=std::min(next[7],int(rate_*.45));}
    config_=next;
    const int target=config_[1] && audition_>=3 && audition_<=4?audition_:0;
    if(target!=measuring_){measuring_=target;count_=settle_=0;inPower_=outPower_=0;progress_=0;quiet_=false;}
}
void SurroundListeningTools::inject(double& left,double& right){
    if(!mode_)return;
    double value;
    if(mode_==4){
        random_^=random_<<13;random_^=random_>>17;random_^=random_<<5;
        const double noise=double(random_)/2147483648.-1;
        const double highPassed=hpB0_*noise+hp_;hp_=-hpB0_*noise-hpA1_*highPassed;
        const double band=lpB0_*highPassed+lp_;lp_=lpB0_*highPassed-lpA1_*band;
        value=band*noiseGain_*amplitude_;
    }else {value=std::sin(phase_)*amplitude_;phase_+=2*pi*tone_/rate_;if(phase_>=2*pi)phase_-=2*pi;}
    if(tail_){envelope_=tailFrom_*double(tail_-1)/fade_;--tail_;}
    else envelope_=std::min({1.,double(position_+1)/fade_,double(total_-position_)/fade_});
    const double l=mode_==2?0:value,r=mode_==1?0:value;
    left+=(l-left)*envelope_;right+=(r-right)*envelope_;
    ++position_;
    if((tailFrom_>0 && !tail_) || position_>=total_)clearGenerator();
}
double SurroundListeningTools::match(double dryL,double dryR,double left,double right,bool settled){
    if(measuring_ && !ready_[measuring_-3]){
        if(!settled){settle_=count_=0;inPower_=outPower_=0;progress_=0;}
        else if(settle_++>=rate_/2){
            inPower_+=(dryL*dryL+dryR*dryR)*.5;outPower_+=(left*left+right*right)*.5;++count_;
            const uint64_t required=uint64_t(rate_)*config_[3]/1000;
            progress_=int(std::min<uint64_t>(1000,count_*1000/required));
            if(count_>=required){
                if(inPower_/count_>1e-8 && outPower_/count_>1e-12){
                    const int index=measuring_-3;ratios_[index]=std::clamp(10*std::log10(outPower_/inPower_),-120.,120.);ready_[index]=true;quiet_=false;
                    if(ready_[0] && ready_[1]){const double reference=std::min(ratios_[0],ratios_[1]),limit=config_[2]/10.;limited_=std::abs(ratios_[0]-ratios_[1])>limit;
                        for(int i=0;i<2;++i)gains_[i]=std::max(-limit,reference-ratios_[i]);}
                }else {count_=0;inPower_=outPower_=0;progress_=0;quiet_=true;}
            }
        }
    }
    const double target=config_[1] && ready_[0] && ready_[1] && audition_>=3 && audition_<=4?std::pow(10.,gains_[audition_-3]/20.):1;
    applied_+=alpha_*(target-applied_);if(std::abs(applied_-target)<1e-12)applied_=target;
    return applied_;
}
void SurroundListeningTools::publish(){
    std::array<int32_t,kRuntimeWords> v{};v[0]=1;v[2]=rate_;v[3]=enabled_;v[4]=audition_;v[5]=config_[1];v[6]=ready_[0];v[7]=ready_[1];
    for(int i=0;i<2;++i){v[8+i]=ready_[i]?int32_t(std::llround(ratios_[i]*1000)):INT32_MIN;v[10+i]=int32_t(std::llround(gains_[i]*1000));}
    v[12]=milliDb(applied_);v[13]=measuring_;v[14]=progress_;v[15]=mode_;
    v[16]=mode_?int32_t((tail_?tail_:total_-position_)*1000/rate_):0;v[17]=low_;v[18]=high_;v[19]=config_[4];v[20]=limited_;v[21]=quiet_;v[22]=tone_;v[23]=lastToken_;
    epoch_.fetch_add(1);for(size_t i=0;i<v.size();++i)runtime_[i].store(v[i]);epoch_.fetch_add(1);
}
bool SurroundListeningTools::snapshot(int32_t* w)const{
    if(!w)return false;
    for(int i=0;i<3;++i){const auto first=epoch_.load();if(first&1)continue;for(size_t j=0;j<kRuntimeWords;++j)w[j]=runtime_[j].load();const auto last=epoch_.load();if(first==last && !(last&1)){w[1]=last&0x7fffffff;return true;}}
    return false;
}
}

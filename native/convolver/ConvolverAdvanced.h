#pragma once
#include "ConvolverControl.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <atomic>

namespace convolution {
constexpr std::array<int32_t,19> advancedDefaults{1,0,60,0,0,12000,0,0,150,0,0,6000,0,0,50000,1000,50000,0,0};
inline bool validAdvanced(const int32_t* p,size_t n) {
    constexpr int32_t lo[]{1,0,20,0,0,1000,0,0,20,-120,0,1000,-120,0,5000,100,10000,-120,0};
    constexpr int32_t hi[]{1,1,1000,2,1,20000,2,1,1000,120,1,16000,120,2,500000,3000,200000,120,16};
    if(!p || n!=19)return false;
    for(unsigned i=0;i<19;++i)if(p[i]<lo[i] || p[i]>hi[i])return false;
    return !p[1] || !p[4] || p[2]<p[5];
}
struct Section {
    double b0=1,b1=0,b2=0,a1=0,a2=0;
    std::array<double,2> z1{},z2{};
    float process(float x,unsigned c) {
        const double y=b0*x+z1[c];z1[c]=b1*x-a1*y+z2[c];z2[c]=b2*x-a2*y;
        return float(y);
    }
    void reset(){z1.fill(0);z2.fill(0);}
};
struct Bank {
    std::array<Section,10> sections{};unsigned count=0;
    float process(float x,unsigned c){for(unsigned i=0;i<count;++i)x=sections[i].process(x,c);return x;}
    void reset(){for(auto& s:sections)s.reset();}
    void design(const std::array<int32_t,19>& p,uint32_t fs) {
        count=0;
        constexpr double pi=3.14159265358979323846;
        for(unsigned kind=0;kind<4;++kind) {
            const unsigned on=kind==0?1:kind==1?4:kind==2?7:10;
            if(!p[on] || (kind>=2 && p[on+2]==0))continue;
            const double hz=std::min(double(p[on+1]),fs*.45),w=2*pi*hz/fs,co=std::cos(w),si=std::sin(w);
            const unsigned order=kind<2?(2u<<p[on+2]):2,number=kind<2?order/2:1;
            for(unsigned k=0;k<number;++k) {
                Section s;double a0=1;
                if(kind<2) {
                    const double q=1/(2*std::cos(pi*(2*k+1)/(2*order))),alpha=si/(2*q);
                    const double sign=kind==0?1:-1;
                    s.b0=(1+sign*co)/2;s.b1=-sign*(1+sign*co);s.b2=s.b0;
                    a0=1+alpha;s.a1=-2*co;s.a2=1-alpha;
                } else {
                    const double a=std::pow(10.,p[on+2]/400.),alpha=si/std::sqrt(2.),beta=2*std::sqrt(a)*alpha;
                    if(kind==2) {
                        s.b0=a*((a+1)-(a-1)*co+beta);s.b1=2*a*((a-1)-(a+1)*co);s.b2=a*((a+1)-(a-1)*co-beta);
                        a0=(a+1)+(a-1)*co+beta;s.a1=-2*((a-1)+(a+1)*co);s.a2=(a+1)+(a-1)*co-beta;
                    } else {
                        s.b0=a*((a+1)+(a-1)*co+beta);s.b1=-2*a*((a-1)+(a+1)*co);s.b2=a*((a+1)+(a-1)*co-beta);
                        a0=(a+1)-(a-1)*co+beta;s.a1=2*((a-1)-(a+1)*co);s.a2=(a+1)-(a-1)*co-beta;
                    }
                }
                s.b0/=a0;s.b1/=a0;s.b2/=a0;s.a1/=a0;s.a2/=a0;sections[count++]=s;
            }
        }
    }
};
/** All storage is fixed or allocated on the control thread. Retired FFT plans are never freed in process(). */
struct Advanced {
    std::array<int32_t,19> p=advancedDefaults;
    uint32_t fs=48000,fadeFrames=720,filterRemaining=0,abRemaining=0,dryRemaining=0,actualCommon=0;
    Bank bank,oldBank,pendingBank;bool filterPending=false;
    float ab=1,abFrom=1,abTarget=1;
    std::array<float,17> dryWeights{},dryFrom{},dryTarget{};
    std::array<float,17> dryL{},dryR{};unsigned dryCursor=0;
    void* retired=nullptr;
    std::atomic<void*> completed{nullptr};
    uint32_t age=0,total=0,entryFrames=0,exitFrames=0,retiredFrames=0,currentFrames=0,currentChannels=0;
    int mode=0,fallback=0;
    float previousL=0,previousR=0,heldL=0,heldR=0;
    Advanced(){dryWeights[0]=dryFrom[0]=dryTarget[0]=1;}
    ~Advanced(){FusionConvDestroyJob(retired);FusionConvDestroyJob(completed.exchange(nullptr));}
    void complete(){if(retired){completed.store(retired,std::memory_order_release);retired=nullptr;retiredFrames=0;}age=total;}
    void* drain(){return completed.exchange(nullptr,std::memory_order_acq_rel);}
    void reset() {
        complete();if(filterPending){bank=pendingBank;filterPending=false;}bank.reset();oldBank=bank;filterRemaining=abRemaining=dryRemaining=0;
        ab=abFrom=abTarget;dryWeights=dryFrom=dryTarget;dryL.fill(0);dryR.fill(0);dryCursor=0;
        previousL=previousR=0;
    }
    void configure(uint32_t rate) {
        if(rate==fs)return;
        fs=rate;fadeFrames=std::max(1u,uint32_t(std::lround(fs*.015)));filterPending=false;bank.design(p,fs);reset();
    }
    bool set(const int32_t* next,size_t count) {
        if(!validAdvanced(next,count))return false;
        if(std::equal(p.begin(),p.end(),next))return true;
        const bool filters=!std::equal(p.begin()+1,p.begin()+13,next+1);
        const bool trim=p[17]!=next[17];
        std::copy_n(next,19,p.begin());
        if(filters){Bank next;next.design(p,fs);if(filterRemaining){pendingBank=next;filterPending=true;}else{oldBank=bank;bank=next;filterRemaining=fadeFrames;}}
        if(trim){abFrom=ab;abTarget=std::pow(10.f,p[17]/200.f);abRemaining=fadeFrames;}
        return true;
    }
    void loadedCommon(){if(actualCommon!=unsigned(p[18])){actualCommon=p[18];dryFrom=dryWeights;dryTarget.fill(0);dryTarget[actualCommon]=1;dryRemaining=fadeFrames;}}
    void filter(float& l,float& r) {
        if(!bank.count && !filterRemaining)return;
        const float nl=bank.process(l,0),nr=bank.process(r,1);
        if(filterRemaining){const float t=1-float(--filterRemaining)/fadeFrames;l=oldBank.process(l,0)*(1-t)+nl*t;r=oldBank.process(r,1)*(1-t)+nr*t;
            if(!filterRemaining && filterPending){oldBank=bank;bank=pendingBank;filterRemaining=fadeFrames;filterPending=false;}}
        else {l=nl;r=nr;}
    }
    void align(float l,float r,float& outL,float& outR) {
        dryL[dryCursor]=l;dryR[dryCursor]=r;
        if(dryRemaining){const float t=1-float(--dryRemaining)/fadeFrames;for(unsigned i=0;i<17;++i)dryWeights[i]=dryFrom[i]+(dryTarget[i]-dryFrom[i])*t;}
        if(!dryRemaining){outL=dryL[(dryCursor+17-actualCommon)%17];outR=dryR[(dryCursor+17-actualCommon)%17];}
        else {outL=outR=0;for(unsigned i=0;i<17;++i){outL+=dryL[(dryCursor+17-i)%17]*dryWeights[i];outR+=dryR[(dryCursor+17-i)%17]*dryWeights[i];}}
        dryCursor=(dryCursor+1)%17;
    }
    float compensation(){if(abRemaining){const float t=1-float(--abRemaining)/fadeFrames;ab=abFrom+(abTarget-abFrom)*t;if(!abRemaining)ab=abTarget;}return ab;}
    bool active()const{return age<total;}
    void* reap(bool force) {
        if(active() && !force)return nullptr;
        complete();void* result=drain();
        if(force){fallback=2;mode=0;}
        return result;
    }
    bool attach(void* job,uint32_t frames,uint32_t channels) {
        currentFrames=frames;currentChannels=channels;fallback=0;mode=p[13];
        if(!job || !FusionConvJobFrames(job) || !mode){mode=0;return false;}
        entryFrames=std::max(1u,uint32_t(std::lround(p[14]*double(fs)/1e6)));
        exitFrames=std::max(1u,uint32_t(std::lround(p[16]*double(fs)/1e6)));
        age=0;heldL=previousL;heldR=previousR;
        const uint64_t estimate=(uint64_t(FusionConvJobFrames(job))*FusionConvJobChannels(job)+uint64_t(frames)*channels)*32;
        if(estimate>128ull*1024*1024) {fallback=1;mode=3;total=entryFrames;return false;}
        retired=job;retiredFrames=FusionConvJobFrames(job);
        total=mode==1?entryFrames:entryFrames+std::min(retiredFrames,uint32_t(std::lround(p[15]*fs/1000.)));
        return true;
    }
    void inputs(float* l,float* r,size_t n) {
        if(!active() || !retired)return;
        float *ol=nullptr,*orr=nullptr;FusionConvJobInputs(retired,&ol,&orr);
        for(size_t i=0;i<n;++i) {
            if(mode==2) {const float t=std::min(1.f,float(age+i+1)/entryFrames);ol[i]=l[i]*(1-t);orr[i]=r[i]*(1-t);l[i]*=t;r[i]*=t;}
            else {ol[i]=l[i];orr[i]=r[i];}
        }
    }
    void mix(float* l,float* r,size_t n) {
        if(!active()) {if(n){previousL=l[n-1];previousR=r[n-1];}return;}
        float *ol=nullptr,*orr=nullptr;
        if(retired){FusionConvProcessJob(retired,n);FusionConvJobInputs(retired,&ol,&orr);}
        for(size_t i=0;i<n;++i) {
            const uint32_t position=age+uint32_t(i)+1;
            if(position<=total) {
                if(mode==1 || mode==3){const float t=std::min(1.f,float(position)/entryFrames);l[i]=l[i]*t+(ol?ol[i]:heldL)*(1-t);r[i]=r[i]*t+(orr?orr[i]:heldR)*(1-t);}
                else if(mode==2){const float t=position<=entryFrames?1.f:std::min(1.f,float(total-position)/std::max(1u,std::min(exitFrames,total-entryFrames)));l[i]+=ol[i]*t;r[i]+=orr[i]*t;}
            }
        }
        age=std::min(total,age+uint32_t(n));if(n){previousL=l[n-1];previousR=r[n-1];}if(!active())complete();
    }
    void snapshot(int32_t* out)const {
        std::fill_n(out,20,0);out[0]=1;out[2]=fs;out[3]=retired?2:1;out[4]=p[13];out[5]=active()?mode:0;out[6]=fallback;
        out[7]=active()?total-age:0;out[8]=retiredFrames;
        out[9]=int32_t((uint64_t(retiredFrames)*FusionConvJobChannels(retired)+uint64_t(currentFrames)*currentChannels)*32/1024);
        for(unsigned i=0;i<4;++i){const unsigned on=i==0?1:i==1?4:i==2?7:10;out[10+i]=p[on]?int32_t(std::min(double(p[on+1]),fs*.45)):0;}
        out[14]=actualCommon;out[15]=p[17];out[16]=filterRemaining;out[19]=currentFrames;
    }
};
}

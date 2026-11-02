#include "DiffAdvanced.h"
#include "ProfessionalTuning.h"
#include <algorithm>
#include <cmath>

namespace diffadvanced {
bool valid(const int32_t* v,size_t n) {
    if(!v || n!=defaults.size())return false;
    constexpr int32_t low[21]={0,0,0,20,-240,-120,0,0,-30,0,2,0,5,100,0,0,30,5,20,80,1000};
    constexpr int32_t high[21]={1,1,20000,80,0,0,1,2,50,120,20,1000,100,2000,1,1000,180,200,300,500,6000};
    for(unsigned i=0;i<n;++i)if(v[i]<low[i] || v[i]>high[i])return false;
    return v[3]==20 || v[3]==40 || v[3]==80;
}
bool signalReading(unsigned i){return (i>=11 && i<=20) || (i>=22 && i<=57);}
bool activeReading(unsigned i){return (i>=6 && i<=10) || i==21;}
void Meter::Reset(uint32_t rate){values.Reset(rate);mid=total=0;count=0;window=std::max(1u,(rate+9)/10);folded=ImagingMeter::invalid;}
bool Meter::Process(float left,float right) {
    values.Process(left,right);
    const double m=(double(left)+right)*.5;
    mid+=m*m;total+=(double(left)*left+double(right)*right)*.5;
    if(++count<window)return false;
    folded=mid>1.e-30 && total>1.e-30?static_cast<int32_t>(std::lround(1000*std::log10(mid/total))):ImagingMeter::invalid;
    count=0;mid=total=0;return true;
}
void State::Split::Configure(float lo,float hi,uint32_t rate){for(auto& x:low)x.Configure(lo,rate);for(auto& x:high)x.Configure(hi,rate);}
void State::Split::Reset(){for(auto& x:low)x.Reset();for(auto& x:high)x.Reset();}
std::array<std::array<float,2>,3> State::Split::Process(float left,float right) {
    std::array<std::array<float,2>,3> result{};
    for(unsigned c=0;c<2;++c){const float x=c?right:left;const float l=low[c].Process(x),h=high[c].Process(x);result[0][c]=l;result[1][c]=h-l;result[2][c]=x-h;}
    return result;
}
bool State::Configure(const Settings& next,uint32_t fs) {
    const bool changed=fs!=rate || next[19]!=settings[19] || next[20]!=settings[20];
    settings=next;rate=fs;
    const auto alpha=[&](double milliseconds){return float(-std::expm1(-1./(fs*milliseconds*.001)));};
    withdrawAlpha=alpha(settings[12]);recoverAlpha=alpha(settings[13]);
    transientAlpha=alpha(settings[17]/10.);releaseAlpha=alpha(settings[18]);fastRelease=alpha(3);slowAlpha=alpha(30);
    for(auto& split:filters)split.Configure(professional::frequency(settings[19],fs),professional::frequency(settings[20],fs),fs);
    if(changed)ClearMeters();
    return changed;
}
void State::ClearMeters(){for(auto& meter:global)meter.Reset(rate);for(auto& signal:bands)for(auto& meter:signal)meter.Reset(rate);for(auto& filter:filters)filter.Reset();guardActive=false;}
void State::Reset(){retention=1;protection=fast=slow=0;actualMix=1000;actualTransient=0;transientDetected=guardActive=false;ClearMeters();}
float State::Input(float left,float right,bool pro) {
    const float peak=std::max(std::abs(left),std::abs(right));
    fast=std::max(peak,fast+fastRelease*(peak-fast));slow+=slowAlpha*(peak-slow);
    transientDetected=pro && settings[14] && fast>1.e-10f && fast>slow*std::pow(10.f,settings[16]/200.f);
    const float target=transientDetected?settings[15]/1000.f:0.f;
    protection=professional::advance(protection,target,target>protection?transientAlpha:releaseAlpha);
    const float retained=pro && settings[6] && guardActive?1-settings[11]/1000.f:1.f;
    retention=professional::advance(retention,retained,retained<retention?withdrawAlpha:recoverAlpha);
    return pro?protection:0.f;
}
void State::EvaluateGuard() {
    constexpr auto invalid=ImagingMeter::invalid;
    const int32_t base=global[0].values.Get(0),candidate=global[1].values.Get(0);
    const int32_t foldedBase=global[0].Fold(),foldedCandidate=global[1].Fold();
    const bool corrValid=base!=invalid && candidate!=invalid;
    const bool foldBaseValid=foldedBase!=invalid;
    const bool foldCandidateSilent=global[1].values.Get(1)==invalid && global[1].values.Get(2)==invalid;
    // A zero-Mid, non-silent candidate is negative infinity, not an invalid quiet window.
    const bool foldValid=foldBaseValid && (foldedCandidate!=invalid || !foldCandidateSilent);
    const bool corrBad=corrValid && candidate<settings[8]*100 && candidate<base-100;
    const bool foldBad=foldValid && (foldedCandidate==invalid || foldedCandidate<foldedBase-settings[9]*10);
    const bool corrRecovered=!corrValid || candidate>=settings[8]*100+settings[10]*100 || candidate>=base-100;
    const bool foldRecovered=!foldValid || (foldedCandidate!=invalid && foldedCandidate>=foldedBase-std::max(0,settings[9]*10-50));
    const bool useCorr=settings[7]!=1,useFold=settings[7]!=0;
    if(!guardActive)guardActive=(useCorr && corrBad) || (useFold && foldBad);
    else if((!useCorr || corrRecovered) && (!useFold || foldRecovered))guardActive=false;
}
void State::Process(float bl,float br,float cl,float cr,float ol,float oright,bool pro) {
    const bool ready=global[0].Process(bl,br);global[1].Process(cl,cr);
    const std::array<std::array<float,2>,3> signals{{{bl,br},{cl,cr},{ol,oright}}};
    for(unsigned signal=0;signal<3;++signal){const auto split=filters[signal].Process(signals[signal][0],signals[signal][1]);for(unsigned band=0;band<3;++band)bands[signal][band].Process(split[band][0],split[band][1]);}
    if(ready){if(pro && settings[6])EvaluateGuard();else guardActive=false;}
}
void State::SetActualMix(float value,float transient){actualMix=std::lround(std::clamp(value,0.f,1.f)*1000);actualTransient=std::lround(std::clamp(transient,0.f,1.f)*1000);}
int32_t State::Get(unsigned i) const {
    if(i==6)return actualMix;
    if(i==7)return std::lround((1-retention)*1000);
    if(i==8)return actualTransient;
    if(i==9)return guardActive?1:0;
    if(i==10)return transientDetected?1:0;
    if(i==11 || i==12)return global[i-11].values.Get(0);
    if(i==13 || i==14)return global[i-13].Fold();
    if(i>=15 && i<=18)return global[(i-15)/2].values.Get(1+(i-15)%2);
    if(i==19 || i==20)return global[i-19].values.Get(3);
    if(i==21)return global[0].values.Get(4);
    if(i>=22 && i<=57){const unsigned offset=i-22,band=offset/12,signal=offset%12/4,field=offset%4;const auto& meter=bands[signal][band];return field==0?meter.values.Get(0):field==1?meter.Fold():meter.values.Get(field-1);}
    if(i==58 || i==59)return std::lround(professional::frequency(settings[19+i-58],rate));
    return 0;
}
}

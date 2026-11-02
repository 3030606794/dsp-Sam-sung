#include "ImagingAdvanced.h"
#include "ProfessionalTuning.h"
#include <cmath>

namespace {
float timeAlpha(uint32_t fs,float ms){return -std::expm1(-1.f/(fs*ms*.001f));}
int32_t scaled(float value,float scale){return static_cast<int32_t>(std::lround(value*scale));}
double controlStep(double current,double target,double coefficient) {
    const double next=current+coefficient*(target-current);
    return std::abs(next-target)<1.e-9?target:next;
}
}
ImagingAdvanced::ImagingAdvanced(){Reset(48000);}
void ImagingAdvanced::SidePath::Reset(){lo.Reset();hi.Reset();focus.Reset();lastLo=lastHi=lastFocus=-1;}
float ImagingAdvanced::SidePath::Process(float side,float width,const Controls& c,uint32_t fs) {
    if(lastLo!=c.bandLow){lastLo=c.bandLow;lo.Configure(lastLo,fs);}
    if(lastHi!=c.bandHigh){lastHi=c.bandHigh;hi.Configure(lastHi,fs);}
    if(lastFocus!=c.focusHz){lastFocus=c.focusHz;focus.Configure(lastFocus,fs);}
    const float band=static_cast<float>(hi.Process(side-lo.Process(side)))*c.bandAvailability;
    const float selected=c.bandMix==0?side:side+c.bandMix*(band-side);
    float result=side+(width-1)*selected;
    result+=c.focusMix*(c.focusWidth-1)*static_cast<float>(focus.Process(result));
    return result;
}
void ImagingAdvanced::UpdateTargets() {
    dynAttack=timeAlpha(rate,dynamic[4]);dynRelease=timeAlpha(rate,dynamic[5]);
    guardWithdraw=timeAlpha(rate,guard[3]);guardRecover=timeAlpha(rate,guard[4]);
    transientRecover=timeAlpha(rate,transient[3]);
    transientPowerRatio=std::pow(10.,transient[2]/100.);
    const unsigned stages=space[4];
    for(unsigned i=0;i<4;++i) {
        const float position=stages==1?0.f:(float(std::min(i,stages-1))/(stages-1)-.5f);
        targetFrequencies[i]=professional::frequency(space[5]*std::pow(2.f,position*space[6]/10.f),rate);
    }
}
bool ImagingAdvanced::Set(uint32_t id,const int32_t* values,size_t count) {
    if(!professional::validImagingAdvanced(id,values,count))return false;
    if(id==65627)std::copy_n(values,6,dynamic.begin());
    if(id==65628)std::copy_n(values,6,guard.begin());
    if(id==65629)std::copy_n(values,4,transient.begin());
    if(id==65630)std::copy_n(values,8,space.begin());
    UpdateTargets();return true;
}
void ImagingAdvanced::ClearRuntime() {
    inputMeter.Reset(rate);baselineMeter.Reset(rate);candidateMeter.Reset(rate);
    sidePower=fastPower=slowPower=foldMid=foldStereo=0;count=0;
    guardAmount=transientAmount=reduction=0;guardTriggered=false;monoFold=ImagingMeter::invalid;
    windowControls.fill(0);
}
void ImagingAdvanced::Reset(uint32_t fs) {
    rate=fs;alpha=professional::alpha(rate);filterAlpha=professional::alpha(rate,16);
    // A 100:1 power-envelope time-constant ratio reaches about 20 dB on an
    // onset, so every exposed threshold (3..18 dB) can actually trigger.
    rmsAlpha=timeAlpha(rate,10);fastAlpha=timeAlpha(rate,1);slowAlpha=timeAlpha(rate,100);
    window=std::max(1u,(rate+9)/10);tick=write=0;
    delayRing.fill(0);delayFrom=delayTo=rate*space[1]/100000.f;delayFade=1;
    delayMix=space[0]?space[2]/1000.f:0;diffusionMix=space[3]?space[7]/1000.f:0;
    stageMix={space[4]==1?1.f:0.f,space[4]==2?1.f:0.f,space[4]==4?1.f:0.f};
    dynamicThreshold=dynamic[1]/10.f;dynamicRatio=dynamic[2]/10.f;dynamicMaximum=dynamic[3]/10.f;
    UpdateTargets();frequencies=targetFrequencies;
    for(unsigned i=0;i<4;++i){const double k=std::tan(3.14159265358979323846*frequencies[i]/rate);allpass[i].a=(k-1)/(k+1);allpass[i].state=0;}
    candidatePath.Reset();baselinePath.Reset();ClearRuntime();
}
float ImagingAdvanced::ReadDelay(float frames) const {
    const unsigned integer=static_cast<unsigned>(frames);
    const float fraction=frames-integer;
    const float recent=delayRing[(write+1024-integer)%1024];
    return recent+fraction*(delayRing[(write+1023-integer)%1024]-recent);
}
float ImagingAdvanced::Space(float side) {
    delayRing[write]=side;
    const float requested=rate*space[1]/100000.f;
    // Two stationary read taps: changing delay never sweeps a tap through the
    // waveform. A further edit is queued until the current 15 ms fade ends.
    if(delayFade>=1 && requested!=delayTo){delayFrom=delayTo;delayTo=requested;delayFade=0;}
    const float delayed=ReadDelay(delayFrom)+delayFade*(ReadDelay(delayTo)-ReadDelay(delayFrom));
    if(delayFade<1)delayFade=std::min(1.f,delayFade+1.f/(rate*.015f));
    write=(write+1)%1024;
    delayMix=professional::advance(delayMix,space[0]?space[2]/1000.f:0.f,alpha);
    diffusionMix=professional::advance(diffusionMix,space[3]?space[7]/1000.f:0.f,alpha);
    const float delayedMix=side+delayMix*(delayed-side);
    if(tick%16==0)for(unsigned i=0;i<4;++i) {
        const float next=professional::advance(frequencies[i],targetFrequencies[i],filterAlpha);
        if(next!=frequencies[i]){frequencies[i]=next;const double k=std::tan(3.14159265358979323846*next/rate);allpass[i].a=(k-1)/(k+1);}
    }
    for(unsigned i=0;i<3;++i)stageMix[i]=professional::advance(stageMix[i],space[4]==(1u<<i)?1.f:0.f,alpha);
    double sample=delayedMix;float diffused=0;
    for(unsigned i=0;i<4;++i){sample=allpass[i].Process(sample);if(i==0)diffused+=stageMix[0]*sample;if(i==1)diffused+=stageMix[1]*sample;if(i==3)diffused+=stageMix[2]*sample;}
    return delayedMix+diffusionMix*(diffused-delayedMix);
}
bool ImagingAdvanced::Bypassed() const {
    return !dynamic[0] && !guard[0] && !transient[0] && !space[0] && !space[3] &&
        delayMix==0 && diffusionMix==0 && guardAmount==0 && transientAmount==0 && reduction==0;
}
void ImagingAdvanced::Process(float inputL,float inputR,float depthSide,float refL,float refR,
                             const Controls& c,float& outL,float& outR) {
    const float mid=(refL+refR)*.5f/c.output;
    const float candidate=candidatePath.Process(Space(depthSide),c.width,c,rate);
    const float baseline=baselinePath.Process(depthSide,std::min(c.width,1.f),c,rate);
    inputMeter.Process(inputL,inputR);
    baselineMeter.Process(mid+baseline,mid-baseline);
    candidateMeter.Process(mid+candidate,mid-candidate);
    if(++count==window) {
        const int32_t observed=candidateMeter.Get(0),reference=baselineMeter.Get(0);
        if(observed==ImagingMeter::invalid || reference==ImagingMeter::invalid)guardTriggered=false;
        else {
            const float correlation=observed/10000.f,base=reference/10000.f;
            const float threshold=guard[1]/100.f,hysteresis=guard[2]/100.f;
            if(correlation<threshold && correlation<base-hysteresis)guardTriggered=true;
            else if(correlation>threshold+hysteresis || correlation>=base-hysteresis*.5f)guardTriggered=false;
        }
    }
    const float guardTarget=guard[0] && guardTriggered?guard[5]/1000.f:0.f;
    guardAmount=controlStep(guardAmount,guardTarget,!guard[0]?alpha:guardTarget>guardAmount?guardWithdraw:guardRecover);
    const double power=(double(inputL)*inputL+double(inputR)*inputR)*.5;
    fastPower+=fastAlpha*(power-fastPower);slowPower+=slowAlpha*(power-slowPower);
    const bool attack=fastPower>1.e-20 && fastPower>slowPower*transientPowerRatio;
    const float transientTarget=transient[0] && attack?transient[1]/1000.f:0.f;
    transientAmount=controlStep(transientAmount,transientTarget,!transient[0]?alpha:transientTarget>transientAmount?fastAlpha:transientRecover);
    const float withdrawal=1-(1-guardAmount)*(1-transientAmount);
    float side=candidate+withdrawal*(baseline-candidate);
    sidePower+=rmsAlpha*(double(side)*side-sidePower);
    dynamicThreshold=professional::advance(dynamicThreshold,dynamic[1]/10.f,alpha);
    dynamicRatio=professional::advance(dynamicRatio,dynamic[2]/10.f,alpha);
    dynamicMaximum=professional::advance(dynamicMaximum,dynamic[3]/10.f,alpha);
    const float level=dynamic[0] && sidePower>1.e-30?static_cast<float>(10*std::log10(sidePower)):-300.f;
    const float desired=dynamic[0]?std::clamp((level-dynamicThreshold)*(1-1/dynamicRatio),0.f,dynamicMaximum):0.f;
    reduction=controlStep(reduction,desired,!dynamic[0]?alpha:desired>reduction?dynAttack:dynRelease);
    if(reduction!=0)side*=professional::amplitude(-reduction);
    // Exact first-round bypass is intentional: even harmless M/S roundoff
    // would otherwise change archived presets while every new switch is off.
    if(Bypassed()){outL=refL;outR=refR;}else{outL=(mid+side)*c.output;outR=(mid-side)*c.output;}
    const double outMid=(double(outL)+outR)*.5;
    foldMid+=outMid*outMid;foldStereo+=(double(outL)*outL+double(outR)*outR)*.5;
    if(count==window) {
        monoFold=foldMid>1.e-30 && foldStereo>1.e-30?static_cast<int32_t>(std::lround(1000*std::log10(foldMid/foldStereo))):ImagingMeter::invalid;
        windowControls={scaled(reduction,100),scaled(guardAmount,1000),scaled(transientAmount,1000)};
        count=0;foldMid=foldStereo=0;
    }
    ++tick;
}
int32_t ImagingAdvanced::Get(unsigned index) const {
    if(index==0)return inputMeter.Get(0);
    if(index>=1 && index<=3)return windowControls[index-1];
    if(index==4)return space[1]*10;
    if(index==5)return scaled(rate*space[1]/100000.f,1000);
    if(index>=6 && index<=8) {
        const float low=targetFrequencies[0],high=targetFrequencies[space[4]-1];
        return static_cast<int32_t>(index==6?std::sqrt(low*high):index==7?low:high);
    }
    if(index==9 || index==10)return inputMeter.Get(index-8);
    if(index==11)return monoFold;
    if(index==12)return baselineMeter.Get(0);
    return index==13?inputMeter.Get(4):0;
}

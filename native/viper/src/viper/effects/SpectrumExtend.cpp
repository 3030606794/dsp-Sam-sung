#include "SpectrumExtend.h"
#include "../constants.h"
#include "../utils/ProfessionalTuning.h"

SpectrumExtend::SpectrumExtend() {
    samplingRate=VIPER_DEFAULT_SAMPLING_RATE;referenceFreq=7600;enabled=false;exciter=0.f;
    Reset();
}
float SpectrumExtend::EffectiveLowpass() const {
    return lowpassFrequency==0 ? samplingRate*.5f-2000.f : professional::frequency(static_cast<float>(lowpassFrequency),samplingRate);
}
void SpectrumExtend::AdvanceFilters() {
    if(tuningTick++%16) return;
    const float hp=professional::frequency(static_cast<float>(referenceFreq),samplingRate),lp=EffectiveLowpass();
    if(currentHP==hp && currentLP==lp && currentHPQ==hpQ && currentLPQ==lpQ) return;
    currentHP=professional::advance(currentHP,hp,filterSmoothing);
    currentLP=professional::advance(currentLP,lp,filterSmoothing);
    currentHPQ=professional::advance(currentHPQ,hpQ,filterSmoothing);
    currentLPQ=professional::advance(currentLPQ,lpQ,filterSmoothing);
    for(unsigned channel=0;channel<2;++channel) {
        highpass[channel].RefreshFilter(MultiBiquad::HIGH_PASS,0,currentHP,samplingRate,currentHPQ,false,false);
        lowpass[channel].RefreshFilter(MultiBiquad::LOW_PASS,0,currentLP,samplingRate,currentLPQ,false,false);
    }
}
void SpectrumExtend::Process(float* samples,uint32_t size) {
    if(!enabled) return;
    for(uint32_t frame=0;frame<size;++frame) {
        AdvanceFilters();
        currentExciter=professional::advance(currentExciter,exciter,smoothing);
        currentOutput=professional::advance(currentOutput,outputTarget,smoothing);
        currentAudition=professional::advance(currentAudition,audition?1.f:0.f,smoothing);
        drive=professional::advance(drive,driveTarget,smoothing);even=professional::advance(even,evenTarget,smoothing);
        highOrder=professional::advance(highOrder,highTarget,smoothing);
        const float dry[2]={samples[2*frame],samples[2*frame+1]};float alignedDry[2];
        const double input[2]={highpass[0].ProcessSample(dry[0]),highpass[1].ProcessSample(dry[1])};double shaped[2];
        ProcessNonlinear(input,dry,shaped,alignedDry);
        for(unsigned channel=0;channel<2;++channel) {
            const double wet=lowpass[channel].ProcessSample(shaped[channel]*currentExciter);
            samples[2*frame+channel]=((1.f-currentAudition)*alignedDry[channel]+static_cast<float>(wet))*currentOutput;
        }
    }
}
void SpectrumExtend::Reset() {
    ClearAudition();
    currentExciter=exciter;currentOutput=outputTarget;
    currentHP=professional::frequency(static_cast<float>(referenceFreq),samplingRate);
    currentLP=EffectiveLowpass();currentHPQ=hpQ;currentLPQ=lpQ;
    smoothing=professional::alpha(samplingRate);filterSmoothing=professional::alpha(samplingRate,16);tuningTick=0;
    drive=driveTarget;even=evenTarget;highOrder=highTarget;
    activePath=targetPath;candidatePath=-1;warmFrames=0;pathMix=0;pathStep=1.f/(.015f*samplingRate);
    dryHistory={};dryPosition=0;
    for(auto& path:paths)path.Reset(even,highOrder);
    for(unsigned channel=0;channel<2;++channel) {
        highpass[channel].RefreshFilter(MultiBiquad::HIGH_PASS,0,currentHP,samplingRate,currentHPQ,false);
        lowpass[channel].RefreshFilter(MultiBiquad::LOW_PASS,0,currentLP,samplingRate,currentLPQ,false);
    }
}
void SpectrumExtend::ClearAudition() {audition=false;currentAudition=0.f;}
void SpectrumExtend::SetEnable(bool enable) {
    if(!enable) ClearAudition();
    if(enabled!=enable) {if(enable) Reset();enabled=enable;}
}
void SpectrumExtend::SetExciter(float value) {
    if(std::isfinite(value) && value>=0.f) exciter=value;
}
void SpectrumExtend::SetReferenceFrequency(uint32_t freq) {referenceFreq=freq;}
void SpectrumExtend::SetSamplingRate(uint32_t rate) {
    if(samplingRate!=rate) {samplingRate=rate;Reset();}
}
bool SpectrumExtend::SetAdvanced(const int32_t* values,size_t count) {
    if(!professional::validSpectrum(values,count)) return false;
    exciter=values[0]/1000.f;referenceFreq=values[1];hpQ=values[2]/1000.f;
    lowpassFrequency=values[3];lpQ=values[4]/1000.f;
    outputTarget=professional::amplitude(values[5]/10.f);
    // A lifecycle cancellation must not be undone by an old UI packet still carrying 1.
    // Require a fresh 0 -> 1 request edge, while ordinary repeated parameter sync stays stable.
    const bool request=values[6]!=0;
    if(!request) audition=false;
    else if(!lastAuditionRequest && enabled) audition=true;
    lastAuditionRequest=request;
    return true;
}
int32_t SpectrumExtend::GetEffective(unsigned index) const {
    if(index==0) return static_cast<int32_t>(professional::frequency(static_cast<float>(referenceFreq),samplingRate));
    if(index==1) return static_cast<int32_t>(EffectiveLowpass());
    if(index==2) return audition?1:0;
    return 0;
}
bool SpectrumExtend::SetNonlinear(const int32_t* values,size_t count) {
    if(!professional::validNonlinear(values,count))return false;
    std::copy_n(values,4,nonlinearSettings.begin());
    driveTarget=professional::amplitude(values[0]/10.f);evenTarget=values[1]/100.f;highTarget=values[2]/100.f;
    targetPath=values[0]==0 && values[1]==0 && values[2]==100 && values[3]==1?0:values[3]==1?1:values[3]==2?2:3;
    return true;
}
int32_t SpectrumExtend::GetNonlinear(unsigned index) const {
    if(!enabled)return 0;
    return index==0?static_cast<int32_t>(paths[activePath].Factor()):index==1?static_cast<int32_t>(paths[activePath].Latency()):0;
}
void SpectrumExtend::ProcessNonlinear(const double input[2],const float dry[2],double wet[2],float alignedDry[2]) {
    dryHistory[dryPosition]={dry[0],dry[1]};
    const auto& delayed=dryHistory[(dryPosition+dryHistory.size()-32)%dryHistory.size()];
    if(candidatePath<0 && targetPath!=activePath) {
        candidatePath=static_cast<int>(targetPath);paths[candidatePath].Reset(even,highOrder);
        // The audible path continues while the new FIR and the original 200-host-frame
        // startup interval settle. Repeated parameter packets never restart this warmup.
        warmFrames=256;pathMix=0.f;
    }
    paths[activePath].Process(input,wet,drive,even,highOrder);
    const bool activeDelayed=paths[activePath].Latency()!=0;
    for(unsigned ch=0;ch<2;++ch)alignedDry[ch]=activeDelayed?delayed[ch]:dry[ch];
    if(candidatePath>=0) {
        double candidate[2];paths[candidatePath].Process(input,candidate,drive,even,highOrder);
        if(warmFrames) --warmFrames;
        else {
            pathMix=std::min(1.f,pathMix+pathStep);
            const bool nextDelayed=paths[candidatePath].Latency()!=0;
            for(unsigned ch=0;ch<2;++ch) {
                wet[ch]+=pathMix*(candidate[ch]-wet[ch]);
                alignedDry[ch]+=pathMix*((nextDelayed?delayed[ch]:dry[ch])-alignedDry[ch]);
            }
            if(pathMix>=1.f) {activePath=static_cast<unsigned>(candidatePath);candidatePath=-1;}
        }
    }
    dryPosition=(dryPosition+1)%dryHistory.size();
}

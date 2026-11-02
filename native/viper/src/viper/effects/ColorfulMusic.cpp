#include "ColorfulMusic.h"
#include "../constants.h"
#include "../utils/ProfessionalTuning.h"

ColorfulMusic::ColorfulMusic() {
    samplingRate=VIPER_DEFAULT_SAMPLING_RATE;enabled=false;
    stereo3dSurround.SetStereoWiden(0.f);depthSurround.SetStrength(0);Reset();
}
void ColorfulMusic::ConfigureFilters() {
    bandLowpass.Configure(bandLow,samplingRate);
    bandHighpass.Configure(bandHigh,samplingRate);
    focusLowpass.Configure(focusFrequency,samplingRate);
}
void ColorfulMusic::Advance() {
    modeMix=professional::advance(modeMix,settings[0]?1.f:0.f,alpha);
    width=professional::advance(width,settings[1]/1000.f,alpha);
    midGain=professional::advance(midGain,midTarget,alpha);
    bandMix=professional::advance(bandMix,settings[3]?1.f:0.f,alpha);
    bandAvailability=professional::advance(bandAvailability,GetImaging(0)?1.f:0.f,alpha);
    focusMix=professional::advance(focusMix,settings[6]?1.f:0.f,alpha);
    focusWidth=professional::advance(focusWidth,settings[8]/1000.f,alpha);
    depthMix=professional::advance(depthMix,settings[9]?settings[10]/1000.f:0.f,alpha);
    output=professional::advance(output,outputTarget,alpha);
    auditionMid=professional::advance(auditionMid,audition==1 || audition==2?1.f:0.f,alpha);
    auditionSide=professional::advance(auditionSide,audition==3?1.f:0.f,alpha);
    differenceMix=professional::advance(differenceMix,differenceAudition?1.f:0.f,alpha);
    const float nextWiden=professional::advance(legacyWiden,widenTarget,alpha);
    const float nextMiddle=professional::advance(legacyMiddle,middleTarget,alpha);
    if(nextWiden!=legacyWiden){legacyWiden=nextWiden;stereo3dSurround.SetStereoWiden(legacyWiden);}
    if(nextMiddle!=legacyMiddle){legacyMiddle=nextMiddle;stereo3dSurround.SetMiddleImage(legacyMiddle);}
    if(tick++%16==0) {
        const float lo=professional::advance(bandLow,professional::frequency(settings[4],samplingRate),filterAlpha);
        const float hi=professional::advance(bandHigh,professional::frequency(settings[5],samplingRate),filterAlpha);
        const float focus=professional::advance(focusFrequency,professional::frequency(settings[7],samplingRate),filterAlpha);
        if(lo!=bandLow || hi!=bandHigh || focus!=focusFrequency){bandLow=lo;bandHigh=hi;focusFrequency=focus;ConfigureFilters();}
    }
}
void ColorfulMusic::Process(float* samples,uint32_t size) {
    if(!enabled)return;
    for(uint32_t frame=0;frame<size;++frame) {
        Advance();
        const float dry[2]={samples[2*frame],samples[2*frame+1]};
        float wet[2]={dry[0],dry[1]};depthSurround.Process(wet,1);
        if(depthMix!=1.f)for(unsigned ch=0;ch<2;++ch)wet[ch]=depthMix==0.f?dry[ch]:dry[ch]+depthMix*(wet[ch]-dry[ch]);
        const float mid=(wet[0]+wet[1])*.5f,side=(wet[0]-wet[1])*.5f;
        // HP=x-LP gives an exactly complementary split. These one-pole
        // branches have bounded, monotonic width response without a fixed delay.
        const double hp=side-bandLowpass.Process(side);
        const float band=static_cast<float>(bandHighpass.Process(hp))*bandAvailability;
        const float selected=bandMix==0.f?side:side+bandMix*(band-side);
        float legacy[2]={wet[0],wet[1]};stereo3dSurround.Process(legacy,1);
        if(midGain!=1.f) {
            const float adjustment=(midGain-1.f)*((legacy[0]+legacy[1])*.5f);
            legacy[0]+=adjustment;legacy[1]+=adjustment;
        }
        if(bandMix!=0.f) {
            const float legacySideGain=2.f*(legacyWiden+1.f)/(legacyWiden+2.f);
            const float adjustment=(legacySideGain-1.f)*bandMix*(band-side);
            legacy[0]+=adjustment;legacy[1]-=adjustment;
        }
        const float proMid=midGain*mid,proSide=side+(width-1.f)*selected;
        const float pro[2]={midGain==1.f && width==1.f?wet[0]:proMid+proSide,midGain==1.f && width==1.f?wet[1]:proMid-proSide};
        float result[2];
        for(unsigned ch=0;ch<2;++ch)result[ch]=modeMix==0.f?legacy[ch]:modeMix==1.f?pro[ch]:legacy[ch]+modeMix*(pro[ch]-legacy[ch]);
        const float finalSide=(result[0]-result[1])*.5f;
        const float lowSide=static_cast<float>(focusLowpass.Process(finalSide));
        const float focusAdjustment=focusMix*(focusWidth-1.f)*lowSide;
        if(focusAdjustment!=0.f){result[0]+=focusAdjustment;result[1]-=focusAdjustment;}
        if(output!=1.f){result[0]*=output;result[1]*=output;}
        const float reference[2]={result[0],result[1]};
        const float legacySideGain=2.f*(legacyWiden+1.f)/(legacyWiden+2.f);
        const ImagingAdvanced::Controls controls{
            legacySideGain+modeMix*(width-legacySideGain),bandLow,bandHigh,bandMix,bandAvailability,
            focusFrequency,focusMix,focusWidth,output};
        advanced.Process(dry[0],dry[1],side,reference[0],reference[1],controls,result[0],result[1]);
        meter.Process(result[0],result[1]); // Inspect normal stereo, before temporary listening modes.
        const float difference[2]={result[0]-reference[0],result[1]-reference[1]};
        if(auditionMid!=0.f || auditionSide!=0.f) {
            const float m=(result[0]+result[1])*.5f,s=(result[0]-result[1])*.5f;
            const float normal=1.f-auditionMid-auditionSide;
            result[0]=normal*result[0]+auditionMid*m+auditionSide*s;
            result[1]=normal*result[1]+auditionMid*m-auditionSide*s;
        }
        if(differenceMix!=0.f)for(unsigned ch=0;ch<2;++ch)
            result[ch]=differenceMix==1.f?difference[ch]:result[ch]+differenceMix*(difference[ch]-result[ch]);
        samples[2*frame]=result[0];samples[2*frame+1]=result[1];
    }
}
void ColorfulMusic::ClearImagingRuntime() {
    audition=0;auditionMid=auditionSide=0;differenceAudition=false;differenceMix=0;
    meter.Reset(samplingRate);advanced.ClearRuntime();
}
void ColorfulMusic::Reset() {
    depthSurround.SetSamplingRate(samplingRate);
    alpha=professional::alpha(samplingRate);filterAlpha=professional::alpha(samplingRate,16);tick=0;
    modeMix=settings[0]?1.f:0.f;width=settings[1]/1000.f;midGain=midTarget;
    bandMix=settings[3]?1.f:0.f;focusMix=settings[6]?1.f:0.f;focusWidth=settings[8]/1000.f;
    depthMix=settings[9]?settings[10]/1000.f:0.f;output=outputTarget;
    legacyWiden=widenTarget;legacyMiddle=middleTarget;
    stereo3dSurround.SetStereoWiden(legacyWiden);stereo3dSurround.SetMiddleImage(legacyMiddle);
    bandLow=professional::frequency(settings[4],samplingRate);bandHigh=professional::frequency(settings[5],samplingRate);
    focusFrequency=professional::frequency(settings[7],samplingRate);bandAvailability=bandLow<bandHigh?1.f:0.f;
    ConfigureFilters();bandLowpass.Reset();bandHighpass.Reset();focusLowpass.Reset();advanced.Reset(samplingRate);ClearImagingRuntime();
}
void ColorfulMusic::SetDepthValue(short value) {depthSurround.SetStrength(value);}
void ColorfulMusic::SetEnable(bool value) {
    if(!value)ClearImagingRuntime();
    if(enabled!=value){if(value)Reset();enabled=value;}
}
void ColorfulMusic::SetMidImageValue(float value) {if(std::isfinite(value)&&value>=0.f)middleTarget=value;}
void ColorfulMusic::SetWidenValue(float value) {if(std::isfinite(value)&&value>=0.f)widenTarget=value;}
void ColorfulMusic::SetSamplingRate(uint32_t value) {if(samplingRate!=value){samplingRate=value;Reset();}}
bool ColorfulMusic::SetAdvanced(const int32_t* values,size_t count) {
    if(!professional::validImaging(values,count))return false;
    std::copy_n(values,13,settings.begin());midTarget=professional::amplitude(values[2]/10.f);outputTarget=professional::amplitude(values[11]/10.f);
    const int32_t request=values[12];
    if(request==0)audition=0;
    else if(request!=lastAuditionRequest && enabled){audition=request;differenceAudition=false;}
    lastAuditionRequest=request;
    return true;
}
int32_t ColorfulMusic::GetImaging(unsigned index) const {
    if(index<2) {
        const float lo=professional::frequency(settings[4],samplingRate),hi=professional::frequency(settings[5],samplingRate);
        return lo<hi?static_cast<int32_t>(index?hi:lo):0;
    }
    if(index==2)return static_cast<int32_t>(professional::frequency(settings[7],samplingRate));
    if(index<7)return enabled?meter.Get(index-3):ImagingMeter::invalid;
    if(index==7)return enabled?(differenceAudition?4:audition):0;
    return enabled?meter.Get(4):0;
}
bool ColorfulMusic::SetAdvancedProcessing(uint32_t id,const int32_t* values,size_t count) {return advanced.Set(id,values,count);}
void ColorfulMusic::SetDifferenceAudition(bool request) {
    if(!request)differenceAudition=false;
    else if(!lastDifferenceRequest && enabled){differenceAudition=true;audition=0;}
    lastDifferenceRequest=request;
}
int32_t ColorfulMusic::GetAdvancedImaging(unsigned index) const {
    if(enabled || (index>=4 && index<=8))return advanced.Get(index);
    return index==0 || (index>=9 && index<=12)?ImagingMeter::invalid:0;
}

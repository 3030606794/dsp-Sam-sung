#pragma once
// Frozen imager1 production reference, not the current implementation.
// Source commit: 5396af58e6c7cd9ee345e8900276a56309998f9d
// Transformations only: distinct class/namespace names, include paths and inline.
// Legacy DepthSurround/Stereo3DSurround and their Biquad/TimeConstDelay dependencies
// remain shared; their implementation is required to remain unchanged this round.
// Original git blob SHA-256 values (before line-ending normalization):
// effects/ColorfulMusic.h: 618a18964de9df9ed98886309a298ef50194ec729c9036354342e4ec0014ebb9
// effects/ColorfulMusic.cpp: baabc69daf7ae3ea458337a6183cef0be4d66b9b04aa2ecc004c84e2c9f92ce2
// utils/ImagingControls.h: dbb229fd0db3009872057098b589d9ee9c15bdbdb105a8da8f24fbda7f0f56bc
// utils/ImagingControls.cpp: 055fb8a466b493d5a3ccc20a4544d2a49dedf2f2604b5f7d9332afcf42dfdef3
// utils/ProfessionalTuning.h: 640523b661be1a9aeb8f90e549df645268e86d591de90a96d180698f5043bf87

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace frozen_imager1 {
inline bool validImaging(const int32_t* v,size_t count) {
    const int32_t lo[13]={0,0,-120,0,100,2000,0,40,0,0,0,-120,0};
    const int32_t hi[13]={1,2000,60,1,2000,20000,1,500,1000,1,1000,0,3};
    if(!v || count!=13)return false;
    for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return v[4]<v[5];
}
inline bool validSelective(const int32_t* v,size_t count) {
    return v && count==4 && v[0]>=0 && v[0]<=1 && v[1]>=1000 && v[1]<=12000 &&
        v[2]>=2000 && v[2]<=20000 && v[1]<v[2] && v[3]>=300 && v[3]<=1500;
}
inline bool validNonlinear(const int32_t* v,size_t count) {
    return v && count==4 && v[0]>=-120 && v[0]<=180 && v[1]>=0 && v[1]<=100 &&
        v[2]>=0 && v[2]<=100 && (v[3]==1 || v[3]==2 || v[3]==4);
}
inline bool validDynamic(const int32_t* v,size_t count) {
    const int32_t lo[8]={0,1000,300,-600,10,0,1,5},hi[8]={1,16000,1500,0,200,180,1000,2000};
    if(!v || count!=8) return false;
    for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return true;
}
inline bool validDeess(const int32_t* v,size_t count) {
    const int32_t lo[9]={0,2000,3000,300,-600,10,0,1,5},hi[9]={1,12000,20000,1500,0,200,180,100,500};
    if(!v || count!=9) return false;
    for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return v[1]<v[2];
}
inline bool validClarity(const int32_t* values, size_t count) {
    const int32_t lo[11]={0,2000,25,60,500,-120,-120,-120,-120,0,0};
    const int32_t hi[11]={450,16000,100,400,5000,60,60,60,0,10000,5000};
    if(!values || count!=11) return false;
    for(size_t i=0;i<count;++i) if(values[i]<lo[i] || values[i]>hi[i]) return false;
    return true;
}
inline bool validSpectrum(const int32_t* values, size_t count) {
    const int32_t lo[7]={0,1000,300,0,300,-120,0};
    const int32_t hi[7]={5600,16000,1500,24000,1500,0,1};
    if(!values || count!=7) return false;
    for(size_t i=0;i<count;++i) if(values[i]<lo[i] || values[i]>hi[i]) return false;
    return values[3]==0 || values[3]>=8000;
}
inline float frequency(float request, uint32_t rate) {
    return std::clamp(request,1.f,rate*.5f-100.f);
}
inline float alpha(uint32_t rate, unsigned samples=1) {
    return -std::expm1(-static_cast<float>(samples)/(.015f*rate));
}
inline float advance(float current,float target,float smoothing) {
    const float next=current+smoothing*(target-current);
    return next==current || std::abs(target-next)<1.e-6f ? target : next;
}
inline float amplitude(float db) { return std::pow(10.f,db/20.f); }
inline unsigned delayFrames(uint32_t rate,uint32_t microseconds) {
    return static_cast<unsigned>(uint64_t(rate)*microseconds/1000000u);
}
}

#include <array>
#include <cstdint>
#include <limits>

// Bilinear one-pole LP. Its complementary HP is exactly x-LP(x).
class ImagingFirstRoundLowpass {
public:
    void Configure(float hz,uint32_t rate);
    void Reset(){state=0;}
    double Process(double sample);
private:
    double b=.5,a=0,state=0;
};

class ImagingFirstRoundMeter {
public:
    static constexpr int32_t invalid=std::numeric_limits<int32_t>::min();
    void Reset(uint32_t rate);
    void Process(float left,float right);
    int32_t Get(unsigned index) const;
private:
    uint32_t window=4800,count=0;
    double meanL=0,meanR=0,varL=0,varR=0,cov=0,midEnergy=0,sideEnergy=0,peak=0;
    std::array<int32_t,4> result{invalid,invalid,invalid,invalid};
    bool ready=false;
    void ClearWindow();
};

#include <algorithm>
#include <cmath>

inline void ImagingFirstRoundLowpass::Configure(float hz,uint32_t rate) {
    const double k=std::tan(3.14159265358979323846*hz/rate);
    b=k/(1+k);a=(k-1)/(k+1);
}
inline double ImagingFirstRoundLowpass::Process(double sample) {
    const double output=b*sample+state;
    state=b*sample-a*output;
    return output;
}
inline void ImagingFirstRoundMeter::ClearWindow() {
    count=0;meanL=meanR=varL=varR=cov=midEnergy=sideEnergy=peak=0;
}
inline void ImagingFirstRoundMeter::Reset(uint32_t rate) {
    window=std::max(1u,(rate+9)/10);ClearWindow();result.fill(invalid);ready=false;
}
inline void ImagingFirstRoundMeter::Process(float left,float right) {
    ++count;
    const double dl=left-meanL,dr=right-meanR;
    meanL+=dl/count;meanR+=dr/count;
    varL+=dl*(left-meanL);varR+=dr*(right-meanR);cov+=dl*(right-meanR);
    const double mid=(double(left)+right)*.5,side=(double(left)-right)*.5;
    midEnergy+=mid*mid;sideEnergy+=side*side;
    peak=std::max({peak,std::abs(double(left)),std::abs(double(right))});
    if(count<window)return;
    result[0]=varL>1.e-20 && varR>1.e-20?static_cast<int32_t>(std::lround(std::clamp(cov/std::sqrt(varL*varR),-1.,1.)*10000)):invalid;
    const auto level=[](double power){return power>1.e-30?static_cast<int32_t>(std::lround(1000*std::log10(power))):invalid;};
    result[1]=level(midEnergy/count);result[2]=level(sideEnergy/count);result[3]=level(peak*peak);
    ready=true;ClearWindow();
}
inline int32_t ImagingFirstRoundMeter::Get(unsigned index) const {return index<4?result[index]:ready?1:0;}


#include <cstdint>
#include <array>
#include <cstddef>
#include "../viper/src/viper/utils/Stereo3DSurround.h"
#include "../viper/src/viper/utils/DepthSurround.h"

class ImagingFirstRoundReference {
public:
    ImagingFirstRoundReference();

    void Process(float *samples, uint32_t size);
    void Reset();
    void SetDepthValue(short depthValue);
    void SetEnable(bool enable);
    void SetMidImageValue(float midImageValue);
    void SetSamplingRate(uint32_t samplingRate);
    void SetWidenValue(float widenValue);
    bool SetAdvanced(const int32_t* values,size_t count);
    int32_t GetImaging(unsigned index) const;
    void ClearImagingRuntime();

private:
    Stereo3DSurround stereo3dSurround;
    DepthSurround depthSurround;
    uint32_t samplingRate;
    bool enabled;
    std::array<int32_t,13> settings{0,1000,0,0,150,16000,0,120,1000,1,1000,0,0};
    float alpha=0,filterAlpha=0,modeMix=0,width=1,midGain=1,bandMix=0,focusMix=0,focusWidth=1,depthMix=1,output=1;
    float midTarget=1,outputTarget=1,legacyWiden=0,legacyMiddle=1,widenTarget=0,middleTarget=1;
    float bandLow=150,bandHigh=16000,focusFrequency=120,bandAvailability=1;
    float auditionMid=0,auditionSide=0;
    unsigned tick=0;int32_t audition=0,lastAuditionRequest=0;
    ImagingFirstRoundLowpass bandLowpass,bandHighpass,focusLowpass;
    ImagingFirstRoundMeter meter;
    void Advance();
    void ConfigureFilters();

};



#include "../viper/src/viper/constants.h"

inline ImagingFirstRoundReference::ImagingFirstRoundReference() {
    samplingRate=VIPER_DEFAULT_SAMPLING_RATE;enabled=false;
    stereo3dSurround.SetStereoWiden(0.f);depthSurround.SetStrength(0);Reset();
}
inline void ImagingFirstRoundReference::ConfigureFilters() {
    bandLowpass.Configure(bandLow,samplingRate);
    bandHighpass.Configure(bandHigh,samplingRate);
    focusLowpass.Configure(focusFrequency,samplingRate);
}
inline void ImagingFirstRoundReference::Advance() {
    modeMix=frozen_imager1::advance(modeMix,settings[0]?1.f:0.f,alpha);
    width=frozen_imager1::advance(width,settings[1]/1000.f,alpha);
    midGain=frozen_imager1::advance(midGain,midTarget,alpha);
    bandMix=frozen_imager1::advance(bandMix,settings[3]?1.f:0.f,alpha);
    bandAvailability=frozen_imager1::advance(bandAvailability,GetImaging(0)?1.f:0.f,alpha);
    focusMix=frozen_imager1::advance(focusMix,settings[6]?1.f:0.f,alpha);
    focusWidth=frozen_imager1::advance(focusWidth,settings[8]/1000.f,alpha);
    depthMix=frozen_imager1::advance(depthMix,settings[9]?settings[10]/1000.f:0.f,alpha);
    output=frozen_imager1::advance(output,outputTarget,alpha);
    auditionMid=frozen_imager1::advance(auditionMid,audition==1 || audition==2?1.f:0.f,alpha);
    auditionSide=frozen_imager1::advance(auditionSide,audition==3?1.f:0.f,alpha);
    const float nextWiden=frozen_imager1::advance(legacyWiden,widenTarget,alpha);
    const float nextMiddle=frozen_imager1::advance(legacyMiddle,middleTarget,alpha);
    if(nextWiden!=legacyWiden){legacyWiden=nextWiden;stereo3dSurround.SetStereoWiden(legacyWiden);}
    if(nextMiddle!=legacyMiddle){legacyMiddle=nextMiddle;stereo3dSurround.SetMiddleImage(legacyMiddle);}
    if(tick++%16==0) {
        const float lo=frozen_imager1::advance(bandLow,frozen_imager1::frequency(settings[4],samplingRate),filterAlpha);
        const float hi=frozen_imager1::advance(bandHigh,frozen_imager1::frequency(settings[5],samplingRate),filterAlpha);
        const float focus=frozen_imager1::advance(focusFrequency,frozen_imager1::frequency(settings[7],samplingRate),filterAlpha);
        if(lo!=bandLow || hi!=bandHigh || focus!=focusFrequency){bandLow=lo;bandHigh=hi;focusFrequency=focus;ConfigureFilters();}
    }
}
inline void ImagingFirstRoundReference::Process(float* samples,uint32_t size) {
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
        meter.Process(result[0],result[1]); // Inspect normal stereo, before temporary listening modes.
        if(auditionMid!=0.f || auditionSide!=0.f) {
            const float m=(result[0]+result[1])*.5f,s=(result[0]-result[1])*.5f;
            const float normal=1.f-auditionMid-auditionSide;
            result[0]=normal*result[0]+auditionMid*m+auditionSide*s;
            result[1]=normal*result[1]+auditionMid*m-auditionSide*s;
        }
        samples[2*frame]=result[0];samples[2*frame+1]=result[1];
    }
}
inline void ImagingFirstRoundReference::ClearImagingRuntime() {
    audition=0;auditionMid=auditionSide=0;meter.Reset(samplingRate);
}
inline void ImagingFirstRoundReference::Reset() {
    depthSurround.SetSamplingRate(samplingRate);
    alpha=frozen_imager1::alpha(samplingRate);filterAlpha=frozen_imager1::alpha(samplingRate,16);tick=0;
    modeMix=settings[0]?1.f:0.f;width=settings[1]/1000.f;midGain=midTarget;
    bandMix=settings[3]?1.f:0.f;focusMix=settings[6]?1.f:0.f;focusWidth=settings[8]/1000.f;
    depthMix=settings[9]?settings[10]/1000.f:0.f;output=outputTarget;
    legacyWiden=widenTarget;legacyMiddle=middleTarget;
    stereo3dSurround.SetStereoWiden(legacyWiden);stereo3dSurround.SetMiddleImage(legacyMiddle);
    bandLow=frozen_imager1::frequency(settings[4],samplingRate);bandHigh=frozen_imager1::frequency(settings[5],samplingRate);
    focusFrequency=frozen_imager1::frequency(settings[7],samplingRate);bandAvailability=bandLow<bandHigh?1.f:0.f;
    ConfigureFilters();bandLowpass.Reset();bandHighpass.Reset();focusLowpass.Reset();ClearImagingRuntime();
}
inline void ImagingFirstRoundReference::SetDepthValue(short value) {depthSurround.SetStrength(value);}
inline void ImagingFirstRoundReference::SetEnable(bool value) {
    if(!value)ClearImagingRuntime();
    if(enabled!=value){if(value)Reset();enabled=value;}
}
inline void ImagingFirstRoundReference::SetMidImageValue(float value) {if(std::isfinite(value)&&value>=0.f)middleTarget=value;}
inline void ImagingFirstRoundReference::SetWidenValue(float value) {if(std::isfinite(value)&&value>=0.f)widenTarget=value;}
inline void ImagingFirstRoundReference::SetSamplingRate(uint32_t value) {if(samplingRate!=value){samplingRate=value;Reset();}}
inline bool ImagingFirstRoundReference::SetAdvanced(const int32_t* values,size_t count) {
    if(!frozen_imager1::validImaging(values,count))return false;
    std::copy_n(values,13,settings.begin());midTarget=frozen_imager1::amplitude(values[2]/10.f);outputTarget=frozen_imager1::amplitude(values[11]/10.f);
    const int32_t request=values[12];
    if(request==0)audition=0;
    else if(request!=lastAuditionRequest && enabled)audition=request;
    lastAuditionRequest=request;
    return true;
}
inline int32_t ImagingFirstRoundReference::GetImaging(unsigned index) const {
    if(index<2) {
        const float lo=frozen_imager1::frequency(settings[4],samplingRate),hi=frozen_imager1::frequency(settings[5],samplingRate);
        return lo<hi?static_cast<int32_t>(index?hi:lo):0;
    }
    if(index==2)return static_cast<int32_t>(frozen_imager1::frequency(settings[7],samplingRate));
    if(index<7)return enabled?meter.Get(index-3):ImagingFirstRoundMeter::invalid;
    if(index==7)return enabled?audition:0;
    return enabled?meter.Get(4):0;
}

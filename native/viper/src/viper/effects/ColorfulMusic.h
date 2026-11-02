#pragma once

#include <cstdint>
#include <array>
#include <cstddef>
#include "../utils/Stereo3DSurround.h"
#include "../utils/DepthSurround.h"
#include "../utils/ImagingControls.h"
#include "../utils/ImagingAdvanced.h"

class ColorfulMusic {
public:
    ColorfulMusic();

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
    bool SetAdvancedProcessing(uint32_t id,const int32_t* values,size_t count);
    int32_t GetAdvancedImaging(unsigned index) const;
    void SetDifferenceAudition(bool request);

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
    float differenceMix=0;
    bool differenceAudition=false,lastDifferenceRequest=false;
    unsigned tick=0;int32_t audition=0,lastAuditionRequest=0;
    ImagingLowpass bandLowpass,bandHighpass,focusLowpass;
    ImagingMeter meter;
    ImagingAdvanced advanced;
    void Advance();
    void ConfigureFilters();

};



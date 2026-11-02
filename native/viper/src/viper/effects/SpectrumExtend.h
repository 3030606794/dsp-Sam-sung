#pragma once

#include <cstdint>
#include "../utils/Harmonic.h"
#include "../utils/MultiBiquad.h"
#include "../utils/OversampledHarmonics.h"
#include <array>
#include <cstddef>

class SpectrumExtend {
public:
    SpectrumExtend();

    void Process(float *samples, uint32_t size);
    void Reset();
    void SetEnable(bool enable);
    void SetExciter(float value);
    void SetReferenceFrequency(uint32_t freq);
    void SetSamplingRate(uint32_t samplingRate);
    bool SetAdvanced(const int32_t* values,size_t count);
    int32_t GetEffective(unsigned index) const;
    void ClearAudition();
    bool SetNonlinear(const int32_t* values,size_t count);
    int32_t GetNonlinear(unsigned index) const;

private:
    std::array<MultiBiquad, 2> highpass;
    std::array<MultiBiquad, 2> lowpass;
    std::array<OversampledHarmonics,4> paths{OversampledHarmonics(1,true),OversampledHarmonics(1),OversampledHarmonics(2),OversampledHarmonics(4)};
    std::array<int32_t,4> nonlinearSettings{0,0,100,1};
    std::array<std::array<float,2>,33> dryHistory{};
    unsigned dryPosition=0,activePath=0,targetPath=0,warmFrames=0;
    int candidatePath=-1;
    float pathMix=0.f,pathStep=0.f,drive=1.f,driveTarget=1.f,even=0.f,evenTarget=0.f,highOrder=1.f,highTarget=1.f;
    bool enabled;
    uint32_t samplingRate;
    uint32_t referenceFreq;
    float exciter;
    float hpQ=.717f,lpQ=.717f,outputTarget=1.f;
    uint32_t lowpassFrequency=0;
    bool audition=false,lastAuditionRequest=false;
    float currentExciter=0.f,currentHP=7600.f,currentHPQ=.717f,currentLP=22000.f,currentLPQ=.717f;
    float currentOutput=1.f,currentAudition=0.f,smoothing=0.f,filterSmoothing=0.f;
    unsigned tuningTick=0;
    float EffectiveLowpass() const;
    void AdvanceFilters();
    void ProcessNonlinear(const double input[2],const float dry[2],double wet[2],float alignedDry[2]);
};



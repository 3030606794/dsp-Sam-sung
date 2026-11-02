#pragma once

#include <cstdint>
#include <array>
#include <cstddef>
#include "../utils/NoiseSharpening.h"
#include "../utils/HiFi.h"
#include "../utils/HighShelf.h"
#include "../utils/TopEndControls.h"

// Iscle: Verified with the latest version at 13/12/2022

class ViPERClarity {
public:
    enum ClarityMode {
        NATURAL,
        OZONE,
        XHIFI
    };

    ViPERClarity();

    void Process(float *samples, uint32_t size);
    void Reset();
    void SetClarity(float gainPercent);
    void SetClarityToFilter();
    void SetEnable(bool enable);
    void SetProcessMode(ClarityMode processMode);
    void SetSamplingRate(uint32_t samplingRate);
    bool SetAdvanced(const int32_t* values,size_t count);
    int32_t GetEffective(unsigned index) const;
    bool SetSelective(const int32_t* values,size_t count);
    bool SetDynamic(const int32_t* values,size_t count);
    bool SetDeess(const int32_t* values,size_t count);
    int32_t GetTopEnd(unsigned index) const;
    void ClearDynamics();

private:
    NoiseSharpening noiseSharpening;
    HighShelf highShelf[2];
    HiFi hifi;
    SelectiveSharpening selective;
    DynamicTreble dynamicTreble,deesser{true};
    bool enable;
    ClarityMode processMode;
    uint32_t samplingRate;
    float clarityGainPercent;
    std::array<int32_t,11> settings{0,8250,100,120,1200,0,0,0,0,5000,2500};
    float currentGain=0.f,currentFrequency=8250.f,currentSlope=1.f,appliedGain=0.f;
    float currentOutput=1.f,outputTarget=1.f,smoothing=0.f,filterSmoothing=0.f;
    unsigned tuningTick=0;
    void AdvanceShelf();
};



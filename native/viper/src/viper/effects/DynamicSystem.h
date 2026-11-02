#pragma once

#include <cstdint>
#include "../utils/DynamicBass.h"
#include "DynamicTuning.h"

class DynamicSystem {
public:
    DynamicSystem();

    void Process(float *samples, uint32_t size);
    void Reset();
    void SetBassGain(float gain);
    void SetEnable(bool enable);
    void SetSamplingRate(uint32_t samplingRate);
    void SetSideGain(float gainX, float gainY);
    void SetXCoeffs(uint32_t low, uint32_t high);
    void SetYCoeffs(uint32_t low, uint32_t high);
    bool SetProfessional(const int32_t*,size_t);
    bool SetTools(const int32_t*,size_t);
    void ClearTools();
    void GetRuntime(int32_t*)const;
    void GetResponse(int32_t*)const;

private:
    DynamicBass dynamicBass;
    DynamicTuning tuning;
    bool configured=false;
    uint32_t samplingRate;
    bool enable;
};



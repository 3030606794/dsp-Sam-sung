#pragma once

#include <cstdint>
#include "../utils/CRevModel.h"
#include "../utils/ProfessionalReverb.h"

class Reverberation {
public:
    Reverberation();

    void Process(float *buffer, uint32_t size);
    void Reset();
    void SetDamp(float value);
    void SetDry(float value);
    void SetEnable(bool enable);
    void SetRoomSize(float value);
    void SetWet(float value);
    void SetWidth(float value);
    void SetSamplingRate(uint32_t rate);
    bool SetProfessional(const int32_t*,size_t);
    bool SetAdvanced(const int32_t*,size_t);
    void GetDecay(int32_t*)const;
    bool SetTools(const int32_t*,size_t);
    void ClearTools();
    void GetRuntime(int32_t*)const;

private:
    CRevModel model;
    bool enable;
    reverbtuning::Engine professional;
    float professionalBlend=0.f,blendStep=.002083333f;
};




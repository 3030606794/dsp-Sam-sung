#pragma once


#include "IIR_1st.h"

class NoiseSharpening {
public:
    NoiseSharpening();

    void Process(float *buffer, uint32_t size);
    void ProcessSelected(float* samples,const float sharpenInput[2]);

    void Reset();

    void SetGain(float gain);

    void SetSamplingRate(uint32_t samplingRate);

    IIR_1st filters[2];
    float in[2];
    uint32_t samplingRate;
    float gain;
};




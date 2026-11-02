#pragma once

#include <cstdint>
#include "MultiBiquad.h"

class Subwoofer {
public:
    Subwoofer();

    void Process(float *samples, uint32_t size);
    void SetBassGain(uint32_t samplingRate, float gainDb);
    // Parameters 0/1: Hz, 2/3: dB offsets, 4/5: bandwidth in octaves.
    void SetTuning(unsigned parameter, float value);
    void Reset();

private:
    void Refresh(bool resetState);
    void AdvanceTuning();
    uint32_t rate = 48000;
    unsigned tick = 0;
    float target[7] = {-120.f, 44.f, 80.f, 0.f, 0.f, .75f, .20f};
    float current[7] = {-120.f, 44.f, 80.f, 0.f, 0.f, .75f, .20f};
    bool dirty = false;
    float smoothing = 0.f;
    MultiBiquad peak[2];
    MultiBiquad peakLow[2];
    MultiBiquad lowpass[2];
};



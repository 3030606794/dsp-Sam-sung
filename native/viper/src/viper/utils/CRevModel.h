#pragma once

#include "CCombFilter.h"
#include "CAllpassFilter.h"

class CRevModel {
public:
    CRevModel();
    ~CRevModel();

    void Mute();
    void ProcessReplace(float *bufL, float *bufR, uint32_t size);
    void UpdateCoeffs();
    void Reset();
    // Comparison snapshots require a fresh independent tail; the historical Reset remains unchanged.
    void ResetComparisonState();
    void SetRoomSize(float roomSize);
    void SetDamp(float damp);
    void SetWet(float wet);
    void SetDry(float dry);
    void SetWidth(float width);
    void SetMode(float mode);
    void SetSamplingRate(uint32_t samplingRate);
    float GetRoomSize();
    float GetDamp();
    float GetWet();
    float GetDry();
    float GetWidth();
    float GetMode();

private:
    float gain;
    float roomSize;
    float internalRoomSize;
    float damp;
    float internalDamp;
    float wet;
    float unknown1;
    float unknown2;
    float dry;
    float width;
    float mode;

    CCombFilter combL[8];
    CCombFilter combR[8];

    CAllpassFilter allpassL[4];
    CAllpassFilter allpassR[4];

    float *buffers[24]{};
    uint32_t samplingRate=0;
};




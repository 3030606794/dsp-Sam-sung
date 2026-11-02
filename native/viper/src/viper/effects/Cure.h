#pragma once

#include <cstdint>
#include "../utils/Crossfeed.h"
#include "../utils/PassFilter.h"
#include "../utils/HeadphoneCrossfeed.h"

// Iscle: Verified with the latest version at 13/12/2022

class Cure {
public:
    Cure();

    uint16_t GetCutoff();
    float GetFeedback();
    float GetLevelDelay();
    struct Crossfeed::Preset GetPreset(); // TODO: Fix with crossfeed
    void Process(float *buffer, uint32_t size);
    void Reset();
    void SetCutoff(uint16_t cutoff);
    void SetEnable(bool enabled);
    void SetFeedback(float feedback);
    void SetPreset(struct Crossfeed::Preset preset);
    void SetSamplingRate(uint32_t samplingRate);
    bool SetAdvanced(const int32_t* values,size_t count);
    bool SetTools(const int32_t* values,size_t count);
    bool SetProfessional2(const int32_t*,size_t);
    bool SetTools2(const int32_t*,size_t);
    void ClearTools();
    void GetRuntime(int32_t* values) const;
    void GetDiagnostics(int32_t*) const;

private:
    Crossfeed crossfeed;
    PassFilter passFilter;
    bool enabled;
    headphone::Engine professional;
    float professionalBlend=0.f,blendStep=1.f/480.f;
};

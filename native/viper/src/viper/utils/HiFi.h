#pragma once
#include <array>
#include "IIR_NOrder_BW_LH.h"
#include "IIR_NOrder_BW_BP.h"

class HiFi {
public:
    HiFi();
    ~HiFi();
    void Process(float *samples,uint32_t size);
    void Reset();
    void SetClarity(float value);
    void SetSamplingRate(uint32_t samplingRate);
    void SetTuning(float low,float high,const float trimDb[3],uint32_t lowDelayUs,uint32_t midDelayUs);
    unsigned LowDelayFrames() const;
    unsigned MidDelayFrames() const;
private:
    struct Filters { IIR_NOrder_BW_LH *lowpass,*highpass; IIR_NOrder_BW_BP *bandpass; } filters[2];
    // 768 kHz at the accepted 10 ms maximum needs 7680 history frames.
    static constexpr unsigned capacity=8192;
    static_assert(capacity > 768000u * 10000ull / 1000000u);
    using Ring=std::array<std::array<float,2>,capacity>;
    struct Delay { unsigned from=0,to=0; float mix=1.f; };
    Ring lowRing{},midRing{};
    Delay lowDelay,midDelay;
    unsigned position=0,tick=0;
    uint32_t samplingRate=48000,lowDelayUs=5000,midDelayUs=2500;
    float gain=1.f,currentGain=1.f,lowFrequency=120.f,highFrequency=1200.f,currentLow=120.f,currentHigh=1200.f;
    std::array<float,3> trim{1.f,1.f,1.f},currentTrim{1.f,1.f,1.f};
    float smoothing=0.f,filterSmoothing=0.f,delayStep=0.f;
    void RefreshFilters(bool resetState);
    void AdvanceTuning();
    void StartDelay(Delay& delay,unsigned wanted);
    float Delayed(const Ring& ring,const Delay& delay,unsigned channel) const;
};

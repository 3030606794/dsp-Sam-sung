#pragma once
#include <array>
#include <cstdint>
#include "MultiBiquad.h"

class SelectiveSharpening {
public:
    void Configure(uint32_t rate);
    void Set(const int32_t* values);
    void Process(const float dry[2],float selected[2]);
    int32_t Frequency(unsigned index) const;
    bool Bypassed() const {return mix==0.f;}
private:
    uint32_t rate=48000;std::array<int32_t,4> settings{0,3000,12000,707};
    MultiBiquad hp[2],lp[2];float low=3000,high=12000,q=.707f,mix=0,availability=1,alpha=0,filterAlpha=0;
    unsigned tick=0;
};

// Detection is independent of attenuation: negative shelves/peaks avoid the phase
// reinforcement that subtracting an IIR-filtered branch can create at its edges.
class DynamicTreble {
public:
    explicit DynamicTreble(bool deesser=false);
    void Configure(uint32_t rate);
    void Set(const int32_t* values);
    void Process(float samples[2]);
    int32_t Frequency(unsigned index=0) const;
    int32_t Reduction() const;
private:
    bool deesser=false,enabled=false,wasProcessing=false;uint32_t rate=48000;
    float lowTarget=6000,highTarget=10000,qTarget=.707f,thresholdTarget=-18,ratioTarget=4,maxTarget=6;
    float low=6000,high=10000,q=.707f,threshold=-18,ratio=4,maximum=6,mix=0,availability=1;
    float attackMs=5,releaseMs=120,attack=0,release=0,alpha=0,filterAlpha=0;
    double envelope=0;float appliedReduction=0;unsigned tick=0;
    MultiBiquad detectorHP[2],detectorLP[2],attenuation[2];
    void Detector(bool reset);
    void TimeCoefficients();
};

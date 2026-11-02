#pragma once
#include "ImagingControls.h"
#include <array>
#include <cstddef>
#include <cstdint>

namespace diffadvanced {
using Settings=std::array<int32_t,21>;
inline constexpr Settings defaults{0,0,5000,40,0,0,0,2,0,30,5,1000,20,400,0,500,60,20,80,200,2000};
bool valid(const int32_t* values,size_t count);
bool signalReading(unsigned index);
bool activeReading(unsigned index);

struct Meter {
    ImagingMeter values;
    double mid=0,total=0;
    uint32_t count=0,window=4800;
    int32_t folded=ImagingMeter::invalid;
    void Reset(uint32_t rate);
    bool Process(float left,float right);
    int32_t Fold() const{return folded;}
};

class State {
public:
    bool Configure(const Settings& next,uint32_t rate);
    void Reset();
    void ClearMeters();
    float Input(float left,float right,bool professional);
    void Process(float baseL,float baseR,float candidateL,float candidateR,float outputL,float outputR,bool professional);
    float Retention() const{return retention;}
    int32_t Get(unsigned index) const;
    void SetActualMix(float value,float transient);
private:
    struct Split {
        std::array<ImagingLowpass,2> low,high;
        void Configure(float lo,float hi,uint32_t rate);
        void Reset();
        std::array<std::array<float,2>,3> Process(float left,float right);
    };
    Settings settings=defaults;
    uint32_t rate=48000;
    std::array<Meter,2> global;
    std::array<Split,3> filters;
    std::array<std::array<Meter,3>,3> bands;
    float retention=1,protection=0,fast=0,slow=0;
    float withdrawAlpha=0,recoverAlpha=0,transientAlpha=0,releaseAlpha=0,fastRelease=0,slowAlpha=0;
    bool guardActive=false,transientDetected=false;
    int32_t actualMix=1000,actualTransient=0;
    void EvaluateGuard();
};
}

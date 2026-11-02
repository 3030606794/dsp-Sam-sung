#pragma once
#include "ImagingControls.h"
#include <array>
#include <cstddef>
#include <cstdint>

// Independent state for the new candidate and the guard's reduced-width
// reference. The first-round reference remains in ColorfulMusic.
class ImagingAdvanced {
public:
    struct Controls {
        float width=1,bandLow=150,bandHigh=16000,bandMix=0,bandAvailability=1;
        float focusHz=120,focusMix=0,focusWidth=1,output=1;
    };
    ImagingAdvanced();
    bool Set(uint32_t id,const int32_t* values,size_t count);
    void Reset(uint32_t rate);
    void ClearRuntime();
    void Process(float inputL,float inputR,float depthSide,float referenceL,float referenceR,
                 const Controls& controls,float& outputL,float& outputR);
    int32_t Get(unsigned index) const;
private:
    struct SidePath {
        ImagingLowpass lo,hi,focus;
        float lastLo=-1,lastHi=-1,lastFocus=-1;
        void Reset();
        float Process(float side,float width,const Controls&,uint32_t rate);
    } candidatePath,baselinePath;
    struct Allpass {
        double a=0,state=0;
        double Process(double x){const double y=a*x+state;state=x-a*y;return y;}
    };
    std::array<int32_t,6> dynamic{0,-180,20,30,10,250},guard{0,0,5,30,600,1000};
    std::array<int32_t,4> transient{0,500,60,80};
    std::array<int32_t,8> space{0,30,150,0,2,1200,10,150};
    uint32_t rate=48000,tick=0,window=4800,count=0,write=0;
    float alpha=0,filterAlpha=0,delayMix=0,diffusionMix=0;
    float delayFrom=0,delayTo=0,delayFade=1;
    std::array<float,1024> delayRing{};
    std::array<Allpass,4> allpass{};
    std::array<float,4> frequencies{},targetFrequencies{};
    std::array<float,3> stageMix{0,1,0};
    float dynamicThreshold=-18,dynamicRatio=2,dynamicMaximum=3;
    double guardAmount=0,transientAmount=0,reduction=0;
    float dynAttack=0,dynRelease=0,guardWithdraw=0,guardRecover=0,transientRecover=0;
    float rmsAlpha=0,fastAlpha=0,slowAlpha=0;
    double transientPowerRatio=3.9810717055349722;
    double sidePower=0,fastPower=0,slowPower=0,foldMid=0,foldStereo=0;
    bool guardTriggered=false;
    ImagingMeter inputMeter,baselineMeter,candidateMeter;
    int32_t monoFold=ImagingMeter::invalid;
    std::array<int32_t,3> windowControls{0,0,0};
    void UpdateTargets();
    float Space(float side);
    float ReadDelay(float frames) const;
    bool Bypassed() const;
};

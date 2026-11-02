#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include "../utils/ImagingControls.h"
#include "../utils/DiffAdvanced.h"

class DiffSurround {
public:
    DiffSurround();
    ~DiffSurround();
    void Process(float* samples,uint32_t frames);
    void Reset();
    void SetDelayTime(float milliseconds);
    void SetEnable(bool enabled);
    void SetSamplingRate(uint32_t rate);
    static bool ValidAdvanced(const int32_t* values,size_t count);
    struct Prepared;
    static std::shared_ptr<Prepared> PrepareAdvanced(const int32_t* values,size_t count,uint32_t rate);
    static std::shared_ptr<Prepared> PrepareConfiguration(const std::array<int32_t,7>& basic,const diffadvanced::Settings& advanced,uint32_t rate);
    bool ApplyPrepared(std::shared_ptr<Prepared>& prepared);
    bool SetAdvanced(const int32_t* values,size_t count);
    bool SetSecondRound(const int32_t* values,size_t count);
    void SetAudition(int32_t request);
    void ClearRuntime();
    int32_t GetRuntime(unsigned index) const;
    int32_t GetSecondRound(unsigned index) const;
private:
    struct Storage;
    std::unique_ptr<Storage> storage;
    std::array<int32_t,7> settings{0,500,0,1000,0,150,12000};
    diffadvanced::Settings advanced=diffadvanced::defaults;
    diffadvanced::State dynamics;
    uint32_t samplingRate=48000;
    bool enable=false;
    float delayTime=0;
    int32_t audition=0,lastAudition=0;
    float bypassMix=0,monoMix=0,alpha=0;
    std::array<float,5> auditionWeights{1,0,0,0,0};
    std::array<float,5> auditionStart{1,0,0,0,0};
    unsigned auditionFrame=0;
    ImagingMeter before,after;
    uint32_t count=0,window=4800;
    double inMid=0,inTotal=0,outMid=0,outTotal=0;
    int32_t beforeFold=ImagingMeter::invalid,afterFold=ImagingMeter::invalid;
    void Prepare(unsigned slot);
};

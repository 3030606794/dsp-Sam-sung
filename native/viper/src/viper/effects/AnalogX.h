#pragma once

#include <cstdint>
#include <cstddef>
#include <memory>
#include "AnalogTuning.h"

class AnalogX {
public:
    AnalogX();
    ~AnalogX();
    AnalogX(const AnalogX&)=delete;
    AnalogX& operator=(const AnalogX&)=delete;

    void Process(float *samples, uint32_t size);
    void Reset();
    void SetEnable(bool enable);
    void SetProcessingModel(int processingModel);
    void SetSamplingRate(uint32_t samplingRate);
    bool SetProfessional(const int32_t*,size_t);
    bool SetTools(const int32_t*,size_t);
    void ClearTools();
    void GetRuntime(int32_t*)const;

private:
    struct Engine;
    std::unique_ptr<Engine> engine;
};



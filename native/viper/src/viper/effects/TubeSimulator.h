#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include "TubeTuning.h"
class TubeSimulator {
public:
    TubeSimulator();
    ~TubeSimulator();
    TubeSimulator(const TubeSimulator&)=delete;
    TubeSimulator& operator=(const TubeSimulator&)=delete;
    void Reset();
    void SetEnable(bool);
    void SetSamplingRate(uint32_t);
    void TubeProcess(float*,uint32_t);
    bool SetProfessional(const int32_t*,size_t);
    bool SetTools(const int32_t*,size_t);
    void ClearTools();
    void GetRuntime(int32_t*)const;
private:
    struct Engine;
    std::unique_ptr<Engine> engine;
};

#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace dynamictuning {
constexpr unsigned kWords=36,kToolsWords=76,kRuntimeWords=48,kResponseWords=200;
using Settings=std::array<int32_t,kWords>;
inline constexpr Settings defaults{1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,20,250,-180,60,60,100,250,0,1000,0,0,0,0,0,0,0,0};
inline constexpr Settings minimum{1,0,0,0,0,-120,-1000,-1000,-1000,-240,0,-1900,-500,-1000,-35,0,0,0,0,20,60,-600,0,0,10,50,0,0,0,-240,0,0,0,0,0,0};
inline constexpr Settings maximum{1,1,5,100,1,120,1000,1000,1000,240,1,1900,500,1000,145,300,20000,1,1,500,2000,-60,120,180,2000,2000,1,1000,1,0,1,1,0,0,0,0};
bool valid(const int32_t*,size_t);
bool validTools(const int32_t*,size_t);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void failAllocationForTest(int after);
#endif
}

class DynamicTuning {
public:
 DynamicTuning();
 ~DynamicTuning();
 DynamicTuning(const DynamicTuning&)=delete;
 DynamicTuning& operator=(const DynamicTuning&)=delete;
 bool SetProfessional(const int32_t*,size_t);
 bool SetTools(const int32_t*,size_t);
 void ClearTools();
 void SetEnable(bool);
 void SetSamplingRate(uint32_t);
 void Reset();
 void Process(float*,uint32_t);
 void GetRuntime(int32_t*)const;
 void GetResponse(int32_t*)const;
private:
 struct Engine;
 std::unique_ptr<Engine> engine;
};

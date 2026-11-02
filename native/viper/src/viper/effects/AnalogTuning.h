#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
namespace analogtuning {
constexpr unsigned kWords=28,kToolsWords=60,kRuntimeWords=40;
using Settings=std::array<int32_t,kWords>;
inline constexpr Settings defaults{1,0,0,0,0,1000,0,0,0,0,0,240,717,0,717,633,58,6280,0,1000,0,0,0,2,0,0,0,0};
inline constexpr Settings minimum{1,0,0,0,-120,0,-50000,-50000,-50000,-50000,0,20,300,-15000,300,20,-600,100,0,0,0,-240,0,2,0,0,0,0};
inline constexpr Settings maximum{1,1,2,1,180,2000,50000,50000,50000,50000,1,4000,2000,4000,2000,4000,600,8000,1,1000,1,0,1,4,1,0,0,0};
bool valid(const int32_t*,size_t);
bool validTools(const int32_t*,size_t);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void failAllocationForTest(int after);
#endif
}

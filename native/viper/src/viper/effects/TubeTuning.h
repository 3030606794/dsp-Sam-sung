#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
namespace tubetuning {
constexpr unsigned kWords=20,kToolsWords=44,kRuntimeWords=32;
using Settings=std::array<int32_t,kWords>;
inline constexpr Settings defaults{1,0,0,0,500,0,0,1000,0,0,0,0,1000,0,0,0,2,0,0,0};
inline constexpr Settings minimum{1,0,0,0,0,-1000,0,0,0,0,0,0,0,0,-240,0,2,0,0,0};
inline constexpr Settings maximum{1,1,1,180,1000,1000,1,1000,20000,1000,20000,1,1000,1,0,1,4,1,0,0};
bool valid(const int32_t*,size_t);
bool validTools(const int32_t*,size_t);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void failAllocationForTest(int after);
#endif
}

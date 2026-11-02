// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
namespace dspfusion {
// schema, professional, wet(0.1%), pre/output(0.1dB), link, LR/RL(milli-dB),
// LR/RL(0.1us), HP/LP(6/12/24), RMS, limit(0.1dB), attack/recovery(ms),
// attenuation-only, sample-peak limiter, ceiling(0.1dBFS), release(ms), meters.
inline constexpr std::array<int32_t,21> kSurroundProfessionalDefaults{
    1,0,1000,0,0,1,5500,5500,350,350,1,1,0,30,80,800,1,0,-10,100,0};
inline constexpr std::array<int32_t,21> kSurroundProfessionalLow{
    1,0,0,-180,-120,0,500,500,200,200,0,0,0,0,10,100,0,0,-120,10,0};
inline constexpr std::array<int32_t,21> kSurroundProfessionalHigh{
    1,1,1000,0,60,1,10000,10000,2100,2100,2,2,1,60,300,3000,1,1,-1,500,1};
}

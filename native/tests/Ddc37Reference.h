// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
// Frozen ui37 vdc.c DirectForm2 stereo loop, independent of production kernels.
class Ddc37Reference {
 struct Section {double b0,b1,b2,a1,a2,v1L=0,v2L=0,v1R=0,v2R=0;};
 std::array<Section,128> sections{};size_t count=0;
 public:void load(const double*,size_t);void process(float*,float*,size_t);
};

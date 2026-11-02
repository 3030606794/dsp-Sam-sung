// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>

namespace dspfusion {
// Independent creative stages. Configurations are atomic snapshots; allocation
// and buffer resizing occur only in the host-excluded configure/reset path.
class CreativeEffectsProcessor {
public:
    static constexpr uint32_t kSetFirst=196730,kSetLast=196732;
    static constexpr uint32_t kCapabilityGet=132010,kStatusFirst=132011,kStatusLast=132013;
    static constexpr size_t kWords=12,kStatusWords=32;
    enum Kind { kGranular=0, kFreeze=1, kShimmer=2 };
    CreativeEffectsProcessor();
    ~CreativeEffectsProcessor();
    CreativeEffectsProcessor(const CreativeEffectsProcessor&)=delete;
    CreativeEffectsProcessor& operator=(const CreativeEffectsProcessor&)=delete;
    bool setParameters(unsigned kind,const int32_t* words,size_t count);
    void configure(uint32_t sampleRate);
    void reset();
    void process(float* stereo,uint32_t frames);
    void readStatus(unsigned kind,int32_t* words) const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}

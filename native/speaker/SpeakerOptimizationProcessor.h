// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>

namespace dspfusion {
/** Independent ViPER-derived speaker correction; never reuses removed legacy 65603. */
class SpeakerOptimizationProcessor {
public:
    static constexpr uint32_t kSet=196762,kStatusGet=132043,kCapabilityGet=132044;
    static constexpr size_t kWords=128,kStatusWords=128;
    SpeakerOptimizationProcessor();
    ~SpeakerOptimizationProcessor();
    void configure(uint32_t rate);
    void reset();
    bool setParameters(const int32_t* words,size_t count);
    void process(float* interleaved,uint32_t frames);
    // GET renews only the observation lease. It never touches filter/audio state.
    void readStatus(int32_t* words,bool renewObservation=true) const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}

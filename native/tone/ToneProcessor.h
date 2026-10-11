// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace dspfusion {
// Independent perceptual tone model. This is not HiBy's proprietary MSEB engine.
// The caller serializes parameter changes and process(). No audio-thread allocations.
class ToneProcessor {
public:
    ToneProcessor();
    void configure(uint32_t sampleRate);
    bool setParameters(const int32_t* values, size_t count);
    void process(float* interleaved, uint32_t frames);
    void reset();
private:
    struct Coeff { double b0=1, b1=0, b2=0, a1=0, a2=0; };
    struct Filter {
        Coeff now{}, target{};
        double z1[2]{}, z2[2]{};
        double tick(double input, unsigned channel);
        void advance(double alpha);
        void clear();
    };
    static Coeff peak(double rate, double hz, double q, double db);
    static Coeff shelf(double rate, double hz, double db, bool high);
    static double magnitude(const Coeff&, double omega);
    void design();
    std::array<Filter, 10> filters_{};
    std::array<int32_t, 10> knobs_{};
    uint32_t sampleRate_=48000;
    bool enabled_=false;
    double alpha_=0, wet_=0, wetTarget_=0, preamp_=1, preampTarget_=1;
    double punch_=0, punchTarget_=0, transient_=0, transientTarget_=0;
    double low_[2]{}, fast_=0, slow_=0, bassFast_=0, bassSlow_=0;
    double lowAlpha_=0, fastAlpha_=0, slowAlpha_=0, bassFastAlpha_=0, bassSlowAlpha_=0;
};
}

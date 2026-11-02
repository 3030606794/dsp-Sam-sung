#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace dspfusion {
// Final, stereo-linked overload protection. The upstream James limiter cannot
// protect gains introduced later by Tone/ViPER. Hold the peak between waveform
// crests and release in seconds, not blocks, to avoid hard-clipped flat tops or
// block-size/sample-rate dependent gain modulation. Below the ceiling this is
// sample-exact unity, with no look-ahead latency or audio-thread allocation.
class OutputSafetyLimiter {
public:
    static constexpr double ceiling = 0.9995;
    void configure(uint32_t rate) {
        rate = std::clamp(rate, 8000u, 768000u);
        if (rate == rate_) return;
        if (rate_) hold_ = static_cast<uint32_t>(double(hold_) * rate / rate_);
        rate_ = rate;
        holdFrames_ = static_cast<uint32_t>(std::ceil(0.010 * rate));
        release_ = std::exp(-1.0 / (0.100 * rate));
    }
    void reset() { envelope_ = ceiling; hold_ = 0; }
    void process(float& left, float& right) {
        if (!std::isfinite(left)) left = 0;
        if (!std::isfinite(right)) right = 0;
        const double peak = std::max(std::abs(double(left)), std::abs(double(right)));
        if (peak >= envelope_) {
            envelope_ = peak;
            hold_ = holdFrames_;
        } else if (hold_) {
            --hold_;
        } else {
            envelope_ = std::max(peak, ceiling + (envelope_ - ceiling) * release_);
        }
        if (envelope_ <= ceiling) return;
        const double gain = ceiling / envelope_;
        left = static_cast<float>(left * gain);
        right = static_cast<float>(right * gain);
    }
private:
    uint32_t rate_ = 0, holdFrames_ = 480, hold_ = 0;
    double release_ = std::exp(-1.0 / 4800.0), envelope_ = ceiling;
};
} // namespace dspfusion

#include "Subwoofer.h"
#include "../constants.h"
#include <algorithm>
#include <cmath>

Subwoofer::Subwoofer() {
    rate = VIPER_DEFAULT_SAMPLING_RATE;
    Reset();
}

void Subwoofer::Refresh(bool resetState) {
    for (int ch = 0; ch < 2; ++ch) {
        peak[ch].RefreshFilter(MultiBiquad::PEAK, current[0] + current[3], current[1], rate, current[5], true, resetState);
        peakLow[ch].RefreshFilter(MultiBiquad::PEAK, current[0] - 18.06179974f + current[4], current[2], rate, current[6], true, resetState);
        lowpass[ch].RefreshFilter(MultiBiquad::LOW_PASS, 0.f, 380.f, rate, .6f, false, resetState);
    }
}

void Subwoofer::Reset() {
    smoothing = -std::expm1(-16.f / (.015f * rate));
    std::copy(target, target + 7, current);
    tick = 0;
    dirty = false;
    Refresh(true);
}

void Subwoofer::AdvanceTuning() {
    if (tick++ % 16 != 0 || !dirty) return;
    dirty = false;
    for (unsigned i = 0; i < 7; ++i) {
        const float previous = current[i];
        current[i] += smoothing * (target[i] - current[i]);
        if (current[i] == previous || std::abs(target[i] - current[i]) < 1.e-4f) current[i] = target[i];
        else dirty = true;
    }
    Refresh(false); // Keep the histories while the coefficients move.
}

void Subwoofer::Process(float *samples, uint32_t size) {
    for (uint32_t i = 0; i < size * 2; i += 2) {
        AdvanceTuning();
        for (unsigned ch = 0; ch < 2; ++ch) {
            const float dry = samples[i + ch];
            double wet = peak[ch].ProcessSample(dry);
            wet = peakLow[ch].ProcessSample(wet);
            wet = lowpass[ch].ProcessSample(wet - dry);
            samples[i + ch] = dry * .5f + static_cast<float>(wet) * .6f;
        }
    }
}

void Subwoofer::SetBassGain(uint32_t samplingRate, float gainAmplitude) {
    if (!std::isfinite(gainAmplitude) || gainAmplitude < 0.f || samplingRate == 0) return;
    // Legacy zero strength previously evaluated log(0), contaminating filter state.
    const float gain = 20.f * std::log10(std::max(gainAmplitude, 1.e-6f));
    if (target[0] != gain) { target[0] = gain; dirty = true; }
    if (rate != samplingRate) {
        rate = samplingRate;
        smoothing = -std::expm1(-16.f / (.015f * rate));
        Refresh(false);
    }
}

void Subwoofer::SetTuning(unsigned parameter, float value) {
    const float lower[6] = {20.f, 60.f, -12.f, -12.f, .1f, .1f};
    const float upper[6] = {80.f, 160.f, 12.f, 12.f, 2.f, 2.f};
    if (parameter >= 6 || !std::isfinite(value) || value < lower[parameter] || value > upper[parameter]) return;
    if (target[parameter + 1] != value) { target[parameter + 1] = value; dirty = true; }
}

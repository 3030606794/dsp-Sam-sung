#include "TimeConstDelay.h"
#include <cmath>

TimeConstDelay::TimeConstDelay() {
    this->offset = 0;
    this->sampleCount = 0;
}

float TimeConstDelay::ProcessSample(float sample) {
    float val = this->samples[this->offset];
    this->samples[this->offset] = sample;
    // Legacy ColorfulMusic always addressed slot zero: modff(integer, ...) returns
    // zero, and its old float* cast also violated aliasing. Preserve that audible
    // one-sample delay deterministically; do not silently replace it with 20/14 ms.
    this->offset = 0;
    return val;
}

void TimeConstDelay::SetParameters(uint32_t samplingRate, float delay) {
    this->sampleCount = (uint32_t) ((float) samplingRate * delay);
    this->samples.resize(this->sampleCount);
    this->offset = 0;
}

// Frozen ui34 Dynamic34Reference, Dynamic34Poles and Dynamic34Biquad; independent reference.
#include "Dynamic34Reference.h"
#include "../viper/src/viper/constants.h"
#include <cstring>
#include <cmath>

Dynamic34Poles::Dynamic34Poles() {
    this->samplingRate = VIPER_DEFAULT_SAMPLING_RATE;
    this->lower_freq = 160;
    this->upper_freq = 8000;
    UpdateCoeff();
}

void Dynamic34Poles::Reset() {
    UpdateCoeff();
}

void Dynamic34Poles::UpdateCoeff() {
    memset(&this->Dynamic34Channels[0], 0, sizeof(Dynamic34Channel));
    memset(&this->Dynamic34Channels[1], 0, sizeof(Dynamic34Channel));

    this->Dynamic34Channels[0].lower_angle = (float) this->lower_freq * (float) M_PI / (float) this->samplingRate;
    this->Dynamic34Channels[1].lower_angle = (float) this->lower_freq * (float) M_PI / (float) this->samplingRate;
    this->Dynamic34Channels[0].upper_angle = (float) this->upper_freq * (float) M_PI / (float) this->samplingRate;
    this->Dynamic34Channels[1].upper_angle = (float) this->upper_freq * (float) M_PI / (float) this->samplingRate;
}

static inline void Dynamic34FilterSide(Dynamic34Channel *side, float sample, float *out1, float *out2, float *out3) {
    float oldestSampleIn = side->in[2];
    side->in[2] = side->in[1];
    side->in[1] = side->in[0];
    side->in[0] = sample;

    side->x[0] += side->lower_angle * (sample - side->x[0]);
    side->x[1] += side->lower_angle * (side->x[0] - side->x[1]);
    side->x[2] += side->lower_angle * (side->x[1] - side->x[2]);
    side->x[3] += side->lower_angle * (side->x[2] - side->x[3]);

    side->y[0] += side->upper_angle * (sample - side->y[0]);
    side->y[1] += side->upper_angle * (side->y[0] - side->y[1]);
    side->y[2] += side->upper_angle * (side->y[1] - side->y[2]);
    side->y[3] += side->upper_angle * (side->y[2] - side->y[3]);

    *out1 = side->x[3];
    *out2 = oldestSampleIn - side->y[3];
    *out3 = side->y[3] - side->x[3];
}

void Dynamic34Poles::DoFilterLeft(float sample, float *out1, float *out2, float *out3) {
    Dynamic34FilterSide(&this->Dynamic34Channels[0], sample, out1, out2, out3);
}

void Dynamic34Poles::DoFilterRight(float sample, float *out1, float *out2, float *out3) {
    Dynamic34FilterSide(&this->Dynamic34Channels[1], sample, out1, out2, out3);
}

void Dynamic34Poles::SetPassFilter(uint32_t lower_freq, uint32_t upper_freq) {
    this->lower_freq = lower_freq;
    this->upper_freq = upper_freq;
    UpdateCoeff();
}

void Dynamic34Poles::SetSamplingRate(uint32_t samplingRate) {
    this->samplingRate = samplingRate;
    UpdateCoeff();
}

// Iscle: Verified with the latest version at 13/12/2022
// Some variable names RE'd with help from https://github.com/wooters/miniDSP/blob/master/biquad.c

Dynamic34Biquad::Dynamic34Biquad() {
    Reset();
    SetCoeffs(1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
}

double Dynamic34Biquad::ProcessSample(double sample) {
    double out =
            sample * this->b0 +
            this->x1 * this->b1 +
            this->x2 * this->b2 +
            this->y1 * this->a1 +
            this->y2 * this->a2;

    this->x2 = this->x1;
    this->x1 = sample;
    this->y2 = this->y1;
    this->y1 = out;

    return out;
}

void Dynamic34Biquad::Reset() {
    this->a1 = 0.0;
    this->a2 = 0.0;
    this->b0 = 0.0;
    this->b1 = 0.0;
    this->b2 = 0.0;
    this->x1 = 0.0;
    this->x2 = 0.0;
    this->y1 = 0.0;
    this->y2 = 0.0;
}

void Dynamic34Biquad::SetBandPassParameter(float frequency, uint32_t samplingRate, float qFactor) {
    double omega = (2.0 * M_PI * (double) frequency) / (double) samplingRate;
    double sinOmega = sin(omega);
    double cosOmega = cos(omega);

    double alpha = sinOmega / ((double) qFactor + (double) qFactor);

    double a0 = alpha + 1.0;
    double a1 = cosOmega * -2.0;
    double a2 = 1.0 - alpha;
    double b0 = sinOmega / 2.0; // Reference biquad implementation would use alpha here
    double b1 = 0.0;
    double b2 = -(sinOmega / 2.0); // Reference biquad implementation would use -alpha here

    SetCoeffs(a0, a1, a2, b0, b1, b2);
}

void Dynamic34Biquad::SetCoeffs(double a0, double a1, double a2, double b0, double b1, double b2, bool resetState) {
    if (resetState) this->x2 = this->x1 = this->y2 = this->y1 = 0.0;

    this->a1 = -(a1 / a0);
    this->a2 = -(a2 / a0);
    this->b0 = b0 / a0;
    this->b1 = b1 / a0;
    this->b2 = b2 / a0;
}

void
Dynamic34Biquad::SetHighPassParameter(float frequency, uint32_t samplingRate, double dbGain, float qFactor, double param_6) {
    double omega = (2.0 * M_PI * (double) frequency) / (double) samplingRate;
    double sinOmega = sin(omega);
    double cosOmega = cos(omega);

    double A = pow(10.0, dbGain / 40.0);
    double sqrtA = sqrt(A);

    double z = sinOmega / 2.0 * sqrt((1.0 / A + A) * (1.0 / (double) qFactor - 1.0) + 2.0);

    double a0 = (A + 1.0) - (A - 1.0) * cosOmega + (sqrtA * 2.0) * z;
    double a1 = ((A - 1.0) - (A + 1.0) * cosOmega) * 2.0;
    double a2 = (A + 1.0) - (A - 1.0) * cosOmega - (sqrtA * 2.0) * z;
    double b0 = ((A + 1.0) + (A - 1.0) * cosOmega + (sqrtA * 2.0) * z) * A * omega;
    double b1 = A * -2.0 * ((A - 1.0) + (A + 1.0) * cosOmega) * omega;
    double b2 = ((A + 1.0) + (A - 1.0) * cosOmega - (sqrtA * 2.0) * z) * A * omega;

    SetCoeffs(a0, a1, a2, b0, b1, b2);
}

void Dynamic34Biquad::SetLowPassParameter(float frequency, uint32_t samplingRate, float qFactor, bool resetState) {
    double omega = (2.0 * M_PI * (double) frequency) / (double) samplingRate;
    double sinOmega = sin(omega);
    double cosOmega = cos(omega);

    double alpha = sinOmega / ((double) qFactor + (double) qFactor);

    double a0 = alpha + 1.0;
    double a1 = cosOmega * -2.0;
    double a2 = 1.0 - alpha;
    double b0 = (1.0 - cosOmega) / 2.0;
    double b1 = 1.0 - cosOmega;
    double b2 = (1.0 - cosOmega) / 2.0;

    SetCoeffs(a0, a1, a2, b0, b1, b2, resetState);
}

Dynamic34Reference::Dynamic34Reference() {
    this->qPeak = 0;
    SetSamplingRate(VIPER_DEFAULT_SAMPLING_RATE);
    this->bassGain = 1.f;
    this->sideGainX = 1.f;
    this->sideGainY = 1.f;
    this->lowFreqX = 120;
    this->highFreqX = 80;
    this->lowFreqY = 40;
    this->highFreqY = (uint32_t) ((float) this->samplingRate / 4.0f);

    this->filterX.SetPassFilter(this->lowFreqX, this->highFreqX);
    this->filterY.SetPassFilter(this->lowFreqY, this->highFreqY);
    this->lowPass.SetLowPassParameter(55.f, this->samplingRate, this->qPeak / 666.f + 0.5f);
}

void Dynamic34Reference::FilterSamples(float *samples, uint32_t size) {
    if (this->lowFreqX <= 120) {
        for (uint32_t i = 0; i < size; i++) {
            float left = samples[2 * i];
            float right = samples[2 * i + 1];
            float avg = (float) this->lowPass.ProcessSample(left + right);
            samples[2 * i] = left + avg;
            samples[2 * i + 1] = right + avg;
        }
    } else {
        for (uint32_t i = 0; i < size; i++) {
            float x1, x2, x3, x4, x5, x6, y1, y2, y3, y4, y5, y6;

            this->filterX.DoFilterLeft(samples[2 * i], &x1, &x2, &x3);
            this->filterX.DoFilterRight(samples[2 * i + 1], &x4, &x5, &x6);
            this->filterY.DoFilterLeft(this->bassGain * x1, &y1, &y2, &y3);
            this->filterY.DoFilterRight(this->bassGain * x4, &y4, &y5, &y6);

            samples[2 * i] = x2 + y3 + this->sideGainX * y2 + this->sideGainY * y1 + x3;
            samples[2 * i + 1] = x5 + y6 + this->sideGainX * y5 + this->sideGainY * y4 + x6;
        }
    }
}

void Dynamic34Reference::Reset() {
    this->filterX.Reset();
    this->filterY.Reset();
    this->lowPass.SetLowPassParameter(55.0, this->samplingRate, this->qPeak / 666.0f + 0.5f);
}

void Dynamic34Reference::SetBassGain(float gain) {
    this->bassGain = gain;

    double x = ((gain - 1.0) / 20.0) * 1600.0;
    if (x > 1600.0) {
        x = 1600.0;
    }
    this->qPeak = (float) x;

    this->lowPass.SetLowPassParameter(55.0, this->samplingRate, this->qPeak / 666.0f + 0.5f);
}

void Dynamic34Reference::SetFilterXPassFrequency(uint32_t low, uint32_t high) {
    this->lowFreqX = low;
    this->highFreqX = high;

    this->filterX.SetPassFilter(low, high);
    this->filterX.SetSamplingRate(this->samplingRate);
    this->lowPass.SetLowPassParameter(55.0, this->samplingRate, this->qPeak / 666.0f + 0.5f);
}

void Dynamic34Reference::SetFilterYPassFrequency(uint32_t low, uint32_t high) {
    this->lowFreqY = low;
    this->highFreqY = high;

    this->filterY.SetPassFilter(low, high);
    this->filterY.SetSamplingRate(this->samplingRate);
    this->lowPass.SetLowPassParameter(55.0, this->samplingRate, this->qPeak / 666.0f + 0.5f);
}

void Dynamic34Reference::SetSamplingRate(uint32_t samplingRate) {
    this->samplingRate = samplingRate;
    this->filterX.SetSamplingRate(samplingRate);
    this->filterY.SetSamplingRate(samplingRate);
    this->lowPass.SetLowPassParameter(55.0, samplingRate, this->qPeak / 666.0f + 0.5f);
}

void Dynamic34Reference::SetSideGain(float gainX, float gainY) {
    this->sideGainX = gainX;
    this->sideGainY = gainY;
}

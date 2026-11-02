// Frozen ui34 algorithm and filter coefficients. Do not update with implementation.
#pragma once

#include <cstdint>

typedef struct {
    float lower_angle;
    float upper_angle;

    float in[3];
    float x[4];
    float y[4];
} Dynamic34Channel;


class Dynamic34Poles {
public:
    Dynamic34Poles();

    void Reset();

    void UpdateCoeff();

    void DoFilterLeft(float sample, float *out1, float *out2, float *out3);

    void DoFilterRight(float sample, float *out1, float *out2, float *out3);

    void SetPassFilter(uint32_t lower_freq, uint32_t upper_freq);

    void SetSamplingRate(uint32_t samplingRate);

    Dynamic34Channel Dynamic34Channels[2];
    uint32_t lower_freq;
    uint32_t upper_freq;
    uint32_t samplingRate;
};



#include <cstdint>

// Iscle: Verified with the latest version at 13/12/2022

class Dynamic34Biquad {
public:
    Dynamic34Biquad();

    double ProcessSample(double sample);
    void Reset();
    void SetBandPassParameter(float frequency, uint32_t samplingRate, float qFactor);
    void SetCoeffs(double a0, double a1, double a2, double b0, double b1, double b2, bool resetState = true);
    void SetHighPassParameter(float frequency, uint32_t samplingRate, double dbGain, float qFactor, double param_6);
    void SetLowPassParameter(float frequency, uint32_t samplingRate, float qFactor, bool resetState = true);

private:
    double x1;
    double x2;
    double y1;
    double y2;
    double a1;
    double a2;
    double b0;
    double b1;
    double b2;
};

#include <cstdint>

class Dynamic34Reference {
public:
    Dynamic34Reference();

    void FilterSamples(float *samples, uint32_t size);

    void Reset();

    void SetBassGain(float gain);

    void SetFilterXPassFrequency(uint32_t low, uint32_t high);

    void SetFilterYPassFrequency(uint32_t low, uint32_t high);

    void SetSamplingRate(uint32_t samplingRate);

    void SetSideGain(float gainX, float gainY);

    uint32_t lowFreqX, highFreqX;
    uint32_t lowFreqY, highFreqY;
    uint32_t samplingRate;
    float qPeak;
    float bassGain;
    float sideGainX, sideGainY;

    Dynamic34Poles filterX;
    Dynamic34Poles filterY;
    Dynamic34Biquad lowPass;
};

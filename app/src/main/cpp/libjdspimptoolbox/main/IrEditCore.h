#ifndef JDSP_IR_EDIT_CORE_H
#define JDSP_IR_EDIT_CORE_H
#include <float.h>
#include <math.h>

/* Shared by the JNI implementation and the host regression test. */
static void jdspIrCheckStartEnd(float **signal, int channels, int samples,
                              float startDb, float endDb, int range[2])
{
    range[0] = range[1] = 0;
    if (!signal || channels <= 0 || samples <= 0) return;
    float peak = 0.0f;
    for (int c = 0; c < channels; ++c)
        for (int n = 0; n < samples; ++n) {
            const float magnitude = fabsf(signal[c][n]);
            if (magnitude > peak) peak = magnitude;
        }
    const float inverse = 1.0f / ((peak < FLT_EPSILON) ? (peak + FLT_EPSILON) : peak);
    const float start = powf(10.0f, startDb / 20.0f);
    const float end = powf(10.0f, endDb / 20.0f);
    int first = samples, last = 0;
    for (int c = 0; c < channels; ++c)
        for (int n = 0; n < samples; ++n) {
            const float magnitude = fabsf(signal[c][n]) * inverse;
            if (magnitude > start && n < first) first = n;
            if (magnitude > end && n > last) last = n;
        }
    if (first == samples) first = 0;
    range[0] = first;
    /* Silence remains a valid one-sample IR; a single final impulse retains its position. */
    range[1] = (last < first ? first : last) + 1;
}

typedef struct { int begin, end; } jdspIrTaskRange;
static int jdspIrMpsTasks(int channels, jdspIrTaskRange ranges[4])
{
    if (channels < 1 || channels > 4 || channels == 3) return 0;
    for (int i = 0; i < channels; ++i) { ranges[i].begin = i; ranges[i].end = i + 1; }
    return channels;
}
#endif

#include "IrEditCore.h"
#undef NDEBUG
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks;
static void expect(float **signal, int channels, int samples, float start, float end, int first, int last) {
    int range[2]; jdspIrCheckStartEnd(signal, channels, samples, start, end, range);
    assert(range[0] == first && range[1] == last); ++checks;
}
int main(void) {
    float a[] = {0, 0, -1, 0.05f, 0, 0, 0, 0}, b[] = {0}, z[8] = {0};
    float *s[] = {a, z};
    expect(s, 1, 8, -20, -40, 2, 4); /* Negative largest sample must set a positive scale. */
    expect(s, 2, 8, -20, -40, 2, 4); /* A silent channel must not force the start back to zero. */
    z[0] = -2; expect(s, 2, 8, -3, -3, 0, 1); /* First sample of later channels participates in peak finding. */
    memset(z, 0, sizeof(z)); memset(a, 0, sizeof(a)); a[7] = -1;
    expect(s, 1, 8, -80, -100, 7, 8); /* A peak at the very last sample is not mistaken for no peak. */
    a[7] = 0; expect(s, 1, 8, -80, -100, 0, 1);
    s[0] = b; expect(s, 1, 1, -80, -100, 0, 1); b[0] = 1; expect(s, 1, 1, -80, -100, 0, 1);
    expect(0, 0, 0, -80, -100, 0, 0);
    /* Independent double-precision reference, many signs/channels/thresholds. */
    unsigned random = 0x12ace;
    for (int channels = 1; channels <= 4; ++channels) for (int trial = 0; trial < 300; ++trial) {
        float data[4][73]; float *ptr[4]; double peak = 0;
        for (int c = 0; c < channels; ++c) {
            ptr[c] = data[c];
            for (int n = 0; n < 73; ++n) {
                random = 1664525U * random + 1013904223U;
                data[c][n] = (random % 5 == 0) ? ((int)(random >> 8) % 20001 - 10000) * .0001f : 0;
                if (fabs((double)data[c][n]) > peak) peak = fabs((double)data[c][n]);
            }
        }
        const float startDb = -6.f - (trial % 35), endDb = -18.f - (trial % 59);
        const double start = peak * pow(10., startDb / 20.), end = peak * pow(10., endDb / 20.);
        int first = 73, last = 0;
        for (int c = 0; c < channels; ++c) for (int n = 0; n < 73; ++n) {
            if (fabs((double)data[c][n]) > start && n < first) first = n;
            if (fabs((double)data[c][n]) > end && n > last) last = n;
        }
        if (first == 73) first = 0;
        expect(ptr, channels, 73, startDb, endDb, first, (first > last ? first : last) + 1);
        for (int c = 0; c < channels; ++c) for (int n = 0; n < 73; ++n) data[c][n] = -data[c][n];
        expect(ptr, channels, 73, startDb, endDb, first, (first > last ? first : last) + 1);
    }
    for (int channels = 0; channels <= 5; ++channels) {
        jdspIrTaskRange memory[6]; memset(memory, 0x5a, sizeof(memory));
        jdspIrTaskRange before = memory[0], after = memory[5];
        const int count = jdspIrMpsTasks(channels, memory + 1);
        assert(!memcmp(&before, &memory[0], sizeof(before)) && !memcmp(&after, &memory[5], sizeof(after)));
        if (channels == 1 || channels == 2 || channels == 4) {
            assert(count == channels); int visits[4] = {0};
            for (int i = 0; i < count; ++i) for (int c = memory[i+1].begin; c < memory[i+1].end; ++c) ++visits[c];
            for (int c = 0; c < channels; ++c) assert(visits[c] == 1);
        } else assert(count == 0);
        ++checks;
    }
    printf("IR edit host regression: %u cases passed (signed peaks, first/last samples, mono/stereo/matrix task bounds).\n", checks);
    return 0;
}

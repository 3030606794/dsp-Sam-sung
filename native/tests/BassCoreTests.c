#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jdsp/jdsp_header.h"

typedef struct { JamesDSPLib *dsp; float left[512], right[512]; size_t position; } BassHarness;
static BassHarness makeBass(double rate, const int32_t *parameters)
{
    BassHarness h = {0};
    h.dsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
    assert(h.dsp);
    h.dsp->fs = rate;
    BassBoostConstructor(h.dsp);
    if (parameters) assert(BassBoostSetAdvanced(h.dsp, parameters));
    else BassBoostSetParam(h.dsp, 5.f);
    BassBoostEnable(h.dsp);
    return h;
}
static double renderBass(BassHarness *h, size_t count, double frequency, size_t block)
{
    double energy = 0;
    while (count)
    {
        const size_t n = count < block ? count : block;
        for (size_t i = 0; i < n; ++i)
            h->left[i] = h->right[i] = .01f * sin(2.0 * M_PI * frequency * (h->position + i) / h->dsp->fs);
        h->dsp->tmpBuffer[0] = h->left;
        h->dsp->tmpBuffer[1] = h->right;
        BassBoostProcess(h->dsp, n);
        for (size_t i = 0; i < n; ++i)
        {
            assert(isfinite(h->left[i]) && isfinite(h->right[i]));
            assert(fabsf(h->left[i]) < 2.f && h->left[i] == h->right[i]);
            energy += (double)h->left[i] * h->left[i];
        }
        h->position += n;
        count -= n;
    }
    return energy;
}

void runBassCoreTests(void)
{
    const double rates[] = {8000, 44100, 48000, 96000, 192000};
    const int32_t defaults[7] = {50,0,250,200,48,192,192};
    for (unsigned r = 0; r < sizeof(rates)/sizeof(rates[0]); ++r)
    {
        const double rate = rates[r];
        BassHarness h = makeBass(rate, defaults);
        renderBass(&h, (size_t)(rate * 2), 62.5, 173);
        assert(h.dsp->dbb.boostdB > 4.99f && h.dsp->dbb.boostdB <= 5.001f);
        assert(h.dsp->dbb.smoothMaxFreq >= 0.f && h.dsp->dbb.smoothMaxFreq <= 250.f);

        // A repeated packet or real edit must preserve the live delay/filter/downsampler state.
        DBB before = h.dsp->dbb;
        assert(BassBoostSetAdvanced(h.dsp, defaults));
        assert(memcmp(&before, &h.dsp->dbb, sizeof(DBB)) == 0);
        int32_t edited[7]; memcpy(edited, defaults, sizeof(edited)); edited[0] = 51; edited[3] = 300;
        assert(BassBoostSetAdvanced(h.dsp, edited));
        assert(memcmp(before.dL, h.dsp->dbb.dL, sizeof(before.dL)) == 0);
        assert(memcmp(&before.downsampler, &h.dsp->dbb.downsampler, sizeof(before.downsampler)) == 0);
        assert(memcmp(before.svf, h.dsp->dbb.svf, sizeof(before.svf)) == 0);
        assert(before.downsamplerPos == h.dsp->dbb.downsamplerPos);
        before = h.dsp->dbb;
        edited[1] = edited[2];
        assert(!BassBoostSetAdvanced(h.dsp, edited));
        assert(memcmp(&before, &h.dsp->dbb, sizeof(DBB)) == 0);

        // Measure actual time constants in processed samples, across all sample rates.
        memcpy(edited, defaults, sizeof(edited)); edited[0] = 0;
        assert(BassBoostSetAdvanced(h.dsp, edited));
        renderBass(&h, (size_t)(rate * 3), 62.5, 127);
        edited[0] = 100; assert(BassBoostSetAdvanced(h.dsp, edited));
        renderBass(&h, (size_t)(rate * .192), 62.5, 97);
        assert(fabsf(h.dsp->dbb.boostdB - 6.3212f) < .10f);
        const float rising = h.dsp->dbb.boostdB;
        edited[0] = 0; assert(BassBoostSetAdvanced(h.dsp, edited));
        renderBass(&h, (size_t)(rate * .192), 62.5, 97);
        assert(fabsf(h.dsp->dbb.boostdB - rising * expf(-1.f)) < .08f);

        // Bounds containing no FFT bin still produce a real, finite in-band filter.
        edited[0] = 150; edited[1] = 70; edited[2] = 71; edited[3] = 400;
        assert(BassBoostSetAdvanced(h.dsp, edited));
        renderBass(&h, (size_t)(rate * 2), 70.5, 511);
        assert(h.dsp->dbb.smoothMaxFreq >= 70.f && h.dsp->dbb.smoothMaxFreq <= 71.f);
        assert(h.dsp->dbb.boostdB > 1.f);
        // The 44.1 kHz last analysis bin lies above 250 Hz; advanced bounds still hold.
        assert(BassBoostSetAdvanced(h.dsp, defaults));
        renderBass(&h, (size_t)(rate * 2), 250.5, 511);
        assert(h.dsp->dbb.smoothMaxFreq <= 250.f);

        // Legacy API keeps its old sample-rate-dependent envelope until opted into advanced.
        BassHarness legacy = makeBass(rate, NULL);
        const float legacyAlpha = (float)(1.0 - exp(-1.0 / (.002 * rate)));
        assert(fabsf(legacy.dsp->dbb.gainSmoothingFactor - legacyAlpha) < 1.e-7f);
        free(legacy.dsp);
        h.dsp->fs = rate == 48000 ? 44100 : 48000;
        BassBoostSetParam(h.dsp, 5.1f);
        assert(h.dsp->dbb.downsamplerPos == 0 && h.dsp->dbb.processed == 0);
        renderBass(&h, 512, 62.5, 512);
        free(h.dsp);
    }

    // 0.1 dB precision must change real audio, not only the control/readout.
    BassHarness a = makeBass(48000, defaults), b = makeBass(48000, defaults);
    int32_t tenth[7]; memcpy(tenth, defaults, sizeof(tenth)); tenth[0] = 51;
    assert(BassBoostSetAdvanced(b.dsp, tenth));
    renderBass(&a, 144000, 62.5, 512); renderBass(&b, 144000, 62.5, 127);
    const double ea = renderBass(&a, 48000, 62.5, 512), eb = renderBass(&b, 48000, 62.5, 127);
    assert(fabs(10.0 * log10(eb / ea) - .1) < .01);
    free(a.dsp); free(b.dsp);

    // The same stream yields the same envelope with short or long callback blocks.
    a = makeBass(48000, defaults); b = makeBass(48000, defaults);
    const double first = renderBass(&a, 48000, 81.7, 511), second = renderBass(&b, 48000, 81.7, 17);
    assert(fabs(first - second) < 1.e-7);
    assert(a.dsp->dbb.boostdB == b.dsp->dbb.boostdB && a.dsp->dbb.smoothMaxFreq == b.dsp->dbb.smoothMaxFreq);
    free(a.dsp); free(b.dsp);
    puts("Dynamic bass audio passed: real 0.1 dB, measured rise/fall time, 8/44.1/48/96/192 kHz, narrow bands, legacy envelope, unchanged live state, callback independence.");
}

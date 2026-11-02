# Independent speaker optimization

The classic processing topology is derived from
[AndroidAudioMods/ViPERFX_RE](https://github.com/AndroidAudioMods/ViPERFX_RE/tree/eb65772bb01ef9040f8be034bd9315460f31394c),
`src/viper/effects/SpeakerCorrection.cpp` and `.h`, commit
`eb65772bb01ef9040f8be034bd9315460f31394c`. The upstream files credit Iscle's
verification on 2022-12-13; their project credits also retain the original
ViPER authors. Distribution provenance is recorded in `../../docs/THIRD-PARTY.md`.
The new adapter does not copy another application's UI or resurrect global
legacy parameter 65603.

## Classic and professional behavior

For each channel, the classic signal is low-pass (13500 Hz, Q 1), high-pass
(80 Hz, Q 1), then multiply by 0.5. This same attenuated signal feeds a 420 Hz,
Q 3.88 band-pass, whose output is added back. The original ViPER band-pass has
peak gain Q because its numerator is `sin(w)/2`, not `sin(w)/(2Q)`.

The independent professional controls preserve that topology:

`wet = base * HP * LP * (1 + 3.88 * amount * unitPeakBP)`

`out = outputGain * ((1 - mix) * dry + mix * wet)`

The enabled/default settings reproduce classic audio after a 20 ms entry
fade. Q changes the compensation bandwidth independently of its peak strength.
The disabled or disallowed-route state is bit-exact dry, including bypass of
the module's output gain. In enabled mix=0, output gain still applies.

This is a generic signal filter, not a measured VIVO loudspeaker calibration,
speaker excursion protector, or hardware room correction. The module is
after ViPER, before balance and the existing final output protection.
The speaker module never changes static PEQ, dynamic EQ, other effects, or
their saved parameters. The former ViPER global speaker command remains
`-ENOSYS`.

Frequency values are capped at `0.48 * sampleRate` for stable low-rate use;
the status reports all effective frequencies and the adaptation flag.
Filter coefficients and continuous gains interpolate over 20 ms while
preserving filter history. Route disallow and module off immediately bypass
and discard history. Re-entry fades in over 20 ms. Fixed-size storage and
bounded atomic snapshots avoid audio-thread allocation and blocking locks.

## Wire contract

Independent SET 196762, STATUS 132043, CAPABILITY 132044 (=1). Existing
Expansion capability 132020, MATCH_PROFILE 196761/132042, and all 21 old
Expansion modules keep their original meanings.

SET is exactly 128 int32 words: schema=1, enabled=0/1, then 12 controls in
physical units multiplied by 1000. Remaining words must be zero.

| Index | Parameter | Range | Default |
|---|---|---|---|
| p0 | mix | 0–1 | 1 |
| p1 | low cutoff Hz | 20–1000 | 80 |
| p2 | low Q | 0.5–2 | 1 |
| p3 | high cutoff Hz | 2000–22000 | 13500 |
| p4 | high Q | 0.5–2 | 1 |
| p5 | body center Hz | 100–4000 | 420 |
| p6 | body Q | 0.3–10 | 3.88 |
| p7 | body branch strength | 0–2 | 1 |
| p8 | base branch level | 0–1 | 0.5 |
| p9 | output dB | −24–12 | 0 |
| p10 | scope enum | 0 builtin / 1 all | 0 |
| p11 | transient route active | 0 / 1 | 0 |

Route active is supplied by the application's current route observer and
is never saved or exported as a preset. The native stage requires route=1
even for scope=all; the application must explicitly resolve that scope.
An unknown route defaults to off. This signal is an application report,
not native verification of an individual player's exclusive output route.

STATUS reuses the 128-word Expansion prefix: schema, requested enable,
sample rate, processed frame counter, acknowledged parameter hash,
input/output/wet peak dB ×1000. Word 11 records the fusion master enable.
The hash is the usual multiply-by-31 hash of the full packet with word 13
(transient route) replaced by zero; scope and every user control participate.

Meter indices start at status word 12. Meters 0/1/2 duplicate peak levels;
3 is actual smoothed mix, 4 reported route, 5 effective processing, 6 cutoff
adaptation, 7 completed FFT window count (zero means no valid current window).
Meters 8/9/10 are effective target low/high/body frequencies, 11 scope,
12 actual smoothed output gain dB, 13 current base level, 14 body strength,
15 body Q, 16 low Q, 17 high Q; 18/19 reserved. Filter-frequency readings
describe the accepted target while coefficients are interpolating.

Meters 20–67 and 68–115 are actual input and module-output spectra. A STATUS
GET renews an observation lease for 1500 ms; no viewer means no FFT work.
There is no audio latency from this analyzer. Each analysis uses a 2048-point
periodic Hann window, independent left/right FFTs and stereo mean power.
Full-scale coherent sine calibration is 0 dBFS, including opposite-phase
stereo. Publication is limited to at most 20 windows per second of audio.
Forty-eight requested log points run from 20 Hz to min(20 kHz, 0.48 Fs),
mapped to `round(f * 2048 / Fs)` bounded to bins 1–1023. A viewer must plot
the actual bin frequency and merge duplicates; this is not 20 Hz resolution.
The first GET, incomplete/expired windows, a setting edit, a stopped stream,
or a route/master bypass invalidates displayed spectra. GET-side observation
epochs and a 300 ms audio-gap check force a completely new window after a
pause, even when GET renews an expired lease before audio resumes. Repeated GET calls
do not advance frames or create synthetic audio.

## Validation

`speaker_optimization_audio` compares classic output with an independent
copy of the upstream process topology using the original local ViPER
Biquad and MultiBiquad classes. It also checks Q/strength independence,
sample rates, extreme settings, bit-exact bypass, output gain at mix=0,
route reconnection without stale history, FFT calibration/lease/freshness,
block-size invariance, concurrent parameter writers, and no allocations
on the audio callback.

`speaker_optimization_bridge` verifies the actual Fusion packet transport,
independent capability, audio behavior, unchanged match-profile storage,
legacy command rejection, and master/reset status invalidation.

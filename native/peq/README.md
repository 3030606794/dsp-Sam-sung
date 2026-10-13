# Parametric equalizer

The independent PEQ stage runs before JamesDSP, whose limiter remains downstream:

`input → PEQ → JamesDSP → listening controls → ViPER → balance → output`

It has up to 170 stereo second-order sections, with double coefficients and double delay states. It does not occupy the existing DDC, convolver or LiveProg controls. Transfer functions follow the standard [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/); no Neutron implementation is included.

## Protocol

Fusion reports protocol **5** through GET 131080 and capabilities **31** through GET 131081 (PEQ adds bit 8; optional RACE surround adds bit 16). Existing parameter IDs and the PEQ packet layout remain supported. Protocol 4 expanded the gain and Q ranges; protocol 5 applies gain to all seven filter types. Alpha9 requires protocol 5 to prevent old drivers from silently ignoring low-pass/high-pass/notch/band-pass gain. Update the alpha9 APK and module together and reboot; protocol-4 alpha7/alpha8 drivers are incompatible.

SET **1250** accepts one native-endian int32 array:

`[schema=1, enabled, count, type, bandEnabled, frequency_mHz, gain_mdB, Q_milli, ...]`

The length is exactly `4 * (3 + 5 * count)` bytes: at most 853 integers / 3412 bytes. Header and band enable flags are 0 or 1. Count is 0–170. Every band's fields are validated, including disabled bands. A malformed or out-of-range packet returns `-EINVAL` and leaves the previous snapshot intact.

| Field | Accepted values |
|---|---|
| Type | 0 peak, 1 low shelf, 2 high shelf, 3 low pass, 4 high pass, 5 notch, 6 band pass |
| Frequency | 20000–20000000 mHz (20–20000 Hz) |
| Gain | −100000–100000 mdB (−100 to +100 dB) |
| Q | 100–100000 (0.1–100) |

Gain affects all seven types. Peak and shelf gain retain their RBJ definitions. Low/high pass, notch and band pass multiply the complete filtered output by `10^(gain/20)`, preserving poles, zeros and the frequency/Q shape; gain 0 keeps the underlying filter active. Shelves use Q directly, with `alpha = sin(omega)/(2Q)`, so high Q can produce resonance. Band pass uses the constant-center-gain form, with center gain equal to the selected gain. The UI converts octave bandwidth to Q before transmission; bandwidth is not a separate native field.

The app's persisted JSON is independently versioned: schema 2 records the new gain semantics, while old JSON schema 1 migrates the formerly ignored four filter types' gain to 0 dB to preserve their previous sound. Reading does not overwrite the original JSON. This migration belongs in the application, not the driver; the SET 1250 binary packet always retains schema 1 and treats every received gain as intentional.

Disabled bands and zero-gain peak/shelf bands are skipped. Frequencies at or above the current Nyquist frequency are bypassed without moving the requested frequency. Sample-rate changes redesign the saved settings. Normal 44.1/48/96 kHz playback supports the full requested 20–20000 Hz range.

## Numerical realization

The same RBJ responses are realized with trapezoidal state-variable sections, using the [linear SVF derivation](https://cytomic.com/files/dsp/SvfLinearTrapAllOutputs.pdf). Positive warped frequency and damping keep the continuous prototype stable under the bilinear transform. This avoids the rounded `1 ± a1 + a2` Schur test in the earlier direct form: a legal high-gain shelf 0.001 Hz below Nyquist must not disappear because that subtraction rounds to zero. The accepted frequency, gain and Q are not clamped, and all enabled non-neutral sections below Nyquist are retained.

For reproducibility, let `g0 = tan(pi*f/fs)`, `k0 = 1/Q` and `A = 10^(gain/40)`. The high/band/low prototype output weights are:

| Type | Warped g | Damping k | High / band / low weights |
|---|---|---|---|
| Peak | g0 | k0/A | 1, k0*A, 1 |
| Low shelf | g0/sqrt(A) | k0 | 1, k0*A, A² |
| High shelf | g0*sqrt(A) | k0 | A², k0*A, 1 |
| Low pass | g0 | k0 | 0, 0, A² |
| High pass | g0 | k0 | A², 0, 0 |
| Notch | g0 | k0 | A², 0, A² |
| Band pass | g0 | k0 | 0, k0*A², 0 |

Direct high/band/low mixing preserves deep-cut precision instead of subtracting a large amplified signal from dry input. The app's response preview evaluates the equivalent RBJ analog polynomial after the same bilinear frequency mapping.

## Continuous audio and limits

Two fixed-size filter banks crossfade over 20 ms when an effective setting changes. Each bank keeps constant coefficients while processing; this avoids interpolating feedback coefficients. Edits arriving during a fade are coalesced to the latest complete snapshot and adopted after the current fade, at the next block. Redundant packets preserve filter history. Reset and sample-rate changes clear old states and fade in the latest settings.

Control-thread coefficient preparation uses a separate snapshot lock. The audio thread never waits for that lock and continues its previous filters if preparation is in progress. Processing allocates no memory and preserves history across arbitrary callback lengths. Settings/configuration follow the synchronization contract documented in `PeqProcessor.h`.

There is no hidden gain compensation or automatic preamp. Stacked boosts can engage the downstream James limiter; +100 dB is a filter gain parameter, not a guarantee of +100 dB audible output loudness. High-Q/high-boost poles can also have a long settling time. Nonfinite input is sanitized. Individual section states and outputs saturate at ±1e100 to prevent cascades such as 170 × +100 dB from overflowing double precision; they never substitute dry input or silently remove a legal filter. The stage output has a numerical bound of ±1e6 before returning to the float audio chain. These are numerical overflow guards, not another user output-limiter control. Responses below those bounds retain their requested gain; extreme stacked cuts can reach floating-point silence.

## Verification and performance

`peq_audio` tests measured sine-wave transfer values for all seven types, equal-and-opposite peak cancellation, the 170th band, exact neutral bypass, packet rejection, stereo isolation, callback continuity, rate changes/reset, repeated/concurrent updates, nonfinite input, and all seven types at 170 bands with all gain/Q corners across 8–192 kHz. Low/high-pass, notch and band-pass tests compare stereo output against their zero-gain waveform scaled by ±12/100 dB across Q0.1/Q100, 20 Hz–20 kHz and 44.1–192 kHz; separate measured stop-band tests reject an accidental 0 dB bypass. Extended-range cases compare against an independent long-double direct-form RBJ reference and check retention 0.001 Hz below Nyquist. Full-engine integration additionally verifies the protocol-5 handshake, ±100 dB/Q100, the maximum 3412-byte SET 1250 packet, out-of-range rejection and that even a 170-band extreme cascade precedes the James limiter.

Local Windows x86-64 validation (Zig 0.13/Clang `-O2`) measured a 10 kHz peak at Q 100 and 48 kHz sampling as **+100.000000 dB** and **−99.999989 dB**. The +100 dB test renders 25 seconds before measuring its final half-second so the high-Q poles can settle.

The benchmark processes five seconds of stereo 48 kHz audio through **170 enabled peak filters with distinct log-spaced frequencies**. Nonzero ±0.3 dB/Q1 took **0.375658 s (13.31× realtime)**; all +100 dB/Q100 took **0.375667 s (13.31× realtime)** with finite output under numerical saturation. The more robust realization costs more CPU than the earlier direct form. These are host PEQ-only measurements, excluding the other engines and with settled settings; they are not phone CPU/battery guarantees. CI prints its own measurements, and transition processing can run both banks.

Run the host tests through the normal native CMake build with `FUSION_BUILD_TESTS=ON`; add `FUSION_SANITIZE=ON` for AddressSanitizer. Android builds compile the same processor for arm64-v8a and armeabi-v7a without the test executable.

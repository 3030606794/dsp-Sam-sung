# Parametric equalizer

The independent PEQ stage runs before JamesDSP, whose limiter remains downstream:

`input → PEQ → JamesDSP → listening controls → ViPER → balance → output`

It has up to 170 stereo biquads, with double coefficients and double delay states. It does not occupy the existing DDC, convolver or LiveProg controls. Filter equations are independently implemented from the standard [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/); no Neutron implementation is included.

## Protocol

Fusion reports protocol **3** through GET 131080 and capabilities **15** through GET 131081 (PEQ adds bit 8). Existing parameter IDs remain supported, but older Fusion APKs that require protocol 2 reject this driver: update the APK and module together.

SET **1250** accepts one native-endian int32 array:

`[schema=1, enabled, count, type, bandEnabled, frequency_mHz, gain_mdB, Q_milli, ...]`

The length is exactly `4 * (3 + 5 * count)` bytes: at most 853 integers / 3412 bytes. Header and band enable flags are 0 or 1. Count is 0–170. Every band's fields are validated, including disabled bands. A malformed or out-of-range packet returns `-EINVAL` and leaves the previous snapshot intact.

| Field | Accepted values |
|---|---|
| Type | 0 peak, 1 low shelf, 2 high shelf, 3 low pass, 4 high pass, 5 notch, 6 band pass |
| Frequency | 20000–20000000 mHz (20–20000 Hz) |
| Gain | −24000–24000 mdB (−24 to +24 dB) |
| Q | 100–30000 (0.1–30) |

Gain affects peak and shelf filters. Low/high pass, notch and band pass retain their filter response at gain 0; their gain field is ignored. Shelves use Q directly, with `alpha = sin(omega)/(2Q)`, so high Q can produce resonance. Band pass uses the constant 0 dB center-gain form. The UI converts octave bandwidth to Q before transmission; bandwidth is not a separate native field.

Disabled bands and zero-gain peak/shelf bands are skipped. Frequencies at or above the current Nyquist frequency are bypassed without moving the requested frequency. Sample-rate changes redesign the saved settings. Normal 44.1/48/96 kHz playback supports the full requested 20–20000 Hz range.

## Continuous audio and limits

Two fixed-size filter banks crossfade over 20 ms when an effective setting changes. Each bank keeps constant coefficients while processing; this avoids interpolating feedback coefficients. Edits arriving during a fade are coalesced to the latest complete snapshot and adopted after the current fade, at the next block. Redundant packets preserve filter history. Reset and sample-rate changes clear old states and fade in the latest settings.

Control-thread coefficient preparation uses a separate snapshot lock. The audio thread never waits for that lock and continues its previous filters if preparation is in progress. Processing allocates no memory and preserves history across arbitrary callback lengths. Settings/configuration follow the synchronization contract documented in `PeqProcessor.h`.

There is no hidden gain compensation or automatic preamp. Stacked boosts can engage the downstream James limiter. Nonfinite input is sanitized; pathological internal overflow clears the affected section's states, and the stage output has a numerical bound of ±1e6, far above normal audio amplitude. This is an overflow guard, not a second output limiter. Ordinary finite responses below that bound retain their requested gain.

## Verification and performance

`peq_audio` tests measured sine-wave transfer values for all seven types, equal-and-opposite peak cancellation, the 170th band, exact neutral bypass, packet rejection, stereo isolation, callback continuity, rate changes/reset, repeated/concurrent updates, nonfinite input, and all seven types at 170 bands with endpoint frequencies/gains/Q across 8–192 kHz. Full-engine integration additionally verifies SET 1250 and that PEQ precedes the James limiter.

The benchmark processes five seconds of stereo 48 kHz audio through **170 enabled peak filters, all with nonzero ±0.3 dB gain and distinct log-spaced frequencies**. On the local Windows x86-64 host, Zig 0.13/Clang `-O2`, this took **0.107529 s (46.50× realtime)**. This is a host PEQ-only measurement, excluding the other engines and with settled settings; it is not a phone CPU/battery guarantee. The CI test prints its own result, and transition processing can run both banks.

Run the host tests through the normal native CMake build with `FUSION_BUILD_TESTS=ON`; add `FUSION_SANITIZE=ON` for AddressSanitizer. Android builds compile the same processor for arm64-v8a and armeabi-v7a without the test executable.

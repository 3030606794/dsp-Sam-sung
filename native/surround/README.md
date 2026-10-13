# Band-limited RACE surround

This is an independent implementation of the publicly described recursive
Ambiophonic crosstalk elimination principle. It contains no Neutron code or
binaries and does **not** claim sample-identical output. The reference APK was
used to establish control names, units, ranges and eight preset values; its
filter order, delay interpolator and feedback topology were not recovered.

Reference: Ralph Glasgal and Robin Miller, *True-to-Life Sound Reproduction
using Recursive Ambiophonic Crosstalk Elimination (RACE)*,
https://www.filmaker.com/papers/RGRM-RACE_rev.pdf

## Model

`yL = xL - g D B(yR)`, `yR = xR - g D B(yL)`.

`B` is a cascade of second-order Butterworth high-pass at the lower frequency
and low-pass at the upper frequency. `g = 10^(-attenuation_dB/20)`.
`D` is a delay in microseconds, converted to samples at the actual host rate
and interpolated linearly. This is negative cross-channel cancellation, not
the positive crossfeed intended for headphone speaker simulation.

Both filters have magnitude at most one; the delay interpolator also has
magnitude at most one. Minimum allowed attenuation of 0.5 dB keeps feedback
gain below one (approximately 0.9441). The recursion is stable but can boost
certain signals; the theoretical frequency-domain gain bound is
`1/(1-g)`, about 17.9 at the lowest attenuation. RACE therefore runs before the
existing James output limiter. No automatic level normalization is added: an
always-on safety gain of `1-g` would otherwise reduce even unaffected audio
by about 25 dB at minimum attenuation. The wrapper's final hard clamp remains
an emergency boundary; it is not the normal RACE level-management mechanism.
In native tests that explicitly disable the whole James stage, that limiter
is unavailable and high-level boosted signals can hit the wrapper's clamp.

At 8 kHz, 20 us is only 0.16 samples. When delay is less than one sample, the
current filtered sample has an instantaneous contribution. Both channel
outputs are solved together through the 2x2 linear system, then filter and
delay states are advanced once. The actual delay is never rounded up to one
sample or truncated to zero. Filter coefficients and states use double.

At host rates where the upper corner exceeds 45% of sample rate, **both**
corners are scaled by `(0.45 * sampleRate / upperHz)` to preserve their ratio.
This is a documented low-rate adaptation, not a claim about Neutron behavior.
The interface's response chart is the two-filter magnitude response, not the
complete two-channel RACE transfer function.

## Control and integration

Protocol remains 5. Capability bit 16 advertises this optional processor, so
the new capability mask is 31. Parameter ID 196609 is exactly six native-endian
int32 words:

| Word | Meaning | Accepted values |
|---|---|---|
| 0 | Schema | 1 |
| 1 | Enabled | 0 or 1 |
| 2 | Lower frequency in Hz | 10..800 |
| 3 | Upper frequency in Hz | 4000..16000 |
| 4 | Attenuation in milli-dB | 500..10000 |
| 5 | Delay in microseconds | 20..210 |

Factory slot 1 is 200 Hz / 9000 Hz / 5.5 dB / 35 us, initially disabled.
Eight independently editable preset slots belong to the application; only
the selected parameters are sent to the driver.

Stage order is PEQ → RACE → JamesDSP → listening controls → ViPER → balance
→ finite-output clamp. The stage is independent of the James and ViPER toggles
and is bypassed by the existing overall AudioEffect switch.

Filter coefficients are prepared on the control thread. The audio thread
uses a nonblocking pending-state lock, two preallocated filter/delay banks and
a 20 ms linear crossfade for changes. Rapid changes are coalesced; a running
fade finishes before the latest pending snapshot is adopted. Duplicate
settings do not clear filter history. The processing callback allocates no
memory. Disabled steady state is exact byte-preserving bypass. Reset/rate
changes clear both banks and fade in the current settings from dry.

## Validation

`surround_tests` measures real input/output against an independent complex
transfer-function oracle for in-phase and anti-phase signals, covers all
parameter corners at 8/44.1/48/96/192 kHz, checks tail decay, exact chunk
invariance for 1/17/512/960-frame calls, stereo symmetry, disabled bypass,
duplicate updates, concurrent control updates, malformed upstream floats and
audio-thread allocations. `fusion_tests` additionally tests the real
AudioEffect command bridge, a cross-channel impulse response and strict
packet rejection. These host tests do not replace device listening tests.

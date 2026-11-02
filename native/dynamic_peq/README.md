# Independent dynamic PEQ

This stage runs immediately after the original static PEQ and before surround.
It does not change the original processor, parameters, presets, or protocol.

`SET 196720` accepts signed Int32 words:

`[1, enabled, bandCount, band0 (12 words), ...]`

Maximum 16 bands. Each band is:

| Word | Value | Allowed range |
| --- | --- | --- |
| 0 | Type: peak / low shelf / high shelf | 0 / 1 / 2 |
| 1 | Band enabled | 0 or 1 |
| 2 | Frequency, milli-Hz | 20000 to 20000000 |
| 3 | Static gain, milli-dB | -24000 to 24000 |
| 4 | Q, thousandths | 100 to 30000 |
| 5 | Dynamic processing enabled | 0 or 1 |
| 6 | RMS threshold, milli-dBFS | -90000 to 0 |
| 7 | Ratio, thousandths | 1000 to 20000 |
| 8 | Maximum downward reduction, milli-dB | 0 to 24000 |
| 9 | Attack time, microseconds | 100 to 500000 |
| 10 | Release time, microseconds | 5000 to 3000000 |
| 11 | Knee width, milli-dB | 0 to 24000 |

Validation checks the complete packet before accepting anything. Frequency
requests at or above `0.499 * sampleRate` remain stored and are bypassed until
a rate supports them. A redundant SET preserves filter and detector history.

Detection observes the stereo input of this independent module, before any of
its own bands. The stereo link uses maximum channel power; phase cancellation
and prior bands' EQ gains cannot suppress or inflate the sidechain. Bell
detection has unity center gain. Shelf detection uses unity-passband LP/HP
with Q capped at Butterworth to avoid resonance bias. Power is attack/release
smoothed, converted to RMS dBFS and fed to a quadratic soft-knee compressor.
The resulting positive attenuation is bounded by each band's requested range.
The effective gain is `static gain - dynamic reduction`.

Audio filtering uses ordinary RBJ peak and Q-based shelf responses, realized
as trapezoidal state-variable sections. Dynamic changes interpolate positive
circuit parameters, not biquad feedback coefficients. Parameter snapshots
crossfade two complete banks over 20 ms. Exactly unchanged bands retain their
states; disabled processing is an immediate sample-exact bypass. The callback
does not allocate and never waits for the parameter mutex.

`GET 132000` returns Int32 `1` as an independent capability flag, preserving
the existing protocol 5 and exhausted capability masks.

`GET 132001` returns exactly 40 Int32 words:

| Offset | Meaning |
| --- | --- |
| 0 | Schema 1 |
| 1 | Requested module enabled |
| 2 | Requested band count |
| 3 | Host sample rate |
| 4 | Frames actually processed, low 31 bits |
| 5, 6 | Latest processed block's input/output sample peak, milli-dBFS |
| 7 | Applied payload fingerprint; zero during bank crossfade |
| 8 to 23 | Per-band detector RMS, milli-dBFS |
| 24 to 39 | Per-band applied attenuation magnitude, milli-dB |

Meters floor at -120 dBFS and cap at +48 dBFS. Inactive or unavailable band
meters are -120000 / 0. GET reads an atomic snapshot and never changes DSP,
advances counters, or enables processing. While the complete effect is off,
Fusion masks signal meters but retains the independent module configuration.
The fingerprint is Java `IntArray.contentHashCode()` over the exact schema-1
payload (`h=1`, then `h=31*h+word`, with 32-bit wraparound). It identifies the
settings actually in the active audio bank, not merely the newest control
request. During a short configuration crossfade it is zero and band meters
describe the target bank. Clients should only overlay live attenuation when
the fingerprint matches their current payload.

Native test targets are `dynamic_peq_tests` (independent signals, protocol,
timing, rates, real-time allocation and concurrency) and
`dynamic_peq_bridge_tests` (actual Fusion AudioEffect protocol and processing).

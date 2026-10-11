# DSP Fusion native driver

The installed driver is **one `libdspfusion.so` and one registered Android AudioEffect**. It owns private JamesDSP and ViPER effect instances and processes:

`input → JamesDSP → independent listening controls → ViPER → finite-output clamp → output`

The old drivers' `AELI` symbols are renamed internally; the linker exports only the Fusion `AELI`. Do not register the original JamesDSP or ViPER UUIDs for this library.

| Field | Value |
|---|---|
| Implementation UUID | `30306067-9400-4d53-9000-000000000001` |
| Type UUID | `30306067-9400-4d53-9000-000000000000` |
| Control protocol | 1 |
| Capabilities | 7: James = 1, ViPER = 2, listening controls = 4 |
| Channels | Stereo only |
| External PCM formats | float, signed 16-bit, signed 32-bit, packed signed 24-bit, signed 8.24 |
| Internal format | Interleaved stereo float; child output uses WRITE |
| Output access | WRITE and ACCUMULATE |
| Accepted sample rates | 8–192 kHz, equal input/output rates; tested rates are listed in CI/tests |
| Offload | Not implemented; requires a software audio effect path |

## Wire protocol

Use Android `EFFECT_CMD_SET_PARAM` / `EFFECT_CMD_GET_PARAM`, `effect_param_t.psize = 4`, a native-endian 32-bit parameter ID, then the value. Replies use the standard Android parameter/status layout. An accepted packet is not evidence that the current playback route traverses the effect.

| ID | Operation | Value |
|---|---|---|
| 131072 | SET/GET | James stage enable, int32 0 or 1 |
| 131073 | SET/GET | ViPER stage enable, int32 0 or 1 |
| 131080 | GET | Protocol version, int32 = 1 |
| 131081 | GET | Capability bits, int32 = 7 |
| 196608 | SET | 44 bytes: int32 enable followed by ten int32 controls |

The ten listening controls retain the screenshot order: temperature, bass extension, bass punch, thickness, vocal presence, female vocal coloration, low sibilance, high sibilance, transients, air. Values are −100 to +100. This is an independent DSP model; it does not contain HiBy's proprietary engine or claim bit-identical MSEB output. See [`tone/README.md`](tone/README.md) and the application compatibility document for the model's scope.

Existing JamesDSP IDs are retained, including 2-byte switches/presets, float arrays, segmented strings/IRs, and GET 19998–20002 / 30000–30003. These IDs intentionally retain their original units. In particular, James tube drive (ID 150) is an int16 in thousandths of a dB, whereas the ViPER tube switch (65583) is an int32 boolean.

Legacy James GET 20001 reports the James engine's internal processing rate, not necessarily the host rate: at a 96 kHz host rate it reports 48 kHz because James uses its existing internal resampler. `EFFECT_CMD_GET_CONFIG` returns the actual host input/output rate. The ViPER and listening-control stages run at that host rate.

ViPER controls use their original 32-bit integer protocol. IDs 65552 (EQ band/gain), 65570/65571 (dynamic-system frequency pairs), and 65572 (side gains) take two int32 values. Reverb IDs 65560–65564 use integer percentages 0–100; their meanings are room size, width, damping, wet, dry. EQ gain is hundredths of a dB, differential delay is hundredths of a millisecond, output/pan/limit are percentages. The application's `ViperParameters.kt` is the UI range and encoding specification.

Incomplete upstream paths return `-ENOSYS`: native ViPER IR upload/convolution, headphone VHE, AGC, FET compressor, and native ViPER DDC coefficient upload. VHE disable is accepted. User DDC and IR processing are provided by the existing JamesDSP controls. The main ViPER environment/field-surround control is implemented and is distinct from the incomplete VHE path.

## Safety and integration behavior

- Global disable performs dry bypass; same-format WRITE preserves the original bytes. Stage switches are independent of the global effect state.
- Packet sizes, ranges, finite numeric values and segmented-transfer state are checked before calling the older engines. Strings have a terminating NUL and repeated uploads free the previous buffer.
- Processing uses bounded chunks with fixed wrapper work buffers. Upstream algorithms may still perform their own allocations when their block configuration changes.
- Control commands serialize access to both engines. The audio thread uses a nonblocking lock and temporarily emits dry audio if a control operation holds it. Large IR/filter changes may therefore produce an audible transition; this initial implementation does not promise seamless arbitrary filter replacement.
- ViPER's original final limiter remains in its processing stage, including its approximately 255-sample delay. The final clamp prevents nonfinite/over-range processed output; ACCUMULATE still honors the host's mixing semantics.
- ViPER reverb retains its original fixed sample delays. Its time behavior changes with sample rate. The unified processing order, floating-point toolchain and corrected upstream bugs can also cause differences from separately installed old binaries.

Local upstream corrections include effect-handle disposal, deterministic reverb initialization, PConv pointer ownership, stereo buffer lengths/offsets, and the tube simulator's skipped frames and unintended reset disable. Algorithm additions are kept in `fusion/` and `tone/`; changes to vendored code should remain small and reviewable.

## Build and test

Host integration tests compile and execute both real engines:

```sh
cmake -S native -B build/host -DFUSION_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
```

Add `-DFUSION_SANITIZE=ON` for AddressSanitizer on the host. Tests cover effect lifecycle, UUID and protocol, old James queries, five PCM formats, in-place/chunked bypass, accumulation, invalid packets, upload sequencing, real reverb tails, available ViPER stages and the listening-control stage. Passing these tests does not establish Android HAL registration or device playback compatibility.

Build the Android library once per ABI:

```sh
cmake -S native -B build/driver-arm64-v8a \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/26.3.11579264/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build build/driver-arm64-v8a --parallel 2
```

Repeat with `armeabi-v7a`. GitHub Actions packages both libraries in the single Magisk module and validates the release artifacts. No device is required for compilation or host tests.

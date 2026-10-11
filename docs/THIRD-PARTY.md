# Source provenance

DSP Fusion retains upstream author notices and licenses. It is an independent integration, not an official JamesDSP, ViPER or HiBy release.

| Component | Source revision | Included material |
|---|---|---|
| Modern JamesDSP application | timschneeb/RootlessJamesDSP `eff31a6da995d6b90463a87b2512dd955e3c66a6` (1.6.14) | Application, UI, JNI helpers. Root LICENSE is the upstream GPLv3 text. |
| JamesDSP engine | james34602/JamesDSPManager / ThePBone fork `1b9f89dbd4b332a07724beb41d58bc2ffcb05395` | `app/src/main/cpp/libjamesdsp/Main/libjamesdsp`; original `Main/LICENSE` retained. Individual dependency notices also retained. |
| ViPER RE engine | WSTxda/ViPERFX_RE `43a66a810c00b1a833463d73a9f6f67ea9937973` (v0.6.3) | `native/viper/src`; accompanying upstream module GPLv2 text retained as `native/viper/LICENSE.module`. Original credits: ViPER520, Zhuhang, Martmists, Iscle and subsequent fork contributors. |
| Listening controls | DSP Fusion contributors | Independently implemented; inspired by the ten publicly visible MSEB controls. No HiBy APK, proprietary library or decompiled application source is distributed. |

The ViPER source tree does not contain separate license headers for every native file; `LICENSE.module` records the supplied distribution notice rather than asserting additional licensing terms. All upstream source is included for inspection and modification, with the integration changes visible in repository history.

The redesigned WSTxda ViperFX application is not copied into this project. Its user-facing parameter behavior was studied to implement a new control panel.

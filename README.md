# DSP Fusion

一个应用、一个 Magisk 模块，统一 JamesDSP、ViPER RE 与十项听感调节。

应用及配套驱动：0.1.0-alpha2。Android 8+，ARM64/ARMv7。此版本需要 root 和配套模块。

alpha2 修复打开应用时 ViPER 参数页的空指针崩溃，以及三星音频 HAL 无权加载驱动的文件标签问题。旧版用户可覆盖升级 APK 和模块，重启后使用，无需清除设置。

## 下载和安装

从本仓库 [Releases](https://github.com/3030606794/DSP-mobile-version/releases) 下载 APK 和 Driver ZIP。私有仓库需登录对应账号。

安装前阅读 [新版与旧版兼容性、安装及回退](docs/COMPATIBILITY.zh-CN.md)。先禁用旧 JamesDSP、Viper 和 AML 模块并重启，再刷入新模块并重启。APK 包名是 `com.dspfusion.android`。

## 功能

- 保留 JamesDSP 1.6.14 原有功能与预设管理。
- 新增 ViPER RE 已实现的频谱扩展、十段均衡、环绕声场、差分环绕、混响、动态系统、电子管、低音、清晰度、听觉保护、AnalogX、扬声器优化与输出控制。
- DDC 和卷积共用 JamesDSP 的文件导入与处理入口。
- 十项海贝式听感控制：温度、下潜、弹性、厚度、人声、女毒、两段齿音、瞬态和空气声。它们使用自主实现的滤波与动态处理，未宣称复制海贝专有算法。参见 [映射和测试](native/tone/README.md)。

系统只注册一个融合效果，内部顺序为 JamesDSP → 听感调节 → ViPER。新增单项音效默认关闭，两套引擎可独立旁路，支持设备配置与预设。

## 构建

GitHub Actions 同时构建签名 APK 与统一驱动，运行 JVM 参数测试和原生音频测试，通过后生成预发布。

Java 17、Android SDK35、NDK26.3.11579264、CMake3.22.1、Gradle wrapper：

```sh
./gradlew :app:assembleRootFdroidRelease :app:testRootFdroidReleaseUnitTest
cmake -S native -B build/host -DFUSION_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build build/host
ctest --test-dir build/host --output-on-failure
```

发布签名仅保存在 GitHub Actions Secrets；仓库不包含私钥、手机备份或海贝二进制。源码及作者说明见 [第三方来源](docs/THIRD-PARTY.md) 和 [原应用说明](docs/UPSTREAM-APPLICATION-README.md)。

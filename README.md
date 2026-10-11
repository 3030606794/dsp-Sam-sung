# dsp音效

一个 APK、一个 Magisk 驱动，整合 170 段参数均衡器、JamesDSP、ViPER RE 和十项 MSEB 听感调音。应用与驱动版本均为 **0.1.0-alpha6**，Android 8+，ARM64/ARMv7，需 root。应用包名和签名保持不变，可覆盖升级。

alpha6 增加独立 IIR 参数均衡器，使用协议 3，必须同时升级 APK 和驱动。保留 alpha5 的统一界面与独立展开/开关操作。

- 参数均衡器的响应曲线固定顶部，下方频段列表最多 170 段；支持峰值、低架、高架、低通、高通、带阻和带通。
- 每段独立频率与 Q；峰值和架式滤波器支持增益，适用类型支持 Q/BW 编辑。可选滑杆步值和增益范围，沿用原 DSP 颜色、Material 控件和动画。
- 旧幅度响应曲线和 AutoEQ 入口保留，可与新参数均衡器切换使用；不会把旧曲线节点误当作等价的峰值滤波器。

- 活动设备配置与输出控制合并为顶部固定卡片；左右平衡支持双击归零。
- 底部中央总开关控制整条音效链；删除两个处理段开关和顶部实现说明。
- 删除 ViPER 输出音量/限制器、十段 EQ、扬声器优化的界面、命令和算法，以及 ViPER 重置入口。
- 听感调音显示为 MSEB：左右关键词、左减右加按钮每次一步、Material 滑杆实时调节、双击归零，保留三档范围。
- 所有效果默认折叠，点击标题仅展开/收起，右侧开关独立控制启用；ViPER 拆分独立卡片并配对应图标，复用 DSP 颜色和动画。
- 使用用户提供的 SVG：固定黑色图案、白色背景，无系统主题色图层。
- 保留 JamesDSP 的均衡器、动态低音、压扩器、混响、声场、交叉馈送、VDC、卷积、LiveProg，以及其余 ViPER 效果。

十项听感调音使用独立的滤波与动态模型近似海贝 MSEB 的调节方式，不分发海贝私有算法。参见 [映射说明](native/tone/README.md)。

安装、升级与回退见 [兼容性说明](docs/COMPATIBILITY.zh-CN.md)。每次构建运行应用界面、参数、安装器及原生音频回归，先生成发布草稿；验证结果和实际测试范围记录在 Releases。

## 构建

使用 Java 17、SDK35、NDK26.3.11579264、CMake3.22.1 和仓库 Gradle wrapper：

```sh
./gradlew :app:assembleRootFdroidRelease :app:testRootFdroidReleaseUnitTest
cmake -S native -B build/host -DFUSION_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build build/host
ctest --test-dir build/host --output-on-failure
```

源码构建保留单个融合库和单个公开音效实例，处理顺序为参数均衡器 → JamesDSP → MSEB → ViPER → 左右平衡。

签名私钥保存在 GitHub Actions Secrets，不提交手机备份、用户 IR 文件或海贝二进制。上游作者、许可证和改动出处见 [第三方说明](docs/THIRD-PARTY.md) 与 [原应用说明](docs/UPSTREAM-APPLICATION-README.md)。

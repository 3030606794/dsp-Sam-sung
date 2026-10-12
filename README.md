# dsp音效

一个 APK、一个 Magisk 驱动，整合 170 段参数均衡器、JamesDSP、ViPER RE 和十项 MSEB 听感调音。当前版本为 **0.1.0-alpha8**，Android 8+，ARM64/ARMv7，需 root。应用包名和签名保持不变，可覆盖升级。

alpha8 修复 Q/BW 切换后的主滑杆，设置改为滑杆与可输入数值框，增加 Q/BW 和频率步长、频段红点及实时标签。本次不改变 DSP 算法和协议，仍使用协议 4；**已有 alpha7 驱动只需更新 APK**，首次安装或从更早版本升级才需安装新驱动。保留 Q 0.1–100、增益 ±100 dB 和独立展开/开关操作。

- 参数均衡器的响应曲线固定顶部，下方频段列表最多 170 段；支持峰值、低架、高架、低通、高通、带阻和带通。
- 每段独立频率与 Q；峰值和架式滤波器支持增益，适用类型支持 Q/BW 编辑。增益滑块步长 0.01–2 dB，增益范围 1–100 dB，Q/BW 步长 0.01–100，频率步长 1–1000 Hz；沿用原 DSP 颜色、Material 控件和动画。
- 曲线左右各留 2dp，线宽 1.25dp，红点和标签实时显示所选频段的频率与增益；可打开 Q/BW 和频率滑杆，两侧加减按设定步长调节。按频段数量在 20–20000 Hz 对数均分，已有配置可明确重新分布频率，保留其他参数。
- 删除旧曲线/AutoEQ 编辑器、搜索客户端和服务器设置；历史曲线字符串仅保留备份，不再处理。

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

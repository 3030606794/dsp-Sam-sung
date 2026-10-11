DSP Fusion 0.1.0-alpha2 修复用户手机上打开应用即闪退的问题。

崩溃原因：ViPER 参数页创建滑块时，在滑块尚未加入界面前读取了它自己的设置管理器，触发空指针。现改为使用页面已经初始化的设置存储，并补充真实 Android 界面创建、恢复与调节的回归测试。

另修复三星音频 HAL 无权加载驱动的问题：vendor 音效库和配置使用正确的 SELinux 文件标签，不新增权限规则、不关闭 enforcing。驱动算法与参数协议保持不变。

已安装融合版的用户请覆盖升级 `DSP-Fusion-0.1.0-alpha2.apk` 和 `DSP-Fusion-Driver-0.1.0-alpha2.zip`，然后重启。同包名、同签名，保留设置，无需卸载或清除数据。

首次安装才需要 APK 和 Driver ZIP，请阅读随附 `COMPATIBILITY.zh-CN.md`。

海贝式十项调节是近似听感实现；没有分发海贝算法或宣称逐样本复刻。DDC/卷积共享 JamesDSP 入口；上游 RE 中未完成的 AGC/FET/VHE 不提供无效控制。

本发布由 GitHub Actions 构建并执行回归检查。实机启动验证结果将在发布说明中补充；音效与听感仍需实际试听。

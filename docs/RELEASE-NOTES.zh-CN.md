DSP Fusion 0.1.0-alpha1 将 JamesDSP 与 ViPER RE 接入一个驱动和一个应用，并提供十项独立实现的听感调节。

下载 APK 和 Driver ZIP。安装前请先阅读随附 `COMPATIBILITY.zh-CN.md`：禁用旧 JamesDSP、Viper（包括 AIDL）和 AML 模块，安装新模块后重启，再安装 APK。新应用使用独立包名和持久签名。

海贝式十项调节是近似听感实现；没有分发海贝算法或宣称逐样本复刻。DDC/卷积共享 JamesDSP 入口；上游 RE 中未完成的 AGC/FET/VHE 不提供无效控制。

本发布由 GitHub Actions 构建并执行离线检查，尚未在用户手机安装测试。请保留旧版安装包和预设以便回退。

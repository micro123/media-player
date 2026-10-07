# 0.12.0 构建与发布验证

日期：2026-10-07，设备 Moto X40 / Android 16。

- 正式应用 ID：`io.github.micro123.mediaplayer`；应用、core/player 与四个源码根目录已统一命名，设备测试组件和 provider authority 同步更新。
- 版本：0.12.0 / versionCode 12，集中配置在 gradle.properties。
- 本地 debug、设备测试 APK、签名 release、lint 与 JVM 测试构建成功。
- JVM 共 76 项：72 通过、4 项需要隔离 SMB/NFS 服务器的用例按设计跳过、0 失败。
- 新应用 ID 在设备上安装 debug 与设备测试包；ExternalMediaOpenTest 共 4 项通过，覆盖跨应用视频/音乐打开及缺少读取授权。旧版应用及数据保留。
- actionlint 检查两个工作流通过，Python 脚本语法和本地文档链接检查通过。
- 云端 Android CI：37643502624 与 37644873386 成功；下载首轮检查报告复核 76 项 JVM 测试、4 跳过、0 失败。
- 首次包含原生源码归档的发布任务 37644341489 按用户要求取消；改为 README 提供对应源码下载和重建方法，不在发布任务中下载源码。
- 正式 Release 工作流 37644983410 成功，build/publish 两个任务通过；APK 签名、包名、版本与对齐检查通过。
- 公开发布标签：v0.12.0；对应源码提交：58a4002746a8c421bd57ee71bde66c2f68a4bd6c。
- 发布附件：media-player-0.12.0.apk、SHA256SUMS、BUILD_INFO.json、media-player-0.12.0-mapping.zip。
- 云端 APK SHA-256：d49e5fe5aee3ca84e27d7236fed6a3de49def0c62bc44b11e9f0b53c3ebcd68c。
- 专用签名证书 SHA-256：fab3f83804b6c0638e1cca15f6540ea874d951eacfb657ef7c1856f1a4a5f139，与本机创建的 RSA 3072 位密钥一致。
- GitHub 仓库已设置简介、Releases 主页、10 个 Topics，识别顶层 GPL-3.0 许可证。
- 四个签名 Secrets 和公开证书指纹 Variable 已配置；私钥、真实密码与构建产物不纳入 Git。

本次设备回归聚焦包名更改后容易失效的跨应用入口；未重复运行全部仪器测试，普通 CI 也不运行设备测试或真实 NAS 集成测试。

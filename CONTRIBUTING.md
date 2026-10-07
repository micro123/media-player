# 贡献说明

较大的功能调整可以先在 Issue 中说明使用场景，修复和功能提交请保持改动范围清楚。

## 开发环境与验证

使用 JDK 25、Android SDK Platform 37.0、Build Tools 36.0.0 和项目 Gradle Wrapper。
构建命令及工程组织见 [README](README.md)。本机 SDK 配置、缓存、APK 与签名材料不纳入 Git。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

SMB/NFS 真实服务集成测试需要项目自建隔离服务器；未设置 `PLAYER_NETWORK_TEST_HOST` 时，这 4 项测试会跳过。
普通 CI 验证其余 JVM 测试，编译设备测试 APK，但不声称已执行真机测试。
需要验证实际播放、系统授权、手势或窗口生命周期时，再在测试设备运行相关仪器测试：

```sh
./gradlew :app:connectedDebugAndroidTest
```

测试服务器、素材生成与端口配置见 `tools/network-test-server/` 及 [网络实现记录](docs/network-locations-0.7.md)。
测试应创建自己的素材和条目，并清理自身数据；不要把 NAS 密码、个人媒体、密钥或设备私有配置提交到仓库。

## 提交与版本

提交前查看 `git diff` 和 `git diff --check`，在 PR 中写明变更后的行为以及完成的验证。
提交信息可使用 `feat:`、`fix:`、`refactor:`、`test:` 或 `docs:` 前缀。
历史验证记录应保留当时的版本、应用 ID 和测试边界，不用新版本信息改写过去的结果。

正式版本修改 `gradle.properties` 的 `appVersionName` 与递增的 `appVersionCode`，添加 `docs/releases/版本号.md`，通过 CI 后由维护者创建匹配标签。
签名 Release 只能使用维护者配置的仓库 Secrets，具体操作见 [发布流程](docs/github-actions.md)。

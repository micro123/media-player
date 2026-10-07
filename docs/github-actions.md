# GitHub 自动构建与发布

仓库：`micro123/media-player`。0.12.0 起正式应用 ID 为 `io.github.micro123.mediaplayer`。
GitHub CLI 可以管理仓库、Actions Secrets、工作流运行与 Release；本机已登录维护者账号。

## 日常 CI

`.github/workflows/build.yml` 在 main 提交、针对 main 的 PR 和手动触发时运行。
使用 Ubuntu 24.04、Temurin JDK 25、SDK Platform 37.0、Build Tools 36.0.0 与项目 Gradle Wrapper。
执行 JVM 单元测试和 debug lint，构建 debug APK 与设备测试 APK，上传调试 APK 及检查报告。

```sh
gh workflow run build.yml --ref main
gh run list --workflow build.yml
gh run view <运行ID> --log-failed
gh run download <运行ID>
```

CI 不读取发布 Secrets，不运行仪器测试。
未启动自建 SMB/NFS 服务器时，4 项需要真实服务器的 JVM 用例按设计跳过，其余测试运行。
调试 APK 和报告保留 14 天。

## 发布所需的仓库配置

仓库 Actions Secrets：

| 名称 | 内容 |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | `.signing/media-player-release.keystore` 的单行 Base64 内容 |
| ANDROID_KEYSTORE_PASSWORD | 密钥库密码 |
| ANDROID_KEY_ALIAS | `media-player` |
| ANDROID_KEY_PASSWORD | 私钥密码，当前与密钥库密码相同 |

仓库 Actions Variable `ANDROID_SIGNING_CERT_SHA256` 保存期望的公开签名证书 SHA-256 指纹：

```text
fab3f83804b6c0638e1cca15f6540ea874d951eacfb657ef7c1856f1a4a5f139
```

GitHub Release 使用工作流短期 `GITHUB_TOKEN`，无需另存个人访问 Token。
签名构建阶段只有 contents:read，创建 Release 的独立 publish 阶段拥有 contents:write。
密钥以 Secrets 注入，仅在签名步骤恢复，权限为 600；不作为产物上传，任务结束时清理。
签名任务只读取 Gradle 缓存，不写回；所有构建明确关闭 configuration cache。
Actions 固定到完整提交 SHA，版本注释用于后续升级检查。

本机配置由维护者通过 `gh secret set` 的标准输入上传，不在命令参数、终端或仓库文件里打印真实密码。
GitHub 不能读回已上传 Secret；本机密钥和密码配置仍需受保护备份。

## 发布新版本

1. 在 `gradle.properties` 修改 `appVersionName` 与递增的 `appVersionCode`。
2. 添加 `docs/releases/版本号.md`，描述本次变化与安装注意事项。
3. 提交并推送 main，等待 Android CI 通过。
4. 在这个提交上创建与版本号匹配的标签并推送。

例如以后发布 0.13.0（先把源码版本更新到 0.13.0）：

```sh
git add gradle.properties docs/releases/0.13.0.md
git commit -m "chore: 准备 0.13.0 发布"
git push origin main
git tag -a v0.13.0 -m "Media Player 0.13.0"
git push origin v0.13.0
```

发布工作流要求标签与 `appVersionName` 一致，并确认被发布的提交属于 origin/main 历史。
`0.13.0-beta.1`、`0.13.0-rc.1` 等预发布版本会自动标记 prerelease。
没有顶层 `LICENSE` 时自动生成 draft Release，等待维护者确定源码许可证；正常源码许可确定后生成公开 Release。

构建成功后验证 APK 包名、版本号、签名指纹与对齐，再发布：

- `media-player-版本号.apk`
- `SHA256SUMS`
- `BUILD_INFO.json`
- `media-player-版本号-mapping.zip`
- `media-player-版本号-source.tar.gz`：该标签的应用源码
- `libmpv-android-1.0.0-source.tar.gz`：包装层、固定版本原生依赖源码、补丁与构建脚本

R8 映射用于反混淆崩溃日志；GitHub 同时提供对应标签的源码压缩包。
项目采用 GPL-3.0-only；原生源码归档在读取签名 Secrets 前生成，缺少源码归档时不会发布 APK。
libmpv 等原生依赖的版本、对应源码和构建入口见 `THIRD_PARTY_NOTICES.md`。

## 重试与查看结果

```sh
gh run list --workflow release.yml
gh run view <运行ID> --log-failed
gh run rerun <运行ID> --failed
gh release view v0.12.0
```

也可以手动在标签上触发：`gh workflow run release.yml --ref v0.12.0`。
在 main 上手动运行 release 会因缺少匹配标签而明确失败。
已存在同名 Release 时，工作流拒绝覆盖发布附件，以免相同版本对应不同 APK。
旧标签 v0.10.0/v0.11.0 保留历史身份；首次远端同步不推送这些标签，避免触发旧版本发布。

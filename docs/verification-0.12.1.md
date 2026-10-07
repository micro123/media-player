# 0.12.1 架构分包验证

日期：2026-10-07。本版本仅调整版本号、APK 构建/分发和发布说明，播放实现与原生依赖版本不变。

- `assembleRelease -PsplitApks=true` 本地构建成功，用时 39 秒；产物为四个单架构 APK 和一个通用 APK。
- 应用 ID `io.github.micro123.mediaplayer`，versionName 0.12.1，versionCode 13，所有包使用相同发布密钥。
- 本地安装包大小：arm64-v8a 25.86 MiB、armeabi-v7a 23.64 MiB、x86 28.61 MiB、x86_64 30.57 MiB、universal 102.77 MiB。
- 五个包逐一通过签名证书 SHA-256、包名、版本、非 debuggable、原生 ABI 内容与 `zipalign -c -P 16 4` 检查。
- 对每个单架构包的全部 11 个 `.so` 计算 SHA-256，均与通用包中对应 ABI 的原生库一致；没有裁剪编解码能力。
- 发布打包脚本依据 AGP output-metadata.json 选择五个产物，检查完整架构集合、重复项、文件名及版本，避免误发布目录中残留的旧 APK。
- BUILD_INFO.json 的 apks 数组记录每个包的架构、字节数和 SHA-256；SHA256SUMS 覆盖全部发布附件。
- 正常 debug 构建不开启 ABI 分包，仍使用 app-debug.apk；只有显式 `-PsplitApks=true` 的发布构建生成多包。
- actionlint 工作流检查、打包 Python 语法检查与 git diff --check 通过。

本次没有重复运行播放仪器测试，验证重点是实际发布产物内容与签名。GitHub 发布任务仍按既定流程运行 JVM 测试和 lint。

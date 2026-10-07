# Release 签名与打包

本项目使用根目录的 `keystore.properties` 配置 release 签名。密钥和实际密码不随源码提交；源码只保留 `keystore.properties.example` 模板。

## 本机签名密钥

2026-10-07 为当前项目创建专用签名密钥：

- 密钥库：`.signing/media-player-release.keystore`
- 密钥库格式：PKCS12
- 别名：`media-player`
- 密钥算法：RSA，3072 位
- 自签名证书算法：SHA256withRSA
- 证书有效期：10000 天
- 证书到期时间：2054-02-22 22:46:48（Asia/Shanghai）
- 密码：随机生成，保存在本机 `keystore.properties` 中；PKCS12 的密钥密码与密钥库密码相同。

`.signing/` 的访问权限为 700，密钥与密码配置权限为 600，仅当前系统用户可读写。
`.gitignore` 忽略整个 `.signing/` 目录、`*.keystore` 和实际 `keystore.properties`。

应将密钥库与密码配置备份到受保护的外部存储或密码管理器。以后每个发布版本都使用这把密钥；只备份源码不能恢复签名身份。

## 构建正式 APK

本机命令：

```sh
export JAVA_HOME=/home/tang/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
export ANDROID_USER_HOME="$PWD/.android"
./gradlew :app:assembleRelease -PsplitApks=true --max-workers=4 -Pkotlin.compiler.execution.strategy=in-process --console=plain --no-daemon
```

0.12.1 起分包产物为 `app/build/outputs/apk/release/app-架构-release.apk`，通用包为 `app-universal-release.apk`。
未指定 `-PsplitApks=true` 时仍生成单个通用 `app-release.apk`。GitHub 发布固定开启分包。
release 保留 R8 代码压缩、资源压缩和既有 ProGuard 配置，构建过程使用专用密钥自动签名。
Android Studio 中也可选择 release 构建变体并构建 APK。

debug 构建不需要专用密钥。缺少 `keystore.properties` 时，release 构建会明确报错，避免误发未签名 APK。

## 在另一台机器上构建

将受保护备份中的密钥恢复到 `.signing/media-player-release.keystore`，复制 `keystore.properties.example` 为 `keystore.properties`，填入原来的真实密码。
也可恢复原始 `keystore.properties`；其中的密钥路径相对于项目根目录，不依赖本机绝对路径。
请勿重新生成密钥来替代已发布版本的签名密钥。

## 检查产物

本机 Android SDK Build Tools 为 36.0.0：

```sh
export JAVA_HOME=/home/tang/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
/home/tang/Android/sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
/home/tang/Android/sdk/build-tools/36.0.0/zipalign -c -P 16 4 app/build/outputs/apk/release/app-arm64-v8a-release.apk
sha256sum app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

正式发布时应同时保存对应的源码提交、APK 和 `app/build/outputs/mapping/release/` 下的混淆映射，方便排查 release 崩溃。

### 2026-10-07 构建验证

- `assembleRelease` 与 `assembleDebug` 同时构建成功，用时 28 秒；release 执行 R8、资源压缩和 release 关键 lint 检查。
- 正式 APK 为 0.11.0（versionCode 11），应用 ID `com.tang.player`，大小 107766281 字节（102.77 MiB）。
- APK 签名验证通过，使用 APK Signature Scheme v2；证书为新建 RSA 3072 位密钥。
- 签名证书 SHA-256：`fab3f83804b6c0638e1cca15f6540ea874d951eacfb657ef7c1856f1a4a5f139`。
- APK 文件 SHA-256：`e84844fb0236270693ad46b2d82df1a9b028089b0254d44730233b1859832a8a`。
- `zipalign -c -P 16 4` 检查通过；包含 arm64-v8a、armeabi-v7a、x86 和 x86_64 四种 ABI。
- release Manifest 未开启 debuggable，APK 中没有密钥或签名密码配置。
- 暂时移走本机签名配置验证后已恢复：debug 构建配置及任务图正常，`preReleaseBuild` 因缺少签名配置按预期明确失败。
- `git check-ignore` 验证密钥及实际密码配置被忽略；模板可纳入版本管理。

本次未在手机安装正式包；已有调试版安装与数据保留。

## 与当前调试版的安装关系

0.11.0 创建密钥时应用 ID 为 `com.tang.player`。0.12.0 起应用 ID 与源码包名统一为 `io.github.micro123.mediaplayer`，继续使用同一把发布密钥。
新应用 ID 可以与旧版并存，但旧版设置、书签和观看进度不会自动迁移。同一应用 ID 的 debug 与 release 签名不同，不能直接覆盖安装。
首次安装正式版后，以后使用同一签名密钥、相同应用 ID 和合适版本号生成的正式包可以用于更新。

GitHub Actions 的 Secrets 配置和标签发布步骤见 [自动构建与发布](github-actions.md)。

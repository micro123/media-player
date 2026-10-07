# Media Player · 本地播放器

[![Android CI](https://github.com/micro123/media-player/actions/workflows/build.yml/badge.svg)](https://github.com/micro123/media-player/actions/workflows/build.yml)
[![GitHub Release](https://img.shields.io/github/v/release/micro123/media-player)](https://github.com/micro123/media-player/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)

基于 **libmpv** 的 Android 音视频播放器，使用 Kotlin、Jetpack Compose 和 Material 3。
支持安卓媒体库、本地文件、SMB/NFS 网络目录、M3U 播放列表与书签，为视频和音乐提供独立播放界面。

A libmpv-powered Android media player with local browsing, SMB/NFS, playlists and dedicated video/music interfaces.

**当前版本：0.12.0** · **应用 ID：`io.github.micro123.mediaplayer`** · **最低 Android 8.0**

## 下载与安装

从 [GitHub Releases](https://github.com/micro123/media-player/releases) 下载 `media-player-版本号.apk`，按系统提示安装。
正式包包含 arm64-v8a、armeabi-v7a、x86、x86_64 四种架构，并使用固定发布密钥签名。

0.12.0 开始使用新的应用 ID，可与此前的 `com.tang.player` 测试版并存。旧版设置、书签和观看进度不会自动迁移。
日常开发提交生成的调试 APK 可在 [Actions](https://github.com/micro123/media-player/actions/workflows/build.yml) 对应任务的 Artifacts 中获取；调试版与正式版签名不同，不能互相直接覆盖安装。

Release 附件中的 `SHA256SUMS` 用于校验文件，`BUILD_INFO.json` 记录版本、提交和签名证书指纹；映射 ZIP 用于排查崩溃，普通使用只需下载 APK。

## 功能

| 功能 | 支持情况 |
| --- | --- |
| 媒体库 | 安卓 MediaStore、音频/视频筛选、最近播放、按文件名前缀与集数自动归类剧集 |
| 文件浏览 | 共享存储与 SAF 文件夹；名称/大小/类型升降序，文件夹置顶；点击搜索图标展开搜索 |
| 网络位置 | SMB 2/3、NFS v3/TCP 只读浏览和按需播放；HTTP/HTTPS 地址；认证加密保存、位置与目录书签 |
| 播放列表 | 持久队列、播放全部、上一项/下一项、自动下一项、顺序调整、M3U 导入/导出 |
| 视频 | 全屏播放，默认横屏，可配置竖屏或保持；原始/4:3/16:9 比例；主动进入系统画中画 |
| 音乐 | 独立封面与 ID3 信息界面，适配竖屏和横屏；应用内迷你播放器 |
| 倍速 | 0.1～5.0，滑块与快捷档位、可记住全局倍速；长按临时使用当前速度的 2 倍，最高 5.0 |
| 观看进度 | 自动保存和续播；可配置 OP/ED 快速跳过，默认 85 秒 |
| 手势 | 单击显隐控制，横屏左右双击 ±10 秒，中间双击播放/暂停；亮度、音量、可取消进度拖动 |
| 跳转预览 | 拖动期间暂停，进度条显示候选位置和本地缩略帧；移到顶部取消区可放弃，结束后恢复原状态 |
| 区间录制 | 同一个按钮开始/结束标记，导出原速无损 MP4；实际开始点可能对齐较早关键帧 |
| 外部打开 | 支持文件管理器「打开方式」播放音视频，使用发送方提供的单文件读取授权 |

视频退出播放界面或普通 Home 离开时保存进度并停止；主动进入画中画才继续小窗播放。
音乐可以在应用内返回浏览并继续播放，离开应用时暂停。

目前没有独立后台音频服务、媒体通知、字幕/音轨选择或系统画中画以外的悬浮窗。
网络视频支持跳转时间预览；缩略帧预览目前仅支持本地文件。
NFS 当前支持 v3/TCP，SMB 当前不使用 Kerberos；网络源按需读取，不预先下载完整媒体文件。

## 界面预览

截图使用项目自建测试素材；部分截图来自 0.9/0.11 版本。

<p>
  <img src="docs/screenshots/music-portrait-0.9.png" width="260" alt="音乐封面与歌曲信息" />
  <img src="docs/screenshots/file-sort-search-0.11.png" width="260" alt="文件排序与搜索" />
</p>

![视频跳转预览](docs/screenshots/seek-preview-local-0.11.png)

## 构建

需要 **JDK 25**、**Android SDK Platform 37.0**、**Build Tools 36.0.0**，使用项目自带的 Gradle Wrapper。
Android Studio 打开项目根目录后，配置 Gradle JDK，安装对应 SDK 并等待同步完成。
SDK 路径通过本机 `local.properties` 或 `ANDROID_HOME` 设置，不随源码提交。

```sh
git clone git@github.com:micro123/media-player.git
cd media-player

# 确保 JAVA_HOME 指向 JDK 25。
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
不需要本机 NDK 或 CMake：固定版本 `dev.jdtech.mpv:libmpv:1.0.0` AAR 已提供四种 ABI。

构建签名正式包需先准备未纳入 Git 的 `keystore.properties` 与密钥库，见 [签名与打包](docs/release-signing.md)：

```sh
./gradlew :app:assembleRelease
```

Release APK：`app/build/outputs/apk/release/app-release.apk`。
项目源码不包含发布密钥；克隆仓库后可以直接构建调试版，不能使用项目维护者的签名身份。

## GitHub Actions 与发布

- **Android CI**：提交到 `main`、向 `main` 提交 PR 或手动触发时运行单元测试、lint，构建调试 APK 与设备测试 APK，上传调试安装包和检查报告。
- **Android Release**：推送 `v*` 标签时检查标签与源码版本一致，再测试、构建并验证签名，创建 GitHub Release，附带 APK、校验文件、构建信息和混淆映射。
- 签名凭据保存在仓库 Actions Secrets，PR 构建不读取发布密钥；发布工作流可手动选择已有版本标签重试。

发布版本号由 `gradle.properties` 中的 `appVersionName` 与 `appVersionCode` 管理。详细步骤见 [自动构建与发布](docs/github-actions.md)。

## 项目结构

```text
app/                           Compose 界面、浏览来源、媒体库、书签与持久设置
core/player/                   播放接口、libmpv 内核与来源句柄管理
gradle/libs.versions.toml       固定插件与依赖版本
.github/workflows/             日常 CI 与签名发布
tools/ci/                      签名恢复、版本检查与发布附件打包
tools/network-test-server/     自建 SMB/NFS 隔离测试服务器
docs/                          功能说明、发布流程与验证记录
THIRD_PARTY_NOTICES.md          原生及网络依赖来源与许可声明
```

来源注册表、目录浏览和播放输入解析相互独立。本地 content/file、HTTP、SMB 与 NFS 均使用稳定地址保存队列和观看进度；网络代理文件描述符支持随机读取和跳转。

## 反馈与贡献

请通过 [Issues](https://github.com/micro123/media-player/issues) 提交问题或功能建议，附上应用版本、Android 版本、来源类型和复现步骤。
构建与验证约定见 [贡献说明](CONTRIBUTING.md)，代码管理见 [Git 工作流](docs/git-workflow.md)。
设备测试使用自建音视频、独立网络服务器和测试文件管理器，不依赖真实 NAS 账号或用户媒体。

## 文档与第三方依赖

- [0.12.0 发布说明](docs/releases/0.12.0.md)
- [文件排序、区间录制与可取消跳转预览](docs/browsing-and-gestures-0.11.md)
- [文件管理器打开与应用图标](docs/external-open-and-icon-0.10.md)
- [独立音乐界面](docs/music-player-0.9.md) · [视频方向策略](docs/video-orientation-0.9.md)
- [网络位置与来源实现](docs/network-locations-0.7.md) · [文件与网络浏览](docs/files-and-network-0.8.md)
- [剧集归类](docs/media-series-0.5.md) · [M3U、书签与片段导出](docs/sources-bookmarks-clips-0.6.md)
- [第三方依赖及对应源码入口](THIRD_PARTY_NOTICES.md)

libmpv JNI 包装层使用 MIT，原生 mpv/FFmpeg 构建包含 GPL 组件；各组件许可证不能仅按包装层的 MIT 计算。
依赖声明随 APK 打包在 `assets/licenses`，详细版本、上游源码和构建入口见第三方依赖说明。

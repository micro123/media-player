# 本地播放器 / LocalPlayer

使用 libmpv 播放本地音视频的 Android 应用，采用 Kotlin、Jetpack Compose 和 Material 3。

当前版本 **0.11.0** 提供安卓媒体库、文件夹浏览、持久播放列表、M3U 导入/导出和命名书签。
支持从文件管理器的「打开方式」直接播放音视频，应用图标使用提供的彩色播放图案。
主导航为媒体库、文件、播放列表、书签、设置；播放页面独立于浏览导航。
视频始终全屏，可竖屏或横屏，可切换原始比例、4:3、16:9。
返回或离开应用会保存进度并停止视频，主动点击“小窗”才使用系统画中画继续播放。
支持 0.1～5.0 倍速、全局记住倍速、观看进度恢复和常见视频手势。
长按视频临时使用当前速度的 2 倍（最高 5.0），松手或取消后恢复，不覆盖保存的速度。
视频播放 UI 采用顶部标题与快捷操作、底部细进度条与图标控制的布局，白色控件配淡紫色进度条。
右侧提供小窗与触摸锁定，播放页内可直接配置 OP/ED 跳过秒数、画面比例和自动下一项。
媒体库默认按文件名前缀与集数标记自动归类为剧集，点击后查看分集，按集数连续播放。
0.8.0 将本地存储、网络位置和目录书签统一到「文件」页，可添加、编辑位置和收藏当前本地 / 网络目录。
0.7.0 接入 SMB 2/3 与 NFS v3/TCP 只读网络目录：加密保存认证、目录浏览、搜索、远程随机读取播放与网络 M3U 导入。HTTP/HTTPS 地址入口保留。
视频更多菜单支持命名时间点书签和区间标记导出。高速播放优先直接 MediaCodec，并在高速切换时重置旧节奏缓冲。

## 在 Android Studio 中打开

1. 在 Android Studio 中选择 **Open**，打开本项目根目录。
2. 等待 Gradle Sync 完成。使用项目自带的 Gradle Wrapper。
3. 在 **Settings → Build, Execution, Deployment → Build Tools → Gradle** 中，将 Gradle JDK 设为 Android Studio 的 Embedded JDK（本机为 JBR 25）。
4. SDK Manager 中需要 Android SDK Platform 37.0 和 Build Tools 36.0.0。
5. 选择 `app` 配置，连接 Android 8.0 或以上的设备，或启动模拟器，然后 Run。

项目已生成本机的 `local.properties`，SDK 路径是 `/home/tang/Android/sdk`。
该文件被 Git 忽略，其他机器应通过 Android Studio 重新生成。

## 命令行构建

本机尚未将 Java 添加到 PATH，可以使用 Android Studio 自带的 JDK：

```sh
export JAVA_HOME=/home/tang/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:lintDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
首次构建需要联网下载 Gradle 和 Maven 依赖。

验证环境使用项目内 `.gradle-user-home/` 存放 Gradle 缓存；要复用该缓存，可在上述命令前设置：

```sh
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
export ANDROID_USER_HOME="$PWD/.android"
```

`.android/` 用于保存本地调试签名等临时文件，同样被 Git 忽略。

原生依赖使用固定版本 `dev.jdtech.mpv:libmpv:1.0.0` AAR，包含 mpv 0.41.0 和 FFmpeg 8.1。
应用构建不需要本机 NDK 或 CMake。Debug APK 包含四种 ABI，体积约 115 MiB；后续可按 ABI 分包。

## 工程组织

```text
app/                              Android 应用
  src/main/java/com/tang/player/
    MainActivity.kt               Compose 入口、画中画和系统生命周期
    PlayerApplication.kt          手动依赖组装与播放引擎工厂
    data/                         来源注册、MediaStore/SAF、M3U、书签、片段导出及持久设置
    ui/
      PlayerViewModel.kt          列表、自动下一项、倍速、进度恢复与引擎生命周期
      PlayerApp.kt                底部导航、系统文件选择器
      screens/                    文件、播放器、设置页面
      components/                 SurfaceView、视频手势、倍速/比例/列表弹层
      theme/                      深色 Material 3 主题
core/player/                      独立 Android 播放模块
  src/main/java/com/tang/player/core/
    PlaybackEngine.kt             播放接口与 Surface 生命周期约定
    PlaybackState.kt              播放状态
    MediaItem.kt                  稳定 URI/URL 媒体模型
    MediaSource.kt                原生输入解析与句柄释放约定
    PlaybackQueue.kt              来源无关的队列模型
    MpvPlaybackEngine.kt          真正的 native 播放、事件、音频焦点与资源管理
gradle/libs.versions.toml          插件和依赖版本
docs/libmpv-integration.md         libmpv 接入与生命周期说明
docs/playback-test.md              0.2.0 原生播放历史验证
docs/features-0.3.md               0.3.0 功能与操作说明
docs/video-ui-0.4.md               0.4.0 视频 UI 与真机截图
docs/media-series-0.5.md           0.5.0 剧集自动归类规则与真机验证
docs/sources-bookmarks-clips-0.6.md 0.6.0 来源扩展、书签、M3U、录制区间及同步验证
docs/network-locations-0.7.md      0.7.0 SMB/NFS 网络目录、认证与协议验证
docs/files-and-network-0.8.md      0.8.0 文件页、目录书签与系统联网限制诊断
THIRD_PARTY_NOTICES.md             原生依赖版本与许可来源
```

应用包名暂定 `com.tang.player`，最低版本 Android 8.0（API 26），编译和目标 API 为 37。
使用 AGP 9.2.1、Gradle 9.4.1、Kotlin 2.3.10 和 Compose BOM 2026.08.00。
AGP 使用内置 Kotlin 支持，根构建脚本将 Kotlin 版本与 Compose Compiler 对齐。

## 当前功能

- **文件与列表**：默认显示安卓 MediaStore，可按视频/音频筛选；支持授权后的整个媒体库及 Android 的部分视频授权。也可使用所有文件权限直接浏览共享存储，或选择 SAF 文件夹逐级浏览，并通过系统选择器添加多个文件。
- **外部打开**：在文件管理器中选择音视频文件的「打开方式」→ 本地播放器。接收 ACTION_VIEW 的 content/file 地址及 audio/video 类型，兼容 application/x-matroska 和 application/ogg。冷启动直接播放，已运行时切换为所选文件，建立单文件队列并保留最近记录和进度；视频全屏并遵循方向设置，音频使用独立音乐页。外部 content 文件优先使用发送方提供的单文件读取授权，不阻塞于首次所有文件权限说明。临时授权失效时需要从原文件管理器重新打开。
- **应用图标**：提供 mdpi～xxxhdpi 图标、自适应前景/背景和单色主题图标；彩色主体缩小到安全区域，适应圆形与圆角方形桌面。原图保存在 assets/branding，生成脚本为 tools/create-app-icons.py。
- **播放列表**：点击列表中的文件从该项开始播放；支持播放全部、上一项/下一项、自动下一项、添加、移除、上移/下移，列表会保存到下次启动。
- **倍速**：0.1～5.0，滑块按 0.1 调整，快捷选择 0.5/1.0/1.5/2.0/3.0/5.0，随时恢复 1.0。可选全局记住上次速度；关闭该选项时，新启动默认 1.0。
- **长按倍速**：播放视频时按住画面，临时速度为 min(当前速度×2, 5.0)。松手、触摸取消、暂停、切换文件、退出视频或进入画中画时恢复基础速度。
- **跳过 OP/ED**：视频控制区提供独立的“跳过 85 秒”按钮，设置可修改全局跳过秒数并保存，支持恢复默认 85 秒；超过剩余时长时跳到结尾。
- **观看进度**：按原始 URI 保存，每 3 秒及暂停/切换文件/释放时更新。重新打开从上次位置继续，可快速回到上次位置或从头播放。播放结束会清除该文件的续播位置；保留最近 500 个文件的进度。
- **浏览流程**：媒体库、文件、播放列表、书签、设置五个入口；媒体库支持最近播放、文件名搜索和视频/音频筛选。点击文件直接进入播放页，退出视频回到原来的浏览入口，保留文件夹、筛选和滚动状态。
- **剧集归类**：媒体库默认开启，按共同名称及 `01/02`、`第1集/第2集`、`S01E01/S01E02` 等标记识别剧集；无明确集数时尝试共同词语前缀。至少两个文件才聚合，按集数排序，不同季、音频/视频分开。打开剧集查看分集，点击某集只将该剧集加入播放队列，退出播放回到剧集详情。媒体库“剧集归类”开关持久保存，可恢复平铺；最近播放和文件夹保持原有列表。只影响展示，不移动、重命名或删除文件。
- **网络位置**：SMB 2/3 支持访客或用户名、密码及可选域；NFS v3/TCP 支持实际导出根目录与 UID/GID，服务端需允许非特权源端口。认证使用 Android 密钥库加密，位置支持保存、编辑、删除。目录可搜索、逐级进入、刷新，点击媒体建立当前目录的自然顺序队列；返回停止视频并保留目录。
- **来源扩展**：MediaRepository 和来源注册表负责地址、元数据与目录，PlaybackSourceResolver 获取 URL 或带释放句柄的 fd 输入。SMB/NFS 使用可跳转代理 fd 按需读取，不先下载整部视频。真实协议已在隔离服务器验证；当前设备底层 socket 被拒绝，手机的真实网络端到端验证仍受限。
- **M3U**：播放列表页支持导入/导出 UTF-8 M3U，识别 EXTINF 标题与顺序；相对本地路径按列表目录解析，SAF 文档需要选择所在文件夹。HTTP 相对路径按最终重定向地址解析；SMB/NFS 可直接读取 M3U 并按所在目录解析相对媒体路径。HTTP HLS m3u8 作为单个流处理，不能当普通分片队列；本地 HLS 暂未接入。
- **书签**：「文件」页添加、编辑或删除网络位置（HTTP/HTTPS、SMB、NFS），并收藏 / 重命名本地与网络目录；书签页也可查看这些条目。网络目录书签保留独立的加密认证和 NFS 原始导出配置，删除原网络位置不影响已收藏目录。视频更多菜单保存任意命名时间点，不覆盖自动续播进度；播放列表页可保存命名列表并在以后加入队列。最多 200 个书签，列表单次最多 5000 项。
- **区间录制**：视频顶部同一个录制按钮点击开始、再次点击结束，再确认并导出；导出前检查编码并显示前一个关键帧对齐后的实际范围，通过系统选择器保存原速无损 MP4。支持兼容的 H.264/H.265 等视频和 AAC 等音轨，不录制 UI、不随倍速加速、不静默丢弃不兼容音轨；暂不支持精确帧转码或字幕导出。
- **高速同步**：先绑定 Surface 再启动视频，优先 mediacodec，回退 mediacodec-copy/auto；以音频时钟同步并丢弃迟到显示帧，高速切换时刷新旧节奏缓冲。增加 avsync/hwdec-current 观察及自建 1080p30 有声素材验证；效果与设备/编码有关。
- **视频**：始终全屏，播放方向可配置为横屏（默认）、竖屏或保持；保持会保留进入播放时的横竖方向，退出播放恢复原来的方向策略。播放页旋转按钮可直接切换。默认原始显示比例，也支持全局切换 4:3、16:9。返回或 Home 离开时保存位置、停止内核并关闭播放页。
- **视频 UI**：顶部返回、文件名、队列位置、时间、可配置秒数的圆形跳过按钮、播放设置与更多菜单；底部时间/剩余时间、细进度条、播放与上一项/下一项、±10 秒、列表、比例、倍速及旋转。竖屏控制分为两行，避免挤压。
- **触摸锁定**：右侧锁定后隐藏其他控制并屏蔽双击、拖动、长按；锁定不改变窗口亮度。右侧解锁或系统返回可恢复，切换文件和进入画中画也会解除锁定。
- **手势**：单击显隐控制；横屏双击左/右三分之一区域后退/快进 10 秒，中间区域播放/暂停，竖屏仍双击播放/暂停。左右滑动或拖进度条时暂停播放，进度条上方预览时间和本地缩略帧，松手才跳转；滑到顶部取消区再松手放弃跳转，之后恢复原有播放/暂停状态。网络视频先支持时间预览。左侧上下滑调应用窗口亮度，右侧上下滑调媒体音量。
- **浏览排序与搜索**：本地和网络文件目录支持名称、大小、类型升降序，文件夹置顶，自然数字排序，选择持久保存；播放队列顺序跟随排序。媒体库、剧集和文件目录使用搜索图标，默认收起输入框，关闭时清除筛选。
- **小窗**：主动点击“小窗”进入 Android 系统画中画，不需要悬浮窗权限。系统小窗提供播放/暂停动作；返回应用继续全屏播放。普通 Home 不自动进入小窗，关闭小窗会停止视频。
- **音乐界面**：独立于视频的封面与歌曲信息界面，竖屏上下排列、横屏左右排列，没有方向切换和小窗按钮。读取音频内嵌封面及歌曲名、歌手、专辑、专辑歌手、年份、流派、曲目、作曲与码率；缺失时使用默认封面和文件名。迷你播放器同步显示封面与歌曲名。
- **音频浏览**：音频播放页返回后可继续在应用内浏览，通过底部迷你控制栏暂停、继续、展开或停止；离开应用仍暂停，没有独立后台音频服务。
- **文件授权**：首次使用说明并请求“访问所有文件”，通过系统设置由用户开启。开启后文件夹可直接浏览共享存储，安卓媒体库仍为默认入口；未开启时保留媒体库读取权限、SAF 文件夹和单文件选择。安卓仍限制其他应用的私有目录。

清空最近记录不删除设备文件、播放列表或观看进度。
当前没有媒体通知、独立后台音频服务、字幕/音轨选择、画中画以外的自定义悬浮窗。
离开视频而未主动进入系统画中画时会停止；没有屏幕的小窗后台音频播放不在本版本范围。

## 验证

0.2.0 历史原生测试见 [播放测试记录](docs/playback-test.md)，0.3.0 功能说明及验证见 [功能记录](docs/features-0.3.md)，0.4.0 视频 UI 见 [界面改版记录](docs/video-ui-0.4.md)。
0.5.0 剧集归类规则及验证见 [剧集归类记录](docs/media-series-0.5.md)。
0.6.0 新功能、适配接口与网络测试限制见 [来源与书签记录](docs/sources-bookmarks-clips-0.6.md)。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

自动测试使用实际打包的 libmpv，并创建应用自有的 MediaStore 静音音频和测试视频。
测试采用 API 29 以上的 MediaStore 接口，应用自身最低 API 仍为 26。
仪器测试保存并恢复原有列表与设置，结束后删除测试条目。
原生依赖来源和许可材料见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

0.7.0 网络位置操作、测试服务器与验证边界见 [网络位置记录](docs/network-locations-0.7.md)。

0.8.0 文件页、目录书签及系统联网限制修复见 [文件与网络记录](docs/files-and-network-0.8.md)。

0.9.0 新增横屏（默认）/竖屏/保持三种播放方向策略，详情见 [播放方向记录](docs/video-orientation-0.9.md)。

0.9.0 独立音乐界面与标签、封面读取说明见 [音乐界面记录](docs/music-player-0.9.md)。

0.10.0 文件管理器「打开方式」接入、跨应用单文件授权与图标适配见 [外部打开与图标记录](docs/external-open-and-icon-0.10.md)。

Git 初始化、提交范围、日常分支与版本标签操作见 [代码管理说明](docs/git-workflow.md)。

0.11.0 文件排序、搜索入口、同按钮区间录制及可取消视频预览见 [浏览与手势记录](docs/browsing-and-gestures-0.11.md)。

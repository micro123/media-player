# libmpv 接入

当前 `AppContainer` 创建真正的 `MpvPlaybackEngine`。原生依赖固定为 Maven Central 的
`dev.jdtech.mpv:libmpv:1.0.0`，包含 mpv 0.41.0、FFmpeg 8.1 和 Kotlin/JNI 包装。
原生版本与接口依据发布方 v1.0.0 标签，而非浮动 master。

应用使用已经构建的 AAR；常规 Gradle 构建不需要安装 NDK 或 CMake。
支持的 ABI 为 arm64-v8a、armeabi-v7a、x86、x86_64。
最低 Android 版本仍为 API 26。

## 所有权与线程

`PlayerViewModel` 独占一个引擎，在 `onCleared()` 调用 `release()`。
每次加载文件创建独立的 MPVLib 实例，销毁旧实例后再创建新实例。
所有 JNI 命令、文件打开、Surface 绑定及 native 销毁在单线程 executor 执行。
原生观察回调只入队，不直接释放 mpv；StateFlow 的更新调度回主线程。

每次切换文件增加 generation，用它拒绝旧文件的延迟回调。
快速切换时，尚未开始的过期加载会被跳过。
`release()` 可重复调用；释放后的播放、加载和旧回调均被忽略。

## 文件访问

系统选择器返回的 `content://` URI 保留原样。
0.6.0 由 PlaybackSourceResolver 解析原生输入，返回 OpenedMediaSource 及释放回调。
本地适配器通过 ContentResolver.openFileDescriptor(uri, "r") 打开文件，并将 `fd://N` 交给 mpv。
HTTP/HTTPS 适配器直接交给 mpv URL，不再对网络地址查询 ContentResolver。SMB/NFS 必须接入独立输入桥接。
描述符一直保留到 mpv 销毁后，再关闭 ParcelFileDescriptor。
不把文档 URI 转换为绝对路径。0.3.0 另有用户请求的“访问所有文件”授权与直接共享存储浏览；
直接文件由 file URI 表示，同样通过文件描述符传入引擎。

`fd://` 不负责关闭传入的描述符。seek 是否可用以 mpv 的 `seekable` 属性为准；
不支持随机访问的文件提供方不会被强行当成普通可跳转文件。

## 播放状态与界面

- 观察 time-pos、duration、pause、paused-for-cache、eof-reached、seekable。
- 映射 IDLE、BUFFERING、PLAYING、PAUSED、ENDED、ERROR 到 StateFlow。
- 播放/暂停、speed、video-aspect-override 与绝对跳转使用原生属性/命令；毫秒在边界转换为秒。
- load 支持初始位置，通过 start 选项从保存的位置开始解码；切换文件保留当前全局 speed。
- keep-open=yes 保留末帧，ENDED 后按播放按钮先 seek 到 0，再取消暂停。
- 文件无法打开、内核无法初始化和不支持的文件显示错误，并可重试或选择其他文件。

视频由 SurfaceView 输出。surfaceCreated 绑定，surfaceChanged 更新 android-surface-size，
surfaceDestroyed/onRelease 解绑；解绑前将 vo 设为 null，重新绑定后设为 gpu。
使用 Android OpenGL ES 上下文，优先 hwdec=mediacodec，回退 mediacodec-copy/auto。
视频先等待 Surface/vo=gpu 就绪再 loadfile，避免先以 vo=null 启动造成直接硬解不可用；
无画面探测场景最多等待 500 ms 后加载。video-sync=audio，framedrop=vo，保持变速音高校正。
跨入/离开 3 倍以上速度时，可跳转的已加载视频通过 relative+exact seek 0 刷新旧节奏缓冲。
观察 avsync（保留有符号毫秒）及 hwdec-current 供同步验证。
视频播放页面会保持屏幕点亮。

## 系统生命周期

播放前请求音频焦点；焦点丢失时暂停，不在焦点恢复后自动抢播。
接收 ACTION_AUDIO_BECOMING_NOISY，在音频路由变化时暂停。
MainActivity 管理生命周期：主动点击小窗进入系统画中画，画中画内保持播放。
0.4.0 视频始终使用独占全屏页面，方向可自动、竖屏或横屏。返回或普通后台会先保存进度，
stop 销毁当前原生 session 并关闭视频页；主动进入画中画时保留播放，关闭画中画则停止。
音频普通后台仍暂停。Manifest 使用 singleTask 复用同一 Activity，并处理尺寸/方向变化。
暂停视频的 Surface 尺寸改变后，以 relative+exact seek 0 刷新当前位置帧，避免旋转后残留旧尺寸缓冲。
当前没有前台播放服务、媒体通知、锁屏控制或独立后台音频服务。

## 验证

`app/src/androidTest/.../MpvPlaybackEngineTest.kt` 使用实际打包的 native 库。
测试自行建立 MediaStore content URI，结束后删除测试素材。
覆盖播放/暂停、时长、暂停时跳转、播放结束/重播、错误恢复、快速切换、
文件描述符释放、重复 release 和音频焦点丢失。
测试使用 ActivityScenario 保证请求焦点时存在前台 Activity。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

0.2.0 历史结果见 `docs/playback-test.md`，0.3.0 新功能见 `docs/features-0.3.md`，
0.4.0 整体交互和退出流程见 `docs/video-ui-0.4.md`，0.6.0 同步测量与网络验证限制见 `docs/sources-bookmarks-clips-0.6.md`。

## 来源

- 固定版本发布：https://github.com/jarnedemeulemeester/libmpv-android/releases/tag/v1.0.0
- 包装层与 JNI：https://github.com/jarnedemeulemeester/libmpv-android/tree/v1.0.0/libmpv/src/main
- mpv fd 输入语义：https://mpv.io/manual/stable/#protocols
- 文件访问：https://developer.android.com/training/data-storage/shared/documents-files
- 原生构建选项与许可证记录：项目根目录 THIRD_PARTY_NOTICES.md。

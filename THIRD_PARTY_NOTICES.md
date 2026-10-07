# 第三方原生依赖

## 网络来源依赖（0.7.0）

| 组件 | 固定版本 | 许可证 |
| --- | --- | --- |
| SMBJ (`com.hierynomus:smbj`) | 0.14.0 | Apache-2.0 |
| ASN.1 (`com.hierynomus:asn-one`) | 0.6.0 | Apache-2.0 |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`) | 1.86 | MIT |
| MBassador (`net.engio:mbassador`) | 1.3.0 | MIT |
| SLF4J (`org.slf4j:slf4j-api`) | 2.0.9 | MIT |

SMBJ 上游：https://github.com/hierynomus/smbj/tree/v0.14.0 。使用只读 SMB 2/3、NTLM / 访客认证；不配置 Java GSS/Kerberos。
Apache-2.0、Bouncy Castle、MBassador 和 SLF4J 的声明均随应用打包在 assets/licenses。
NFS v3 的只读 ONC RPC / XDR 客户端由本项目实现，不引入旧版 Netty NFS 库。
测试服务器使用 Samba 与 UNFS3 0.11.0，仅用于隔离验证，不进入 APK。

本项目通过 Maven Central 引用 `dev.jdtech.mpv:libmpv:1.0.0`，未修改该 AAR。
发布方：https://github.com/jarnedemeulemeester/libmpv-android/tree/v1.0.0

## JNI 包装层

libmpv-android 的 Kotlin/JNI 包装代码使用 MIT 许可证。
完整声明已放入 `app/src/main/assets/licenses/libmpv-android-MIT.txt` 并随应用打包。
MIT 声明仅说明包装代码，不能用于代表整个原生依赖集合的许可证。

## mpv、FFmpeg 及原生依赖

发布方固定版本如下，版本来源为其 v1.0.0 的 `buildscripts/include/depinfo.sh`。

| 组件 | 版本 | 上游源码及许可证入口 |
| --- | --- | --- |
| mpv | 0.41.0 | https://github.com/mpv-player/mpv/tree/v0.41.0 |
| FFmpeg | 8.1 | https://github.com/FFmpeg/FFmpeg/tree/n8.1 |
| Lua | 5.2.4 | https://www.lua.org/license.html |
| libunibreak | 6.1 | https://github.com/adah1972/libunibreak |
| libass | 0.17.4 | https://github.com/libass/libass |
| HarfBuzz | 14.1.0 | https://github.com/harfbuzz/harfbuzz |
| FriBidi | 1.0.16 | https://github.com/fribidi/fribidi |
| FreeType | 2.14.3 | https://gitlab.freedesktop.org/freetype/freetype |
| libxml2 | 2.15.2 | https://gitlab.gnome.org/GNOME/libxml2 |
| Fontconfig | 2.17.1 | https://gitlab.freedesktop.org/fontconfig/fontconfig |
| Mbed TLS | 3.6.6 | https://github.com/Mbed-TLS/mbedtls |
| libplacebo | 7.360.1 | https://code.videolan.org/videolan/libplacebo |
| dav1d | 1.5.3 | https://code.videolan.org/videolan/dav1d |

该版本的 `buildscripts/scripts/ffmpeg.sh` 显式启用了 `--enable-gpl --enable-version3`。
mpv 的构建脚本保留默认 GPL 构建配置，因此此依赖不能整体视为 MIT 或纯 LGPL 构建。
GPL v2 和 v3 原文已经放入应用 assets/licenses，分别取自 mpv v0.41.0 和 FFmpeg n8.1。

## 对应源码和重建入口

- AAR 的对应包装层、依赖版本和构建脚本：上述 libmpv-android 的 v1.0.0 标签。
- 下载脚本：`buildscripts/download.sh`、`buildscripts/include/download-deps.sh`。
- 构建步骤：该标签的 `buildscripts/README.md`，先下载依赖，再按脚本构建各 ABI。
- 该 AAR 提供 arm64-v8a、armeabi-v7a、x86 和 x86_64。

0.12.0 起应用原创源码采用 GPL-3.0-only，完整条款见仓库 LICENSE。
应用对应源码通过 GitHub Release 的 Source code 附件或版本标签获取。
libmpv-android v1.0.0 的包装层、补丁与构建脚本以及其固定依赖源码的下载命令集中列在仓库 README「源码下载与重建」。
本项目使用未经修改的上游 AAR，不在发布流程中下载或归档原生依赖源码。
原生重建步骤为 buildscripts/download.sh、patch.sh、build.sh，详见原生归档中的 buildscripts/README.md。
应用内 assets/licenses 包含项目许可、JNI 包装层 MIT、GPL v2/v3 与网络依赖声明；各原生源码树保留组件自身的许可文件。

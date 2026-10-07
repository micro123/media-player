# 网络位置 0.7.0

网络位置从可收藏的地址升级为只读目录来源。应用版本 0.7.0，versionCode 7。

## 使用

1. 进入「书签 → 添加网络位置」，或媒体库的网络地址入口。
2. SMB 填写 `smb://服务器/共享名`，例如 `smb://192.168.1.10/Movies`。
   可使用 `smb://服务器:端口/共享名/子目录`。开启访客访问，或填写用户名、密码和可选域。
3. NFS 填写**服务器实际导出的根目录**，例如 `nfs://192.168.1.10/volume1/video`。
   使用 NFS v3 / TCP、AUTH_SYS。UID / GID 默认 65534，可改为服务端授权的用户与组。
   URI 的可选端口指 rpcbind/portmapper 端口，默认 111；mountd/nfsd 端口由服务端公布。
   安卓客户端使用非特权源端口，Linux/UNFS 等服务端需在对应导出上允许 `insecure`。
4. 「保存书签」只保存配置；「保存并连接」同时保存并进入目录。
5. 目录按文件夹优先、文件名自然顺序排列，显示音视频及 M3U，可搜索当前目录、上一级、刷新。
6. 点击音视频，从该项开始使用当前目录的媒体队列。视频沿用全屏、倍速、手势与续播。
   返回播放页会停止视频，回到原网络目录；目录根的返回操作回到书签列表。
7. 点击网络 M3U 会导入队列，支持 EXTINF 与相对路径、中文名称；导入不会自动播放。
   HTTP/HTTPS HLS 仍作为单个网络流；SMB/NFS 上的 HLS 分片清单暂不支持。

HTTP/HTTPS 保留直接播放与保存书签两种操作。新地址的「播放」不会强制创建书签。
SMB/NFS 不支持把账号密码写进 URL；特殊字符须按 URI 编码，例如文件名中的 `#` 为 `%23`。

## 认证与保存

- 网络位置沿用独立的 BookmarkRepository，仍可编辑、删除，重启应用后保留。
- NetworkCredentialStore 将整个账号配置使用 AndroidKeyStore AES/GCM 加密。
  SharedPreferences 仅存书签 ID 与密文，ID 作为 GCM AAD。
- 密码不进入 URI、播放列表、最近记录、原生播放日志或 Compose 保存的实例状态。
- SMB 账号按服务器、端口和共享名匹配。NFS 按已保存导出根匹配，优先更具体的根。
- 切到访客模式会清除不再使用的 SMB 账号、密码和域；删除网络书签也删除对应认证配置。
- 凭据保存或书签保存失败会报错；书签提交失败会回滚已更新的认证配置。
- 应用不允许备份，密钥不导出；卸载或丢失设备密钥后需重新填写网络认证。

## 实现边界

- MediaSourceProvider：SMB/NFS 元数据、目录列表；MediaRepository 来源注册与分发。
- NetworkRepository：位置配置匹配、媒体过滤排序、网络 M3U 有界读取。
- RemoteTransport / RemoteReadHandle：协议只读接口，文件大小与 64 位随机读取。
- SmbTransport：SMBJ 0.14.0，SMB 2/3，只读 FILE_OPEN，NTLM 或 Guest。
  显式使用 Bouncy Castle 1.86，避免依赖 Android 内置密码提供者的算法差异。
  未使用 Java GSS/Kerberos、SMB 1 或 DFS 跳转。
- NfsTransport / NfsRpc：项目实现的 NFS v3 / ONC RPC v2 / XDR TCP 客户端。
  GETATTR、LOOKUP、READ、READDIRPLUS；不支持 PLUS 的服务端回退 READDIR+LOOKUP。
  AUTH_SYS 支持无符号 UID/GID。不会遍历 NFS 符号链接，不暴露远程写入操作。
- RemotePlaybackSourceResolver：StorageManager.openProxyFileDescriptor 生成可定位、可读取的
  `fd://` 输入，ProxyFileDescriptorCallback 在独立工作线程按偏移读取。
  处理远程短读取、EOF、错误和幂等释放；不先下载完整文件，不依赖手机本地 HTTP 服务器。
- MpvPlaybackEngine：仍持有输入句柄，替换、停止、释放时关闭；SMB/NFS 中断显示网络错误。
- 协议 socket 连接/读取有超时，目录遍历有 60 秒期限、最多 10,000 项；XDR 长度、
  RPC 分片和响应大小有界检查。新目录请求取消旧请求，并用请求序号避免重复打开同一路径时的状态合并问题；过时请求不会覆盖当前目录。

本版不提供 NAS 自动发现、列出服务器全部共享、SMB 1、Kerberos、NFS v4、远程文件管理或网络片段导出。
SMB 需要填写共享名，NFS 需要填写实际导出的根目录。

## 可重现的隔离测试服务器

`tools/network-test-server/` 提供 Samba 和 UNFS3 0.11.0 容器。
仅绑定主机 loopback，挂载专门生成的测试素材目录为只读，不访问用户媒体或 NAS。
测试账号 `player-test` / `player-test-password` 只用于此容器。

```sh
python3 tools/network-test-server/create-fixtures.py
docker build -t player-network-test:0.7 tools/network-test-server
docker run -d --rm --name player-network-test-07 \
  -p 127.0.0.1:1445:445 -p 127.0.0.1:11111:111 -p 127.0.0.1:2049:2049 \
  --mount type=bind,src="$PWD/build/network-fixtures",dst=/fixtures,readonly \
  player-network-test:0.7
PLAYER_NETWORK_TEST_HOST=127.0.0.1 ./gradlew :app:testDebugUnitTest --rerun-tasks
docker stop player-network-test-07
```

普通单测运行不设置服务器变量时，4 个真实协议用例会明确跳过。
本次验证设置变量并运行了真实服务器测试，包括访客与账号认证、错误密码、嵌套目录、
中文 `#` 文件名、随机读取、EOF 和超过 4 GiB 的 5 GiB 稀疏文件偏移。

设备测试使用 `NetworkSourcesTest` 和 `NetworkUiTest`。协议测试需要启动上述服务器，并通过
ADB reverse 映射 1445、11111 和 2049，传递 `-e networkTestHost 127.0.0.1`。
设备若在 socket 创建阶段被系统拒绝，该测试明确记录环境跳过，不计为通过。
所有设备用例只添加独立测试书签与素材；队列、最近记录和测试进度会在结束时恢复。
UI 截图仅显示测试构造的地址和列表，不包含用户媒体与网络账号。

详细结果见 `verification-0.7.txt`。

## 界面截图

以下截图全部来自构造的测试地址与列表：

- [SMB 认证表单](screenshots/network-smb-0.7.png)
- [NFS 导出与 UID/GID](screenshots/network-nfs-0.7.png)
- [网络目录](screenshots/network-browser-0.7.png)
- [连接错误与重试](screenshots/network-error-0.7.png)

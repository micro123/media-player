# 用 Git 管理播放器项目

本项目建议使用 main 主分支、按功能划分的提交和通过验证后的版本标签。先以当前 0.10.0 建立基线，后续每个提交对应一项明确改动，例如修复 SMB 打开、添加字幕选择或调整音乐布局。

## 首次建立仓库

在项目根目录运行。首次执行前确认当前目录正确，下面的操作只建立本地仓库和提交，不会上传代码。

```sh
cd /home/tang/Workspace/AndroidApp/player
git init -b main
git status --short
git add .
git diff --cached --stat
git diff --cached --check
```

检查暂存清单应包含 app/src、core/player/src、Gradle 构建脚本和 Wrapper、assets、tools、docs、README 与第三方许可记录。确认没有缓存、APK、本机 SDK 路径和私有配置后，再建立提交与标签：

```sh
git commit -m "chore: 建立播放器 0.10.0 代码基线"
git tag -a v0.10.0 -m "0.10.0：外部文件打开与新应用图标"
git log --oneline --decorate -5
```

提交者可以用下面的命令检查。若需为本项目使用不同身份，去掉 --get 后填入自己的值，配置仅作用于本仓库：

```sh
git config --get user.name
git config --get user.email
```

本项目已在 2026-10-07 初始化 main 分支，以 20f2121 提交建立 0.10.0 基线，并添加 v0.10.0 标签。现有仓库无需重新初始化，也不要删除已有历史的 .git 目录。

## 提交哪些文件

| 文件 | 本项目做法 |
| --- | --- |
| Kotlin/Java/XML 源码、Gradle 配置、Wrapper | 提交，保留 gradlew 的可执行权限 |
| 图标原图、图标生成脚本、生成后的图标资源 | 提交，方便重建和回溯 |
| 自建 MP4/MP3 测试素材、测试服务器脚本 | 提交，让其他机器能够复现测试 |
| 功能说明、验证记录和必要截图 | 提交 |
| build、Gradle/Kotlin 缓存、.android、.idea | 忽略 |
| local.properties、签名密钥、私有 .env 配置 | 忽略 |
| APK/AAB 安装包 | 忽略；需要发布时作为版本附件另行保存 |

最大测试素材目前约 12.44 MiB，可以先随源码管理；素材明显增多时再评估 Git LFS。

.gitignore 只作用于尚未跟踪的文件。后续如果发现某个已跟踪文件应排除，应先确认路径，再使用 git rm --cached 将它从版本管理中移除，保留本机文件。

.gitattributes 将文本统一为 LF，Windows 的 .bat 文件检出为 CRLF；图片、音视频和 Wrapper JAR 按二进制保存。

## 日常开发

每次先查看当前状态与改动：

```sh
git status --short
git diff
```

小改动可以直接在 main 提交。持续时间较长的功能可以建立分支：

```sh
git switch -c feature/subtitle-selection
```

完成一项改动并验证后，使用 git add 指定这次需要提交的路径；若同一个文件包含多个独立改动，使用 git add -p 挑选代码块。

```sh
git add -p
git diff --cached
git diff --cached --check
git commit -m "feat: 添加字幕轨道选择"
```

查看最近历史或某次提交：

```sh
git log --oneline --decorate -10
git show HEAD
```

功能完成、验证通过且工作目录干净后合回 main：

```sh
git switch main
git merge feature/subtitle-selection
```

在版本对应的 main 提交上建立标签。例如未来 0.11.0 完成验证后：

```sh
git tag -a v0.11.0 -m "播放器 0.11.0"
```

提交示例：

- feat: 添加字幕轨道选择
- fix: 修复 SMB 路径中的中文字符
- refactor: 抽象媒体来源接口
- test: 增加外部文件读取授权测试
- docs: 更新播放器操作说明

## 撤销与恢复

只想取消暂存、保留文件改动，可以运行：

```sh
git restore --staged app/src/main/AndroidManifest.xml
```

若已经提交了错误改动，使用 git revert 指定提交，生成一条撤销提交，保留历史。工作目录应先处理干净：

```sh
git revert <提交编号>
```

git restore 文件路径会丢弃该文件尚未提交的修改；git reset --hard 和 git clean -fd 也会删除本地工作，使用前应明确确认要丢弃的内容。

## 远端备份

本地提交完成后再选择 GitHub、GitLab 或自建服务。先创建空仓库，从服务页面复制实际地址，再执行：

```sh
git remote add origin <远端仓库地址>
git push -u origin main
git push origin v0.10.0
```

本项目已配置 origin 为 git@github.com:micro123/media-player.git，无需再次执行 git remote add。
本机 GitHub SSH 连接使用 ssh.github.com:443；主机密钥已与 GitHub 官方 Ed25519 指纹核对，公开密钥保存在本仓库 .git/github-known-hosts，通过仓库级 core.sshCommand 指定。未修改全局 SSH 配置；该配置不随源码提交。
日常提交后使用 git push 同步；git commit 只保存本地历史。远端仓库的可见性应按项目分享需求选择。当前尚未推送代码。

## Android Studio

仓库建立后，可在 Android Studio 的 Git / Commit 工具窗口查看差异、选择文件并提交；通过分支选择器切换或创建分支。提交前检查文件清单，构建通过后再将对应提交标记为版本。

菜单位置可能随 Android Studio 版本和界面布局不同；上述命令可在 IDE 的 Terminal 中执行。

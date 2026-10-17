# MusicBridge

<p align="center">
  <img src="image/icon.png" alt="MusicBridge Icon" width="120"/>
</p>

中文 | [English](./README.md)

一个将 Plex 或 032 Music Server 音乐库桥接到 Sonos 音箱的 Android 应用。

## 下载

从 [Releases](https://github.com/lux032/MusicBridge/releases) 下载最新的 APK

## 功能特性

- 浏览 Plex 与 032 Music Server 音乐库（专辑、艺术家、歌单、收藏）
- 管理 032 Music Server 歌单：新建歌单，从任意「更多」菜单添加歌曲或整张专辑，移除（可撤销）、改名、删除
- 032 Music Server「今日推荐」：首页横向翻页展示服务器每天生成的歌单，可播放、赞/踩（可选原因）、换一个、另存为歌单，单曲可设为「不再推荐」
- 保存多个音乐服务器，并在设置页直接切换；每台服务器独立缓存资料库、最近播放和同步时间
- 发现并控制网络中的 Sonos 音箱
- 从 Plex 或 032 Music Server 串流音乐到 Sonos
- 实时播放控制和状态显示
- 将收藏变化、播放进度和播放完成记录同步回当前音乐服务器
- 032 Music Server 持久播放会话（apiRevision 3）：每次播放都是服务端会话加有序事件，切输出、单曲循环、重启应用、进程被杀都能保证计数、跳过和断点正确
- Material Design 3 界面

## 032 播放会话

当当前服务器是 032 Music Server 时，播放上报走独立的会话上报器（`Music032PlaybackReporter`），不再使用旧的 timeline/scrobble 接口（服务端已移除并返回 410，应用不会回退）：

- 每次明确播放都开启全新会话（UUID），绝不接续旧链；播放/缓冲时每 15 秒、暂停时每 60 秒心跳续租。上报器生命周期跟随真实播放而不是界面：熄屏不会结束会话；退出应用时由进程级上报 scope 以非阻塞、可取消、自带截止时间的有界方式尽力发送 `end(client_closed)`，强杀则由服务端 90 秒/10 分钟租约兜底。
- 单曲循环和切曲会用旧会话自己最后观测到的位置收尾并开启新的一次播放；用户跳过报 `skipped`、自动连播报 `completed`、播放失败报 `error`（最后一首失败绝不会被伪造成满时长的 completed）。
- 本机与 Sonos 之间切换输出时会话自动续链（takeover resume），同一次聆听只计一次；持久化会话快照只用于这种真正的输出切换接管。在队列里重选当前正在播放的曲目 = 从 0 开始的全新播放（新链）；普通暂停/继续与 STOPPED 传输层重推保持续链。
- 上报跑在串行 actor 上：观测值合并后投递、不占 1 秒播放循环；离线时周期性发送指数退避；401/400/404 等不可重试拒绝会可见地停止上报而不是持续打服务器。
- 会话一旦持续收不到观测（上报循环终止、音箱失联），上报器会以最后真实位置结束它而不是永久续租——同时保留续链意图，同一首恢复时接回原链而不是重复计数。仅改服务器名称不会中断上报；同一服务器仅换 token 会把当前聆听移交给用新凭据构建的上报器（不重复计数）；服务器地址变更则被保守地视为另一个资料库（服务端无法证明身份）：当前曲目的上报暂停并给出可见提示，直到下一次明确播放——绝不伪造跨库续链，也不自动在高位置另开新链计数。如果实际只是同一台服务器换了地址，代价仅是本次聆听在下次明确播放时重新开始。
- 计数由服务端按 50% 门槛加真实播放证据派生；从未真正出声的曲目绝不会被伪装成 playing——恢复事件只有在当前确实播放中、或该位置是实际播放中达到过的（played high-water 已过门槛）时才允许声称 playing；只是暂停后 seek 到高位置绝不会被恢复成 playing。
- Plex 源完全不受影响，保持原有 timeline/scrobble 行为。

## 系统要求

- Android 7.0 (API 24) 或更高版本
- Plex Media Server 或 032 Music Server
- 同一网络下的 Sonos 音箱

## 安装

1. 克隆仓库
```bash
git clone https://github.com/lux032/MusicBridge.git
```
2. 使用 Android Studio 打开项目
3. 构建并在设备上运行

### GitHub Actions 自动构建

推送代码、提交或更新 Pull Request 时，会自动运行 JVM 单元测试并构建 Debug 和 Release APK。也可以在仓库 **Actions → Build Android APK → Run workflow** 手动触发。

构建成功后，在运行详情页的 **Artifacts** 下载 `MusicBridge-apks-<运行编号>`，解压后即可获得两个可安装的 APK 和 `SHA256SUMS.txt` 校验文件。APK 保留 30 天；测试报告保留 14 天，Release 混淆映射保留 30 天。

- CI 使用 JDK 21、Android SDK 36 和项目自带的 Gradle Wrapper。非 PR 构建必须配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 四个 Secrets，缺失时构建失败，不会回退到 debug 签名发布。
- Release APK 开启压缩和混淆。分支推送及手动构建会自动发布正式签名的测试预发布；推送与应用版本匹配的 `v*` 标签（例如版本 `1.0.0` 对应 `v1.0.0`）会在测试和构建成功后发布正式 Release 并标记为 Latest。
- PR 构建不使用正式签名密钥。覆盖安装要求应用 ID、签名一致且版本码不降低；签名不匹配时需要卸载旧应用（会清除本地数据）。
- 单元测试失败时不会上传 APK，可下载测试报告排查原因；此流程不包含需要模拟器或真机的仪器测试。

## 配置

首次启动时：
1. 在设置页添加音乐服务器，并选择 `Plex` 或 `032 Music Server`
2. Plex 可填写服务器地址与 Plex Token，也可使用 Plex 用户名和密码自动发现
3. 032 Music Server 填写地址（例如：`http://192.168.1.100:4533`）、API Token；媒体 Token 留空时会使用 API Token
4. 如有多台服务器，可在设置页点击服务器卡片直接切换
5. 应用会自动发现网络中的 Sonos 音箱，选择音箱后即可播放

升级旧版本时，原有 Plex 连接会自动迁移为第一个服务器。首次打开新版会重建本地音乐缓存，服务器上的音乐、收藏和播放数据不会被删除。

## 应用截图

<p align="center">
  <img src="image/01.jpg" width="200"/>
  <img src="image/02.jpg" width="200"/>
  <img src="image/03.jpg" width="200"/>
  <img src="image/04.jpg" width="200"/>
  <img src="image/05.jpg" width="200"/>
</p>

## 许可证

MIT

# YAQMC-Android

<img src="assets/yaqmc-logo.png" alt="YAQMC-Android 图标" width="96" align="right">

**轻量版 QQ 音乐，专注日常听歌。**

继续使用自己的 QQ 音乐账号、会员曲库和歌单，在简洁的 Android 界面里浏览、播放和看歌词。

YAQMC-Android 是 [YAQMC](https://github.com/YAQMC/YAQMC) 的 Android 原生版，面向日常听歌进行精简和优化。

**[下载安卓版](https://ltzu.top/download)** · [GitHub 版本记录](https://github.com/YAQMC/YAQMC-Android/releases) · [免登录反馈](https://ltzu.top/yaqmc/feedback)

## 界面预览

真机截图稍后补充。

## 核心亮点

- **自己的音乐库**：登录 QQ 音乐账号，查看会员信息、收藏和歌单，选择账号权益内的音质。
- **直接的浏览与播放**：搜索歌曲、歌手、专辑和歌单，浏览推荐与音乐库，切换入口保留搜索条件和列表位置。
- **独立播放器**：封面与歌词左右滑动切换，播放队列支持下一首播放、移除和拖动排序。
- **歌词与外观**：逐字歌词、翻译与罗马音显示，支持歌词预设、配色和背景设置。
- **日常听歌所需**：后台播放、系统媒体控制、缓存管理和听歌统计。
- **音频共存**：可在设置中开启“允许同时播放”，与其他应用一起播放音频。
- **插件扩展**：兼容 YAQMC 插件包，支持音乐提供器、插件页面、样式和歌词场景。

## 下载与安装

在[最新正式版](https://github.com/YAQMC/YAQMC-Android/releases/latest)下载 `.apk` 并安装。希望体验预览版本时，可在[全部版本](https://github.com/YAQMC/YAQMC-Android/releases)中选择标记为 Pre-release 的版本。

- 系统：Android 8.0 及以上。
- 架构：ARM64（`arm64-v8a`）。
- 更新：`0.1.6` 起使用新包名，首次安装作为新应用，不自动带入旧版数据；此后的版本支持覆盖安装，保留登录和本地数据。
- 版本：发布日期、变化和安装包见对应 Release 页面。

## 使用与反馈

首次使用可从账号入口登录 QQ 音乐，再在媒体库查看收藏和歌单；也可以直接搜索、浏览推荐并播放歌曲。

**同时播放。** 设置中的“允许同时播放”默认关闭，开启后可与其他应用的音频共存；通话和耳机断连时仍会暂停音乐。

**插件。** 在“设置 → 插件”导入 `.yaqmc-plugin`，或查找插件商店中的发行包。安装后默认停用，按需授权并启用；媒体库会显示音乐提供器和插件页面，插件歌词场景可在歌词布局中选择。仅运行清单声明支持 Android 的包；安全模式可暂时停止所有插件。开发模式支持加载已构建的插件目录，重新加载先预览变更，再确认替换。兼容契约与插件开发资料见 [YAQMC](https://github.com/YAQMC/YAQMC) 和 [yaqmc-plugins](https://github.com/YAQMC/yaqmc-plugins)。

**歌曲与音质。** 可访问曲目和音质取决于账号会员、地区及原平台版权要求，已有 QQ 音乐会员权益继续由原账号提供。

**覆盖安装。** `0.1.6` 及后续版本沿用新包名和本项目签名；从 `0.1.5` 或更早版本切换时，`0.1.6` 会单独安装，登录需重新授权。

**反馈问题。** 从“设置 → 诊断与反馈”可提交脱敏诊断包。旧版可先用“分享诊断包”保存 ZIP，再到网站上传。遇到连续闪退，直接用浏览器填写出错前的操作、重开后的表现、应用版本和手机型号。提交成功后可凭反馈编号补充信息。[免登录反馈](https://ltzu.top/yaqmc/feedback) · [GitHub Issues](https://github.com/YAQMC/YAQMC-Android/issues)

## 后续方向

计划通过 B 站选定收藏夹补充 QQ 音乐没有的翻唱、现场等版本，只播放视频中的音频。这项功能目前仍在规划中。

## 开发

界面采用 Kotlin / Jetpack Compose，底层复用 Rust Core。完整的开发流程、工程结构、构建、测试与发布规范见[开发指南](docs/development.md)。

在已有开发环境中，用 VS Code 打开仓库根目录，在 `apps/android` 执行 Gradle 的 `:nativeApp:assembleDebug` 任务即可构建开发版。

开发版使用独立包名和本机调试签名，包含完整 Rust Core，无需维护者的私有密钥。

## 来源与许可

本项目基于 YAQMC 开发，与腾讯或 QQ 音乐没有从属或合作关系，歌曲访问和音质取决于账号权益及原平台限制。

项目采用 [GPL-3.0-or-later](LICENSE)。项目来源与第三方资料见[鸣谢](ACKNOWLEDGEMENTS.md)、[第三方声明](THIRD_PARTY_NOTICES.md)和[对应源码说明](CORRESPONDING_SOURCE_POLICY.md)。

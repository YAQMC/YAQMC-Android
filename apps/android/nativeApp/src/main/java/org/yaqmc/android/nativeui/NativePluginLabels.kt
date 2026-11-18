package org.yaqmc.android.nativeui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Keep review text useful while retaining exact protocol names at the Core boundary. */
@Composable
internal fun pluginPermissionLabel(permission: String): String {
    val zh = LocalConfiguration.current.locales[0].language == "zh"
    val labels = mapOf(
        "track.read" to ("读取正在播放的歌曲" to "Read the current track"),
        "lyrics.read" to ("读取当前歌词" to "Read current lyrics"),
        "player.read" to ("读取播放状态与队列" to "Read playback and queue"),
        "player.control" to ("控制播放器" to "Control playback"),
        "theme.read" to ("读取主题设置" to "Read the theme"),
        "plugin.storage" to ("保存插件私有数据" to "Save private plugin data"),
        "scene.register" to ("提供歌词场景与动态背景" to "Provide lyric scenes and backgrounds"),
        "style.register" to ("调整应用外观" to "Customize app styles"),
        "ui.contextMenu" to ("添加歌曲菜单操作" to "Add track menu actions"),
        "ui.playerBar" to ("添加播放器操作" to "Add player actions"),
        "ui.sidebar" to ("添加媒体库操作" to "Add library actions"),
        "ui.notify" to ("显示应用内提示" to "Show in-app notices"),
        "ui.songBadges" to ("显示歌曲来源标签" to "Show song source badges"),
        "ui.pages" to ("提供插件页面" to "Provide plugin pages"),
        "ui.navigation" to ("添加插件导航入口" to "Add plugin navigation"),
        "ui.layout" to ("调整首页内容顺序与显示" to "Reorder or hide Home sections"),
        "ui.lyrics" to ("添加歌词操作" to "Add lyric actions"),
        "files.read" to ("读取你选择的文件" to "Read files you choose"),
        "files.write" to ("写入你选择的文件" to "Write files you choose"),
        "provider.localFiles" to ("读取你授权的本地音乐目录" to "Read a music folder you authorize"),
        "shortcuts.register" to ("提供应用内快捷键" to "Register in-app shortcuts"),
        "playlists.read" to ("读取插件私有歌单" to "Read private plugin playlists"),
        "playlists.write" to ("编辑插件私有歌单" to "Edit private plugin playlists"),
        "provider.catalog" to ("提供音乐目录与搜索" to "Provide music catalogs and search"),
        "provider.playback" to ("提供音频播放来源" to "Provide audio sources"),
        "provider.recommendation" to ("提供音乐推荐" to "Provide music recommendations"),
        "provider.lyrics" to ("提供歌词" to "Provide lyrics"),
        "provider.comments" to ("提供歌曲评论" to "Provide song comments"),
        "provider.account" to ("管理此来源的登录与音乐资料" to "Manage this source's account and music library"),
    )
    if (permission.startsWith("network:")) return (if (zh) "访问网络：" else "Access network: ") + permission.removePrefix("network:")
    return labels[permission]?.let { if (zh) it.first else it.second } ?: permission
}

@Composable
internal fun pluginUpdateLabel(value: String): String {
    val zh = LocalConfiguration.current.locales[0].language == "zh"
    val labels = mapOf(
        "preparing" to ("正在准备" to "Preparing"), "downloading" to ("正在下载" to "Downloading"),
        "review" to ("等待确认" to "Ready for review"), "prepared" to ("等待确认" to "Ready for review"),
        "quiescing" to ("正在停止旧版本" to "Stopping the previous version"), "validating" to ("正在验证新版本" to "Validating the new version"),
        "applying" to ("正在应用更新" to "Applying update"), "completed" to ("更新完成" to "Updated"),
        "failed" to ("更新失败" to "Update failed"), "cancelled" to ("已取消" to "Cancelled"), "unchanged" to ("已是当前版本" to "Already current"),
        "replacement" to ("将替换同版本安装包" to "Replace the installed package at the same version"),
        "downgrade" to ("将安装较旧版本" to "Install an older version"),
        "uncomparable-version" to ("版本格式不同，请核对版本" to "Version formats differ; review the version"),
        "channel-change" to ("更新通道将改变" to "The update channel will change"),
        "source-change" to ("安装来源将改变" to "The installation source will change"),
        "trust-change" to ("可信发布来源将改变" to "The trusted publisher will change"),
        "session-change" to ("此来源的登录资料将重新建立" to "The source's login session will be recreated"),
        "permissions" to ("插件申请了新的权限" to "The plugin requests new permissions"),
        "unverified-source" to ("此安装包未由可信发布源签名" to "This package is not signed by a trusted publisher"),
    )
    return labels[value]?.let { if (zh) it.first else it.second } ?: value
}

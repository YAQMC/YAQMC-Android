package org.yaqmc.android.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import org.yaqmc.android.R

@UnstableApi
@Composable
internal fun Library(model: NativeViewModel) {
    val account by model.account.collectAsStateWithLifecycle()
    val plugins by model.plugins.state.collectAsStateWithLifecycle()
    val contributions by model.plugins.contributions.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val supportsAccount = plugins.providers.firstOrNull { it.text("providerId") == route.providerId }?.optJSONObject("capabilities")?.optBoolean("account", true) != false
    LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (supportsAccount && !account.authenticated) item { AccountLibraryNotice(account, model) }
        if (supportsAccount) item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryCollection(stringResource(R.string.native_favorites), Modifier.weight(1f).fillMaxHeight(), featured = true,
                    open = { model.openLibrary("favorites") }) { Icon(Icons.Default.Favorite, null, Modifier.size(30.dp)) }
                LibraryCollection(stringResource(R.string.native_my_playlists), Modifier.weight(1f).fillMaxHeight(), featured = false,
                    open = { model.openLibrary("account-playlists") }) { Icon(painterResource(R.drawable.ic_player_queue_music), null, Modifier.size(30.dp)) }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                if (supportsAccount) LibraryAction(stringResource(R.string.native_recent), R.drawable.ic_library_history) { model.openLibrary("recent") }
                LibraryAction(stringResource(R.string.native_statistics), R.drawable.ic_library_statistics) { model.navigate(NativeRoute("statistics")) }
            }
        }
        if (plugins.providers.any { !it.optBoolean("isDefault") }) item {
            Column {
                plugins.providers.forEach { provider ->
                    ListItem(headlineContent = { Text(provider.text("displayName", provider.text("providerId"))) },
                        modifier = Modifier.fillMaxWidth().testTag("provider-${provider.text("providerId")}").clickable(enabled = provider.optBoolean("available")) { model.selectProvider(provider.text("providerId")) })
                }
            }
        }
        contributions.optJSONArray("navigation").objects().forEach { entry -> item("plugin-nav-${entry.text("pluginId")}-${entry.text("id")}") {
            NativeSettingRow(entry.text("label"), stringResource(R.string.native_plugin_by, entry.text("pluginName")), "plugin-navigation-${entry.text("id")}") {
                model.navigate(NativeRoute("plugin-page", id = entry.text("pageId"), providerId = route.providerId, profileId = route.profileId, pluginId = entry.text("pluginId")))
            }
        } }
        item { NativePluginActions(model, "sidebar") }
    }
}

@Composable
private fun LibraryCollection(title: String, modifier: Modifier, featured: Boolean, open: () -> Unit, icon: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = modifier.heightIn(min = 148.dp), shape = RoundedCornerShape(20.dp),
        color = if (featured) colors.primary else colors.primary.copy(alpha = 0.10f),
        contentColor = if (featured) colors.onPrimary else colors.onSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            CompositionLocalProvider(LocalContentColor provides if (featured) colors.onPrimary else colors.primary) { icon() }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LibraryAction(title: String, icon: Int, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = open)
        .heightIn(min = 68.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

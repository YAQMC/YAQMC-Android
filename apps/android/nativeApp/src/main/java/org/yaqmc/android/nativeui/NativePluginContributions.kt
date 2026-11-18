package org.yaqmc.android.nativeui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import org.yaqmc.android.R

internal fun pluginSceneId(scene: JSONObject) = "plugin:${scene.text("pluginId")}:${scene.text("sceneId")}"

@UnstableApi
@Composable
internal fun nativeProviderAvailable(model: NativeViewModel, providerId: String): Boolean {
    val state by model.plugins.state.collectAsStateWithLifecycle()
    return providerId == "qqmusic" || state.providers.any { it.text("providerId") == providerId && it.optBoolean("available") }
}

@UnstableApi
@Composable
internal fun NativePluginScene(model: NativeViewModel, resource: JSONObject, modifier: Modifier = Modifier) {
    val runtime = model.plugins.runtime ?: return
    val pluginId = resource.text("pluginId")
    val sceneId = resource.text("sceneId")
    DisposableEffect(pluginId, sceneId, runtime) {
        model.plugins.scene(pluginId, sceneId)
        onDispose { model.plugins.scene(null, null) }
    }
    AndroidView(factory = {
        runtime.view.apply { (parent as? ViewGroup)?.removeView(this) }
    }, modifier = modifier.testTag("plugin-scene-$sceneId"))
}

@UnstableApi
@Composable
internal fun NativePluginPresetSettings(model: NativeViewModel) {
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val state by model.plugins.state.collectAsStateWithLifecycle()
    val scenes = state.resources.optJSONArray("scenes").objects()
    val selected = scenes.firstOrNull { pluginSceneId(it) == preferences.lyricPresets.selectedId }
    if (!preferences.lyricPresets.selectedId.startsWith("plugin:")) {
        NativeLyricPresets(preferences.lyricPresets, model::setLyricPresets) { PluginSceneChoices(model, scenes) }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (nativeBuiltinLyricPresets + preferences.lyricPresets.custom).forEach { preset ->
                FilterChip(false, onClick = { model.setLyricPresets(preferences.lyricPresets.select(preset.id)) }, label = { Text(nativePresetName(preset)) })
            }
        } }
        item { PluginSceneChoices(model, scenes) }
        if (selected != null) item { NativePluginScene(model, selected, Modifier.fillMaxWidth().height(420.dp)) }
        else item { Text(stringResource(R.string.native_plugin_scene_unavailable)) }
    }
}

@UnstableApi
@Composable
private fun PluginSceneChoices(model: NativeViewModel, scenes: List<JSONObject>) {
    val preferences by model.preferences.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        scenes.forEach { scene ->
            val id = pluginSceneId(scene)
            FilterChip(preferences.lyricPresets.selectedId == id, onClick = { model.setLyricPresets(preferences.lyricPresets.select(id)) },
                label = { Text(scene.optJSONObject("definition")?.text("name").orEmpty().ifBlank { scene.text("sceneId") }) }, modifier = Modifier.testTag("preset-$id"))
        }
    }
}

@UnstableApi
@Composable
internal fun NativePluginActions(model: NativeViewModel, slot: String, modifier: Modifier = Modifier) {
    val contributions by model.plugins.contributions.collectAsStateWithLifecycle()
    val actions = contributions.optJSONArray(slot).objects().filter { slot != "lyrics" || it.text("windowAction").isBlank() }
    if (actions.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { action -> TextButton(onClick = {
            model.plugins.action(action.text("pluginId"), JSONObject().put("id", action.text("id")).put("slot", slot))
        }, modifier = Modifier.testTag("plugin-action-${action.text("pluginId")}-${action.text("id")}")) { Text(action.text("label")) } }
    }
}

@UnstableApi
@Composable
internal fun NativePluginSongBadges(model: NativeViewModel, track: Track) {
    val state by model.plugins.state.collectAsStateWithLifecycle()
    val provider = track.json.optJSONObject("provider")
    val id = provider?.text("originProviderId")?.takeIf(String::isNotBlank) ?: track.providerId
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val badges = state.resources.optJSONArray("songBadges").objects().mapNotNull { declaration ->
        val ids = declaration.optJSONArray("providerIds")
        if (ids == null || (0 until ids.length()).none { index -> ids.optString(index) == id }) null else declaration
    }.take(4)
    if (badges.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { badge ->
            val labels = badge.optJSONObject("labels")
            val label = labels?.text(locale)?.takeIf(String::isNotBlank) ?: labels?.text(locale.substringBefore('-'))?.takeIf(String::isNotBlank) ?: badge.text("label")
            val color = pluginCssColor(badge.text("color")) ?: MaterialTheme.colorScheme.primary
            Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = RoundedCornerShape(4.dp)) { Text(label, Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

internal fun pluginCssColor(value: String): Color? {
    if (value.isBlank() || value == "transparent") return null
    val hex = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
    if (hex != null) return hex
    val rgb = Regex("rgba?\\(([^)]+)\\)").matchEntire(value)?.groupValues?.get(1)?.split(',')?.map { it.trim().toFloatOrNull() } ?: return null
    if (rgb.size !in 3..4 || rgb.any { it == null }) return null
    return Color(rgb[0]!! / 255f, rgb[1]!! / 255f, rgb[2]!! / 255f, rgb.getOrNull(3) ?: 1f)
}

internal fun pluginColorScheme(base: ColorScheme, contributions: JSONObject): ColorScheme {
    val theme = contributions.optJSONObject("theme") ?: return base
    val primary = pluginCssColor(theme.text("primary")) ?: base.primary
    val secondary = pluginCssColor(theme.text("secondary")) ?: base.secondary
    val alpha = theme.text("surface-alpha").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    return base.copy(primary = primary, secondary = secondary, surface = base.surface.copy(alpha = alpha))
}

private fun JSONObject.px(key: String, fallback: Float = 0f) = text(key).removeSuffix("px").toFloatOrNull()?.coerceIn(0f, 512f) ?: fallback
private fun JSONObject.region(region: String) = optJSONObject("theme")?.optJSONObject("regions")?.optJSONObject(region)

@UnstableApi
@Composable
internal fun pluginRegionColor(model: NativeViewModel, region: String, fallback: Color): Color {
    val contributions by model.plugins.contributions.collectAsStateWithLifecycle()
    return contributions.region(region)?.text("background-color")?.let(::pluginCssColor) ?: fallback
}

@UnstableApi
@Composable
internal fun pluginTextStyle(model: NativeViewModel, base: TextStyle): TextStyle {
    val contributions by model.plugins.contributions.collectAsStateWithLifecycle()
    val style = contributions.region("track-title") ?: return base
    val shadow = style.optJSONObject("textShadow")?.let {
        Shadow(pluginCssColor(it.text("color")) ?: Color.Transparent, Offset(it.optDouble("x").toFloat(), it.optDouble("y").toFloat()), it.optDouble("blur").toFloat())
    }
    return base.copy(color = pluginCssColor(style.text("color")) ?: base.color, letterSpacing = style.text("letter-spacing").removeSuffix("px").toFloatOrNull()?.sp ?: base.letterSpacing, shadow = shadow)
}

internal fun pluginShapes(base: Shapes, contributions: JSONObject): Shapes {
    if (contributions.optJSONObject("theme")?.optBoolean("styleActive") != true) return base
    val radius = contributions.optJSONObject("theme")?.px("radius-card") ?: return base
    return base.copy(small = base.small.copy(all = CornerSize(radius.dp)), medium = base.medium.copy(all = CornerSize(radius.dp)), large = base.large.copy(all = CornerSize(radius.dp)))
}

@UnstableApi
@Composable
internal fun Modifier.pluginRegion(model: NativeViewModel, region: String): Modifier {
    val contributions by model.plugins.contributions.collectAsStateWithLifecycle()
    val style = contributions.region(region) ?: return this
    val shape = RoundedCornerShape(topStart = style.px("border-top-left-radius").dp, topEnd = style.px("border-top-right-radius").dp, bottomStart = style.px("border-bottom-left-radius").dp, bottomEnd = style.px("border-bottom-right-radius").dp)
    var result = this
    style.optJSONObject("boxShadow")?.let { shadow ->
        result = result.drawBehind {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = (pluginCssColor(style.text("background-color")) ?: Color.Transparent).toArgb()
                setShadowLayer(shadow.optDouble("blur").toFloat() * density, shadow.optDouble("x").toFloat() * density, shadow.optDouble("y").toFloat() * density, (pluginCssColor(shadow.text("color")) ?: Color.Transparent).toArgb())
            }
            drawContext.canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, style.px("border-top-left-radius") * density, style.px("border-top-left-radius") * density, paint)
        }
    }
    result = result.clip(shape)
    pluginCssColor(style.text("background-color"))?.takeIf { it.alpha > 0f }?.let { result = result.background(it, shape) }
    val outline = style.px("outline-width")
    if (outline > 0) pluginCssColor(style.text("outline-color"))?.let { result = result.border(outline.dp, it, shape) }
    result = result.drawWithContent {
        drawContent()
        for (edge in listOf("top", "right", "bottom", "left")) {
            val width = style.px("border-$edge-width") * density
            val color = pluginCssColor(style.text("border-$edge-color")) ?: continue
            if (width <= 0f) continue
            val (start, end) = when (edge) {
                "top" -> Offset(0f, width / 2) to Offset(size.width, width / 2)
                "bottom" -> Offset(0f, size.height - width / 2) to Offset(size.width, size.height - width / 2)
                "left" -> Offset(width / 2, 0f) to Offset(width / 2, size.height)
                else -> Offset(size.width - width / 2, 0f) to Offset(size.width - width / 2, size.height)
            }
            drawLine(color, start, end, width)
        }
    }
    val minHeight = style.px("min-height")
    if (minHeight > 0) result = result.heightIn(min = minHeight.dp)
    return result
}

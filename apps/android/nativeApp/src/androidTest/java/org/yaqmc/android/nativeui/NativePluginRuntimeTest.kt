package org.yaqmc.android.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Runs the unchanged Android-enabled upstream API v6 script in the real WebView/Worker engine. */
@UnstableApi
class NativePluginRuntimeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(NativeDevicePreferences(ComponentActivity::class.java)).around(compose)
    private lateinit var model: NativeViewModel
    @After fun close() { compose.runOnIdle { if (::model.isInitialized) { model.plugins.close(); model.viewModelScope.cancel() } } }

    @Test fun upstreamLibraryWorkerRendersNativePageHandlesActionsAndRevokesContributions() {
        val core = PluginCore()
        install(core)
        compose.waitUntil(20000) { model.plugins.contributions.value.optJSONArray("pages")?.length() == 1 }
        assertTrue(core.calls.any { it.first == "plugin_runtime_ready" })
        compose.runOnIdle { model.navigate(NativeRoute("plugins")) }
        compose.onNodeWithTag("plugin-card-${core.id}").assertIsDisplayed()
        capture("plugins-manager")
        compose.runOnIdle { model.navigate(NativeRoute("plugin-page", "library", pluginId = core.id)) }
        compose.onNodeWithText("Local playlist tools").assertIsDisplayed()
        compose.onNodeWithTag("plugin-page-action-create").performClick()
        compose.waitUntil(10000) { core.playlists.size == 1 && model.plugins.contributions.value.toString().contains("Created a local playlist.") }
        compose.onNodeWithText("Created a local playlist.").assertIsDisplayed()
        assertEquals("My plugin playlist", core.playlists.single().text("name"))
        assertFalse(core.calls.any { it.first in setOf("provider_create_playlist", "provider_add_playlist_track", "provider_set_favorite") })
        compose.runOnIdle { assertTrue(model.plugins.key(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_L, 0, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON))) }
        compose.waitUntil(10000) { model.plugins.contributions.value.toString().contains("Refreshed local playlists") }
        compose.runOnIdle { model.plugins.shortcut(core.id, "refresh", "control+K") }
        compose.waitUntil(5000) { model.plugins.contributions.value.optJSONArray("shortcuts")?.optJSONObject(0)?.text("accelerator") == "control+K" }
        capture("plugins-library-page")
        compose.runOnIdle { model.plugins.setEnabled(core.id, false, emptySet()) }
        compose.waitUntil(10000) { model.plugins.contributions.value.optJSONArray("pages").objects().isEmpty() && model.plugins.runtime == null }
        compose.onNodeWithText("此插件页面暂不可用。").assertIsDisplayed()
        assertTrue(core.calls.any { it.first == "plugin_runtime_stop" })
    }

    @Test fun permissionReviewCancelDoesNotInstallAndNewPackagesStayDisabled() {
        val core = PluginCore(installed = false)
        install(core)
        compose.runOnIdle { model.navigate(NativeRoute("plugins")); model.plugins.inspect("fixture.yaqmc-plugin") }
        compose.waitUntil(5000) { model.plugins.state.value.inspection != null && !model.plugins.state.value.busy }
        compose.onNodeWithText("取消").performClick()
        assertFalse(core.calls.any { it.first == "plugin_install" })
        compose.runOnIdle { model.plugins.inspect("fixture.yaqmc-plugin") }
        compose.waitUntil(5000) { model.plugins.state.value.inspection != null && !model.plugins.state.value.busy }
        compose.onNodeWithTag("plugin-review-confirm").performClick()
        compose.waitUntil(5000) { model.plugins.state.value.records.size == 1 && !model.plugins.state.value.busy }
        assertFalse(core.calls.single { it.first == "plugin_install" }.second.getJSONObject("request").getBoolean("enable"))
        assertFalse(core.calls.any { it.first == "plugin_runtime_start" })
        compose.onNodeWithText("1.0.0 · 已停用").assertIsDisplayed()
    }

    @Test fun publicStyleTokensAndRegionsAreProjectedAndRevokedByTheHost() {
        val core = PluginCore(styled = true)
        install(core)
        compose.waitUntil(10000) { model.plugins.contributions.value.optJSONObject("theme")?.text("primary") == "#ff8fab" }
        val theme = model.plugins.contributions.value.getJSONObject("theme")
        assertEquals("22px", theme.text("radius-card"))
        assertEquals("96px", theme.text("player-height"))
        assertEquals("rgba(255,143,171,1)", theme.getJSONObject("regions").getJSONObject("track-title").text("color"))
        assertTrue(theme.getJSONObject("regions").getJSONObject("sidebar").text("background-color").startsWith("rgba("))
        compose.runOnIdle { model.plugins.setEnabled(core.id, false, emptySet()) }
        compose.waitUntil(10000) { model.plugins.runtime == null }
        assertEquals(0, model.plugins.contributions.value.length())
    }

    @Test fun sharedSceneSchemaRendersLyricsAndScopesCanvasAndInstanceMutations() {
        val core = PluginCore(scene = true)
        install(core)
        compose.waitUntil(20000) { core.calls.any { it.first == "plugin_runtime_ready" } }
        compose.runOnIdle {
            model.plugins.present(core.document)
            model.setLyricPresets(NativeLyricPresetPreferences(selectedId = "plugin:${core.id}:studio-split"))
            model.navigate(NativeRoute("lyric-presets"))
        }
        compose.onNodeWithTag("plugin-scene-studio-split").performScrollTo().assertIsDisplayed()
        fun read(script: String): String {
            val result = java.util.concurrent.atomic.AtomicReference<String?>()
            compose.runOnIdle { model.plugins.runtime!!.view.evaluateJavascript(script, result::set) }
            compose.waitUntil(5000) { result.get() != null }
            return result.get()!!
        }
        compose.waitUntil(20000) { core.calls.any { it.first == "plugin_bridge" && it.second.optJSONObject("request")?.text("method") == "scenes.setState" } }
        assertTrue(core.calls.any { it.first == "player_set_lyrics" && it.second.optJSONObject("document")?.text("songId") == "scene-fixture" })
        compose.waitUntil(10000) { read("document.querySelector('.lyrics-scene').dataset.orientation") == "\"portrait\"" }
        assertEquals("\"portrait\"", read("document.querySelector('.lyrics-scene').dataset.orientation"))
        compose.waitUntil(10000) { read("matchMedia('(orientation: portrait)').matches") == "true" }
        assertEquals("\"1\"", read("document.querySelector('.lyrics-scene').style.getPropertyValue('--scene-pulse')"))
        assertTrue(read("document.querySelector('.lyrics-scene').textContent").contains("Scene fixture"))
        // The original Studio scene requests artwork, so registering a renderer does not select it.
        assertEquals("0", read("document.querySelectorAll('canvas[data-plugin-background]').length"))
        compose.runOnIdle { core.canvas = true; model.plugins.refresh() }
        compose.waitUntil(10000) { read("document.querySelectorAll('canvas[data-plugin-background]').length") == "1" }
        assertEquals("true", read("(()=>{const s=document.querySelector('.lyrics-scene').getBoundingClientRect();return ['controls','progress'].every(part=>{const c=document.querySelector('.lyrics-transport__'+part).getBoundingClientRect();return c.height>0&&c.top>=s.top-1&&c.bottom<=s.bottom+1&&c.left>=s.left-1&&c.right<=s.right+1})})()"))
        capture("plugins-scene-portrait")
        compose.runOnIdle { model.plugins.setEnabled(core.id, false, emptySet()) }
        compose.waitUntil(10000) { model.plugins.state.value.resources.optJSONArray("scenes")?.length() == 0 }
        compose.onNodeWithTag("plugin-scene-studio-split").assertDoesNotExist()
        compose.onNodeWithText("此场景暂不可用，请启用对应插件或选择其他布局。").assertIsDisplayed()
    }

    private fun install(core: PluginCore) {
        compose.runOnUiThread {
            model = NativeViewModel(compose.activity.application, core)
            model.setLanguage("zh-CN")
            model.plugins.refresh()
        }
        compose.setContent { NativeApp(model, {}, { _, _ -> }, {}) }
        compose.waitUntil(5000) { model.plugins.state.value.loaded }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class PluginCore(var installed: Boolean = true, val scene: Boolean = false, val styled: Boolean = false) : NativeCoreApi {
        var canvas = false
        private val assets = InstrumentationRegistry.getInstrumentation().context.assets
        private val manifest = JSONObject(assets.open(if (scene) "plugins/studio-manifest.json" else "plugins/library-manifest.json").bufferedReader().use { it.readText() })
        private val source = if (scene) """definePlugin({ async activate(ctx) {
          await ctx.scenes.background.register('studio-split', { mount(surface) { const paint=surface.canvas.getContext('2d'); paint.fillStyle='#19304a'; paint.fillRect(0,0,surface.pixelWidth,surface.pixelHeight); } });
          ctx.scenes.onMount('studio-split', async () => { await ctx.scenes.setVariable('pulse', 1); await ctx.scenes.setState('ready','yes'); });
        } });""" else assets.open("plugins/library.js").bufferedReader().use { it.readText() }
        val document = JSONObject("""{"songId":"scene-fixture","syncMode":"word","metadata":{"sourceLabel":"Fixture","offsetMs":0},"lines":[{"id":"one","startMs":0,"endMs":5000,"text":"Shared scene lyrics","words":[{"startMs":0,"endMs":1200,"text":"Shared "},{"startMs":1200,"endMs":2500,"text":"scene "},{"startMs":2500,"endMs":5000,"text":"lyrics"}]}]}""")
        private val player = JSONObject("""{"queue":[{"id":"scene-fixture","title":"Scene fixture","artists":[{"id":"artist","name":"Fixture artist"}],"album":{"id":"album","title":"Fixture album"},"artwork":{"src":"","alt":"","dominantColor":"#19304a"},"provider":{"providerId":"qqmusic","trackId":"scene-fixture"},"durationMs":5000}],"currentIndex":0,"positionMs":1800,"playbackDurationMs":5000,"isPlaying":false,"sessionId":"fixture-session"}""")
        val id = manifest.text("id")
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val playlists = CopyOnWriteArrayList<JSONObject>()
        var enabled = installed
        private fun record() = JSONObject(manifest.toString()).put("enabled", enabled).put("status", if (enabled) "active" else "disabled")
            .put("compatible", true).put("grantedPermissions", manifest.getJSONArray("permissions"))
        override suspend fun invoke(method: String, params: JSONObject, host: Boolean): Any? {
            calls += method to JSONObject(params.toString())
            return when (method) {
                "plugin_list" -> JSONArray(if (installed) listOf(record()) else emptyList<JSONObject>())
                "plugin_active_resources" -> JSONObject().put("safeMode", false).put("developerMode", false).put("scripts", JSONArray(if (enabled) listOf(JSONObject().put("pluginId", id).put("pluginName", manifest.text("name")).put("source", source).put("packageSha256", "fixture")) else emptyList<JSONObject>()))
                    .put("styles", JSONArray(if (styled && enabled) listOf(JSONObject().put("pluginId", id).put("css", assets.open("plugins/sakura.css").bufferedReader().use { it.readText() })) else emptyList<JSONObject>()))
                    .put("scenes", JSONArray(if (scene && enabled) listOf(JSONObject().put("pluginId", id).put("pluginName", "Studio").put("sceneId", "studio-split").put("definition", JSONObject(assets.open("plugins/studio-split.scene.json").bufferedReader().use { it.readText() }).apply {
                        // A host-only Canvas fixture; the upstream package and its files stay unchanged.
                        if (canvas) { getJSONObject("scene").getJSONObject("background").put("source", "plugin"); optJSONObject("portraitScene")?.getJSONObject("background")?.put("source", "plugin") }
                    }).put("css", assets.open("plugins/studio-split.css").bufferedReader().use { it.readText() })) else emptyList<JSONObject>()))
                "player_snapshot" -> if (scene) player else JSONObject()
                "provider_list" -> JSONArray("""[{"providerId":"qqmusic","displayName":"QQ Music","isDefault":true,"available":true}]""")
                "provider_account_snapshot" -> JSONObject("""{"state":"guest","revision":1,"capabilities":{}}""")
                "plugin_runtime_start" -> "test-runtime"
                "plugin_inspect_path" -> JSONObject().put("manifest", manifest).put("permissions", manifest.getJSONArray("permissions"))
                "plugin_install" -> { installed = true; enabled = params.getJSONObject("request").getBoolean("enable"); record() }
                "plugin_set_enabled" -> { enabled = params.getJSONObject("request").getBoolean("enabled"); record() }
                "plugin_bridge" -> {
                    val request = params.getJSONObject("request")
                    check(enabled && request.text("token") == "test-runtime")
                    val payload = request.optJSONObject("payload") ?: JSONObject()
                    when (request.text("method")) {
                        "playlists.list" -> JSONArray(playlists.map { JSONObject(it.toString()).put("trackCount", it.optJSONArray("tracks")?.length() ?: 0) })
                        "playlists.create" -> JSONObject(payload.toString()).put("id", "local-${playlists.size + 1}").put("description", "").also { playlists += it }
                        "playlists.get" -> playlists.single { it.text("id") == payload.text("id") }
                        "playlists.remove" -> { playlists.removeIf { it.text("id") == payload.text("id") }; JSONObject().put("ok", true) }
                        else -> JSONObject().put("ok", true)
                    }
                }
                else -> JSONObject()
            }
        }
    }
}

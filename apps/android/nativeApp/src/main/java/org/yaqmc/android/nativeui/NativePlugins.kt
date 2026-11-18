package org.yaqmc.android.nativeui

import android.app.Application
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.yaqmc.android.plugin.PluginPackageStaging
import org.yaqmc.android.plugin.PluginDeveloperSource
import androidx.media3.common.util.UnstableApi
import org.yaqmc.android.core.CoreManager
import android.os.Handler
import android.os.Looper

internal data class NativePluginState(
    val records: List<JSONObject> = emptyList(),
    val resources: JSONObject = JSONObject(),
    val providers: List<JSONObject> = emptyList(),
    val inspection: JSONObject? = null,
    val settings: JSONObject? = null,
    val updates: List<JSONObject> = emptyList(),
    val store: List<JSONObject> = emptyList(),
    val storePage: JSONObject = JSONObject(),
    val updateInfo: JSONObject? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** Core owns packages and grants. Android only stages documents and presents the shared records. */
@UnstableApi
internal class NativePlugins(private val application: Application, internal val core: NativeCoreApi, private val scope: CoroutineScope) : CoreManager.Callback {
    private val _state = MutableStateFlow(NativePluginState())
    val state = _state.asStateFlow()
    private val _contributions = MutableStateFlow(JSONObject())
    val contributions = _contributions.asStateFlow()
    private val staging = PluginPackageStaging(File(application.cacheDir, "plugin-import"))
    private var stagedPath: String? = null
    private var stagedTree: Uri? = null
    private val developerSources = PluginDeveloperSource(application)
    private var operation: Job? = null
    private var editingLease: Job? = null
    internal var runtime: NativePluginRuntime? = null
    internal val nativeFiles = NativePluginFilesBridge(application, core, scope)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var refreshPending = false
    private val lyricsMutex = Mutex()
    private var publishedLyrics: String? = null
    private val preferences = application.getSharedPreferences("native-plugin-runtime", android.content.Context.MODE_PRIVATE)
    private val preparingInputs = runCatching { JSONObject(preferences.getString("imports", "{}") ?: "{}") }.getOrDefault(JSONObject())
    internal var presentation = JSONObject().put("shortcutBindings", runCatching { JSONObject(preferences.getString("shortcuts", "{}") ?: "{}") }.getOrDefault(JSONObject()))
        private set
    init { CoreManager.addCallback(this) }
    override fun onCoreResponse(id: Long, json: String) = Unit
    override fun onCoreEvent(sequence: Long, channel: String, json: String) {
        handler.post {
            if (closed) return@post
            when (channel) {
                "host://provider-local-files" -> nativeFiles.acceptProviderEvent(json)
                "plugin://changed" -> refresh()
                "plugin://update-progress" -> {
                    runCatching { JSONObject(json) }.getOrNull()?.let { operation ->
                        _state.value = _state.value.copy(updates = _state.value.updates.filterNot { it.text("operationId") == operation.text("operationId") } + operation)
                        if (runtime == null && operation.text("phase") in setOf("quiescing", "validating") && operation.optJSONObject("preview")?.optBoolean("needsRenderer") == true) runtime = NativePluginRuntime(application, this, scope)
                        runtime?.update(operation)
                    }
                    refresh()
                }
            }
        }
    }

    fun refresh() = run {
        if (closed) return@run
        if (_state.value.busy) { refreshPending = true; return@run }
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) { perform { readState() } }
    }

    private suspend fun readState() {
        val records = (core.invoke("plugin_list") as? JSONArray).objects()
        val resources = core.objectResult("plugin_active_resources")
        val providers = (core.invoke("provider_list") as? JSONArray).objects()
        readUpdates()
        syncCoreLyrics(resources, records)
        val needsRuntime = listOf("scripts", "styles", "scenes").any { resources.optJSONArray(it)?.length()?.let { size -> size > 0 } == true } ||
                _state.value.updates.any { it.text("phase") in setOf("quiescing", "validating") && it.optJSONObject("preview")?.optBoolean("needsRenderer") == true }
        if (!needsRuntime && runtime != null) { runtime?.close(); runtime = null; _contributions.value = JSONObject() }
        if (runtime == null && needsRuntime) {
            runtime = NativePluginRuntime(application, this, scope)
        }
        runtime?.presentation(presentation)
        runtime?.synchronize(resources, records)
        _state.value.updates.filter { it.text("phase") in setOf("quiescing", "validating") }.forEach { runtime?.update(it) }
        _state.value = _state.value.copy(records = records, resources = resources, providers = providers, loaded = true)
    }

    fun select(uri: Uri) = run {
        if (_state.value.busy) return@run
        dismissInspection()
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) { perform {
            val file = withContext(Dispatchers.IO) { staging.stage { application.contentResolver.openInputStream(uri) } }
            stagedPath = file.absolutePath
            val inspection = try { core.objectResult("plugin_inspect_path", JSONObject().put("path", file.absolutePath)) }
                catch (error: Exception) { stagedPath = null; staging.clear(); throw error }
            _state.value = _state.value.copy(inspection = inspection)
        } }
    }

    fun selectDirectory(uri: Uri) = mutate {
        check(_state.value.resources.optBoolean("developerMode"))
        if (stagedTree != null) stagedPath?.let(developerSources::release)
        stagedTree = null; stagedPath = null; staging.clear()
        application.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val directory = withContext(Dispatchers.IO) { developerSources.mirror(uri) }
        try {
            val inspection = core.objectResult("plugin_inspect_path", JSONObject().put("path", directory.absolutePath))
            stagedTree = uri; stagedPath = directory.absolutePath
            _state.value = _state.value.copy(inspection = inspection)
        } catch (error: Exception) { developerSources.release(directory.absolutePath); throw error }
    }

    internal fun inspect(path: String) = mutate {
        stagedPath = path
        _state.value = _state.value.copy(inspection = core.objectResult("plugin_inspect_path", JSONObject().put("path", path)))
    }

    fun dismissInspection() {
        if (_state.value.busy) return
        if (stagedTree != null) stagedPath?.let(developerSources::release)
        stagedTree = null; stagedPath = null
        staging.clear()
        _state.value = _state.value.copy(inspection = null)
    }

    fun install(grants: Set<String>) = mutate {
        val path = stagedPath ?: return@mutate
        val tree = stagedTree
        val id = _state.value.inspection?.optJSONObject("manifest")?.text("id").orEmpty()
        if (_state.value.records.any { it.text("id") == id }) {
            val retained = if (tree == null) staging.handoff(File(path)) else File(path)
            try {
                val operation = core.objectResult("plugin_update_prepare", JSONObject().put("request", JSONObject().put("pluginId", id).put("path", retained.absolutePath).put("unpacked", tree != null)))
                preparingInputs.put(operation.getString("operationId"), retained.absolutePath)
                preferences.edit().putString("imports", preparingInputs.toString()).apply()
            } catch (error: Exception) {
                if (error !is CancellationException && (error as? CoreFailure)?.code != "timeout") staging.release(retained.absolutePath)
                throw error
            }
            readUpdates()
        } else {
            core.invoke(if (tree == null) "plugin_install" else "plugin_install_unpacked", JSONObject().put("request", JSONObject().put("path", path).put("enable", false).put("grant", JSONArray(grants.toList()))))
        }
        if (tree != null) preferences.edit().putString("tree:$id", tree.toString()).apply()
        stagedTree = null; stagedPath = null
        staging.clear()
        _state.value = _state.value.copy(inspection = null)
        readState()
    }

    fun setEnabled(id: String, enabled: Boolean, grants: Set<String>) = mutate {
        runtime?.retire(id)
        try { core.invoke("plugin_set_enabled", JSONObject().put("request", JSONObject().put("id", id).put("enabled", enabled).put("grant", JSONArray(grants.toList())))) }
        finally { runtime?.resume(id); readState() }
    }

    fun uninstall(id: String, removeData: Boolean) = mutate {
        runtime?.retire(id)
        try { core.invoke("plugin_uninstall", JSONObject().put("request", JSONObject().put("id", id).put("removeData", removeData))) }
        finally { runtime?.resume(id); readState() }
    }

    fun safeMode(enabled: Boolean) = mutate {
        runtime?.retire()
        try { core.invoke("plugin_set_safe_mode", JSONObject().put("enabled", enabled)) }
        finally { runtime?.resume(); readState() }
    }
    fun developerMode(enabled: Boolean) = mutate { core.invoke("plugin_set_developer_mode", JSONObject().put("enabled", enabled)); readState() }
    fun reload(id: String) = mutate {
        check(_state.value.resources.optBoolean("developerMode"))
        val uri = preferences.getString("tree:$id", null)?.let(Uri::parse) ?: throw IllegalStateException("plugin developer directory is unavailable")
        val directory = withContext(Dispatchers.IO) { developerSources.mirror(uri) }
        val operation = core.objectResult("plugin_update_prepare", JSONObject().put("request", JSONObject().put("pluginId", id).put("path", directory.absolutePath).put("unpacked", true)))
        preparingInputs.put(operation.getString("operationId"), directory.absolutePath)
        preferences.edit().putString("imports", preparingInputs.toString()).apply()
        readUpdates()
    }
    fun settings(id: String) = mutate {
        _state.value = _state.value.copy(settings = core.objectResult("plugin_settings_get", JSONObject().put("id", id)))
    }
    fun saveSettings(id: String, values: JSONObject, onSaved: () -> Unit = {}) = mutate {
        val saved = core.objectResult("plugin_settings_set", JSONObject().put("request", JSONObject().put("id", id).put("values", values)))
        runtime?.event(id, "settings.changed", saved)
        readState()
        onSaved()
    }
    fun searchStore(query: String, filter: String = "all", sort: String = "best-match", page: Int = 1) = mutate {
        val result = core.objectResult("plugin_store_search", JSONObject().put("request", JSONObject().put("query", query).put("filter", filter).put("sort", sort).put("page", page)))
        _state.value = _state.value.copy(store = if (page == 1) result.optJSONArray("items").objects() else (_state.value.store + result.optJSONArray("items").objects()).distinctBy { it.text("fullName") }, storePage = result)
    }
    fun updateInfo(id: String) = mutate { readUpdateInfo(id) }
    private suspend fun readUpdateInfo(id: String) {
        _state.value = _state.value.copy(updateInfo = core.objectResult("plugin_update_policy_get", JSONObject().put("id", id)).put("pluginId", id))
    }
    fun updatePolicy(request: JSONObject, onSaved: () -> Unit) = mutate {
        core.invoke("plugin_update_policy_set", JSONObject().put("request", request)); readUpdateInfo(request.getString("pluginId")); onSaved()
    }
    fun checkUpdates(id: String? = null) = mutate {
        core.invoke("plugin_update_check", JSONObject().put("pluginId", id)); readUpdates(); if (id != null) readUpdateInfo(id)
    }
    fun editing(id: String, active: Boolean) {
        editingLease?.cancel()
        editingLease = scope.launch {
            do {
                runCatching { core.invoke("plugin_update_editing", JSONObject().put("id", id).put("editing", active)) }
                if (active) kotlinx.coroutines.delay(10_000)
            } while (active)
        }
    }
    fun storeReleases(request: JSONObject, onResult: (JSONObject) -> Unit) = mutate {
        onResult(core.objectResult("plugin_store_releases", JSONObject().put("request", request)))
    }
    fun prepareUpdate(request: JSONObject) = mutate {
        core.invoke("plugin_update_prepare", JSONObject().put("request", request)); readUpdates()
    }
    fun applyUpdate(request: JSONObject) = mutate {
        core.invoke("plugin_update_apply", JSONObject().put("request", request)); readUpdates(); readState()
    }
    fun cancelUpdate(id: String) = mutate {
        core.invoke("plugin_update_cancel", JSONObject().put("operationId", id)); readUpdates()
    }
    private suspend fun readUpdates() {
        val result = core.invoke("plugin_update_status")
        val updates = when (result) { is JSONArray -> result.objects(); is JSONObject -> result.optJSONArray("operations").objects(); else -> emptyList() }
        val reading = updates.filter { it.text("phase") in setOf("preparing", "downloading") }.map { it.text("operationId") }.toSet()
        preparingInputs.keys().asSequence().toList().filter { it !in reading }.forEach { id -> staging.release(preparingInputs.getString(id)); preparingInputs.remove(id) }
        preferences.edit().putString("imports", preparingInputs.toString()).apply()
        _state.value = _state.value.copy(updates = updates)
    }
    internal fun acceptContributions(value: JSONObject) { _contributions.value = value }
    internal fun rendererGone(renderer: NativePluginRuntime) {
        if (runtime !== renderer) return
        renderer.close(); runtime = null
        _contributions.value = JSONObject()
        _state.value = _state.value.copy(error = application.getString(org.yaqmc.android.R.string.native_plugin_runtime_failed))
    }
    internal fun shortcutBindings(value: JSONObject) {
        if (value.length() > 256) return
        preferences.edit().putString("shortcuts", value.toString()).apply()
        presentation.put("shortcutBindings", value)
    }
    internal fun key(event: android.view.KeyEvent): Boolean {
        val key = pluginKeyEvent(event) ?: return false
        val accelerator = pluginAccelerator(key)
        if (_contributions.value.optJSONArray("shortcuts").objects().none { it.text("accelerator").replace("mod+", "control+") == accelerator }) return false
        runtime?.key(key) ?: return false
        return true
    }
    internal fun shortcut(pluginId: String, id: String, accelerator: String?) {
        runtime?.shortcut(pluginId, id, accelerator)
    }
    internal fun present(document: JSONObject?) {
        presentation.put("document", document ?: JSONObject.NULL)
        runtime?.presentation(presentation)
        scope.launch { runCatching { syncCoreLyrics(_state.value.resources, _state.value.records) } }
    }
    private suspend fun syncCoreLyrics(resources: JSONObject, records: List<JSONObject>) = lyricsMutex.withLock {
        val scripts = resources.optJSONArray("scripts").objects().map { it.text("pluginId") }.toSet()
        val reader = records.any { record -> record.text("id") in scripts && record.optJSONArray("grantedPermissions")?.let { permissions -> (0 until permissions.length()).any { permissions.optString(it) == "lyrics.read" } } == true }
        if (!reader) { publishedLyrics = null; return@withLock }
        val document = presentation.optJSONObject("document")
        val encoded = document?.toString() ?: "null"
        if (publishedLyrics == encoded) return@withLock
        core.invoke("player_set_lyrics", JSONObject().put("document", document ?: JSONObject.NULL))
        publishedLyrics = encoded
    }
    internal fun displayPreferences(value: NativePreferences, dark: Boolean) {
        presentation.put("colorMode", if (dark) "dark" else "light").put("lyrics", JSONObject(value.lyrics.json()))
            .put("language", application.withNativeLanguage(value).resources.configuration.locales[0].toLanguageTag())
        val colors = nativeColorScheme(value.appearance, dark)
        fun color(color: androidx.compose.ui.graphics.Color) = "#%02x%02x%02x".format((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        presentation.put("theme", JSONObject().put("primary", color(colors.primary)).put("secondary", color(colors.secondary)))
        runtime?.presentation(presentation)
    }
    internal fun scene(pluginId: String?, sceneId: String?) { runtime?.scene(pluginId, sceneId) }
    internal fun error(message: String?) { _state.value = _state.value.copy(error = message) }
    internal fun action(pluginId: String, event: JSONObject) { runtime?.event(pluginId, "ui.action", event) }
    internal fun page(pluginId: String, pageId: String, mounted: Boolean) { runtime?.event(pluginId, if (mounted) "page.mounted" else "page.unmounted", JSONObject().put("pageId", pageId)) }
    private fun mutate(block: suspend () -> Unit) {
        if (_state.value.busy) return
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) { perform(block) }
    }
    private suspend fun perform(block: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName) }
        finally {
            _state.value = _state.value.copy(busy = false)
            if (refreshPending && !closed) { refreshPending = false; refresh() }
        }
    }
    fun close() { closed = true; CoreManager.removeCallback(this); operation?.cancel(); editingLease?.cancel(); runtime?.close(); runtime = null; nativeFiles.close(); staging.clear() }
}

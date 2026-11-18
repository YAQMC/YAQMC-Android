package org.yaqmc.android.nativeui

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import org.json.JSONObject
import org.yaqmc.android.core.CoreManager
import org.yaqmc.android.plugin.PluginFileCall

/** A trusted local relay hosts unmodified SDK workers. Plugin code never receives a JNI object. */
@UnstableApi
@SuppressLint("SetJavaScriptEnabled")
internal class NativePluginRuntime(application: Application, private val owner: NativePlugins, private val scope: CoroutineScope) : CoreManager.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var closed = false
    private val pending = mutableListOf<JSONObject>()
    private val runtimeTokens = mutableSetOf<String>()
    private var artworkUrl = ""
    private var artworkData: String? = null
    private var artworkJob: Job? = null
    private var currentPresentation = JSONObject()
    val view = WebView(application).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        addJavascriptInterface(Relay(), "NativePluginHost")
        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                handler.post { owner.rendererGone(this@NativePluginRuntime) }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path.orEmpty()
                if (request.method != "GET" || uri.scheme != "https" || uri.host != HOST || !path.startsWith("/plugins/") || path.split('/').any { it == ".." || it == "." }) return denied()
                return runCatching {
                    val mime = when { path.endsWith(".html") -> "text/html"; path.endsWith(".js") -> "application/javascript"; path.endsWith(".css") -> "text/css"; else -> "application/octet-stream" }
                    WebResourceResponse(mime, "utf-8", application.assets.open(path.removePrefix("/")))
                }.getOrElse { denied() }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url == URL && !closed) { ready = true; viewport(); pending.toList().forEach(::send); pending.clear() }
            }
        }
        loadUrl(URL)
    }
    init {
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top) viewport()
        }
        CoreManager.addCallback(this)
        scope.launch { runCatching { owner.core.objectResult("player_snapshot") }.getOrNull()?.let(::player) }
    }

    fun synchronize(resources: JSONObject, records: List<JSONObject>) {
        val command = JSONObject().put("type", "sync").put("resources", resources).put("records", org.json.JSONArray(records))
        if (ready) send(command) else { pending.removeAll { it.optString("type") == "sync" }; pending += command }
    }
    fun update(operation: JSONObject) { val command = JSONObject().put("type", "update").put("operation", operation); if (ready) send(command) else pending.add(command) }
    fun presentation(value: JSONObject) {
        currentPresentation = JSONObject(value.toString())
        val command = JSONObject().put("type", "presentation").put("value", JSONObject(value.toString()).put("artwork", artworkData ?: JSONObject.NULL))
        enqueue(command)
    }
    private fun enqueue(value: JSONObject) {
        if (ready) send(value) else { pending.removeAll { it.optString("type") == value.optString("type") }; pending.add(value) }
    }
    fun retire(pluginId: String? = null) { send(JSONObject().put("type", "retire").put("pluginId", pluginId)) }
    fun resume(pluginId: String? = null) { send(JSONObject().put("type", "resume").put("pluginId", pluginId)) }
    fun event(pluginId: String?, name: String, payload: JSONObject) { send(JSONObject().put("type", "event").put("pluginId", pluginId).put("event", name).put("payload", payload)) }
    fun scene(pluginId: String?, sceneId: String?) { enqueue(JSONObject().put("type", "scene").put("pluginId", pluginId).put("sceneId", sceneId)) }
    fun key(value: JSONObject) { send(JSONObject().put("type", "key").put("event", value)) }
    fun shortcut(pluginId: String, id: String, accelerator: String?) { send(JSONObject().put("type", "shortcut-binding").put("pluginId", pluginId).put("id", id).put("accelerator", accelerator ?: JSONObject.NULL)) }
    private fun viewport() {
        if (view.width <= 0 || view.height <= 0) return
        val density = view.resources.displayMetrics.density
        send(JSONObject().put("type", "viewport").put("width", view.width / density).put("height", view.height / density))
    }
    private fun send(value: JSONObject) { if (!closed && ready) view.evaluateJavascript("window.yaqmcNativeReceive(${JSONObject.quote(value.toString())})", null) }

    private inner class Relay {
        @JavascriptInterface fun call(raw: String) {
            if (raw.length > 800 * 1024 || closed) return
            handler.post { scope.launch {
                if (closed) return@launch
                val message = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                val id = message.optLong("id", -1)
                val method = message.optString("method")
                val params = message.optJSONObject("params") ?: JSONObject()
                if (method !in METHODS) { respond(id, Result.failure(IllegalArgumentException("plugin-method-denied"))); return@launch }
                if (method == "plugin_bridge" && params.optJSONObject("request")?.optString("method")?.startsWith("files.") == true) {
                    owner.nativeFiles.invoke(PluginFileCall { result -> handler.post { respond(id, result) } }, params)
                } else {
                    val result = runCatching { owner.core.invoke(method, params) }
                    if (method == "plugin_runtime_start") (result.getOrNull() as? String)?.let(runtimeTokens::add)
                    if (method == "plugin_runtime_stop") { runtimeTokens.remove(params.optString("token")); owner.nativeFiles.release(params.optString("token")) }
                    respond(id, result)
                }
            } }
        }
        @JavascriptInterface fun snapshot(raw: String) {
            if (raw.length > 1024 * 1024 || closed) return
            handler.post { if (!closed) runCatching { owner.acceptContributions(JSONObject(raw)) } }
        }
        @JavascriptInterface fun preferences(raw: String) {
            if (raw.length > 64 * 1024 || closed) return
            handler.post { if (!closed) runCatching { owner.shortcutBindings(JSONObject(raw)) } }
        }
    }
    private fun respond(id: Long, result: Result<Any?>) {
        send(JSONObject().put("type", "result").put("id", id).put("ok", result.isSuccess)
            .put("value", result.getOrNull() ?: JSONObject.NULL).put("error", result.exceptionOrNull()?.message ?: JSONObject.NULL))
    }
    override fun onCoreResponse(id: Long, json: String) = Unit
    override fun onCoreEvent(sequence: Long, channel: String, json: String) {
        handler.post {
            if (closed) return@post
            if (channel == "player://snapshot") runCatching { player(JSONObject(json)) }
        }
    }
    private fun player(snapshot: JSONObject) {
        enqueue(JSONObject().put("type", "player").put("snapshot", snapshot))
        val current = snapshot.optJSONObject("current") ?: snapshot.optJSONArray("queue")?.optJSONObject(snapshot.optInt("currentIndex", -1))
        val url = current?.optJSONObject("artwork")?.text("src").orEmpty()
        if (url == artworkUrl) return
        artworkUrl = url
        artworkData = null
        artworkJob?.cancel()
        presentation(currentPresentation)
        if (url.isBlank()) return
        artworkJob = scope.launch {
            val image = runCatching { view.context.imageLoader.execute(ImageRequest.Builder(view.context).data(url).size(720).allowHardware(false).build()) as? SuccessResult }.getOrNull()?.image ?: return@launch
            val encoded = ByteArrayOutputStream().use { output -> image.toBitmap().compress(Bitmap.CompressFormat.JPEG, 85, output); Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP) }
            if (!closed && artworkUrl == url) { artworkData = "data:image/jpeg;base64,$encoded"; presentation(currentPresentation) }
        }
    }
    fun close() {
        if (closed) return
        closed = true
        artworkJob?.cancel()
        runtimeTokens.toList().forEach { token -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.nativeFiles.release(token)
            runCatching { owner.core.invoke("plugin_runtime_stop", JSONObject().put("token", token)) }
        } }
        runtimeTokens.clear()
        CoreManager.removeCallback(this)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.removeJavascriptInterface("NativePluginHost")
        view.stopLoading()
        view.destroy()
    }
    companion object {
        const val HOST = "plugins.yaqmc.invalid"
        const val URL = "https://$HOST/plugins/index.html"
        private val METHODS = setOf("plugin_bridge", "plugin_list", "plugin_active_resources", "plugin_runtime_start", "plugin_runtime_ready", "plugin_runtime_stop", "plugin_mark_failed", "plugin_read_asset", "plugin_update_runtime_ready", "plugin_local_files", "player_toggle", "player_seek", "player_next", "player_previous")
        private fun denied() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

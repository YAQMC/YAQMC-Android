package org.yaqmc.android.nativeui

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.yaqmc.android.plugin.PluginFileCall
import org.yaqmc.android.plugin.PluginFiles

/** Shared SAF bridge for SDK workers and the native-only Provider local-files import. */
internal class NativePluginFilesBridge(application: Application, private val core: NativeCoreApi, private val scope: CoroutineScope) {
    private val handler = Handler(Looper.getMainLooper())
    private var picker: PluginFileCall? = null
    private var closed = false
    var openPicker: ((Intent) -> Unit)? = null
    private val files = PluginFiles(application,
        authorize = { request, reply -> scope.launch {
            val provider = request.has("requestId")
            val params = if (provider) JSONObject().put("operation", "authorize").put("request", request) else JSONObject().put("request", request)
            reply(runCatching { core.objectResult(if (provider) "plugin_local_files" else "plugin_bridge", params, host = provider).optBoolean("ok") }.getOrDefault(false))
        } },
        openPicker = { call, intent -> handler.post {
            if (closed || picker != null || openPicker == null) call.reject("files.picker-unavailable")
            else {
                picker = call
                runCatching { openPicker?.invoke(intent) }.onFailure { picker = null; pickerFailed(call) }
            }
        } },
    )
    private fun pickerFailed(call: PluginFileCall) { files.pickerFailed(call) }
    fun invoke(call: PluginFileCall, params: JSONObject) = files.invoke(call, params)
    fun picked(result: ActivityResult) { val call = picker; picker = null; files.picked(call, result) }
    fun release(token: String) = files.release(token)
    fun acceptProviderEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        event.text("release").takeIf(String::isNotBlank)?.let { files.release(it); return }
        val requestId = event.text("requestId").takeIf(String::isNotBlank) ?: return
        val request = event.optJSONObject("request") ?: return
        fun complete(result: Result<Any?>) { scope.launch {
            runCatching { core.invoke("plugin_local_files", JSONObject().put("operation", "complete").put("requestId", requestId)
                .put("value", result.getOrNull() ?: JSONObject.NULL).put("error", if (result.isFailure) "files.native-operation-failed" else JSONObject.NULL), host = true) }
        } }
        if (request.text("method") !in setOf("files.pickDirectory", "files.stat", "files.list", "files.readFile") || request.optJSONObject("payload")?.optBoolean("writable", false) == true) {
            complete(Result.failure(IllegalArgumentException("files.permission-denied"))); return
        }
        files.invoke(PluginFileCall(::complete), event)
    }
    fun close() { closed = true; picker = null; openPicker = null; files.close() }
}

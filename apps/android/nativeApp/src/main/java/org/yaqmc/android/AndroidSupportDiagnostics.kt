package org.yaqmc.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal class AndroidStartupDiagnostics {
    private data class HomeArtworkTiming(val requestedMs: Long, val displayedMs: Long?, val source: String?)

    private val startedAt = SystemClock.elapsedRealtime()
    private var coreStartedAt = startedAt
    private var coreStartupMs: Long? = null
    private var firstPageMs: Long? = null
    @Volatile private var firstHomeArtwork: HomeArtworkTiming? = null
    var coreFailure: String? = null
        private set

    fun installCrashRecorder(context: Context) {
        val downstream = Thread.getDefaultUncaughtExceptionHandler() ?: return
        val file = crashFile(context)
        val writing = AtomicBoolean()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (writing.compareAndSet(false, true)) runCatching {
                    val report = JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("details", exceptionLocation(error))
                    val stream = file.startWrite()
                    try {
                        stream.write(report.toString().toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (failure: Exception) {
                        file.failWrite(stream)
                    }
                }
            } finally {
                downstream.uncaughtException(thread, error)
            }
        }
    }

    fun startCore() { coreStartedAt = SystemClock.elapsedRealtime() }

    fun finishCore(error: Throwable?) {
        coreStartupMs = SystemClock.elapsedRealtime() - coreStartedAt
        coreFailure = error?.let(::exceptionLocation)
    }

    fun firstPageDrawn() {
        if (firstPageMs == null) firstPageMs = SystemClock.elapsedRealtime() - startedAt
    }

    val homeArtworkDrawn: Boolean get() = firstHomeArtwork?.displayedMs != null

    /** Called on the UI thread for an artwork request observed inside the home viewport. */
    fun observeHomeArtwork(requested: Long, displayed: Long? = null, source: String? = null) {
        if (homeArtworkDrawn || (displayed == null && firstHomeArtwork != null)) return
        if (requested < startedAt || (displayed != null && displayed < requested)) return
        firstHomeArtwork = HomeArtworkTiming(requested - startedAt, displayed?.minus(startedAt), source)
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("coreStartupMs", coreStartupMs)
        .put("firstPageMs", firstPageMs)
        .put("coreFailure", coreFailure)
        .put("firstVisibleHomeArtwork", firstHomeArtwork?.let { timing ->
            JSONObject().put("requestStartedMs", timing.requestedMs)
                .put("displayedMs", timing.displayedMs)
                .put("dataSource", timing.source)
        })
}

/** Device and exit metadata for a user-initiated preview or export. */
internal fun androidSupportDetails(context: Context): JSONObject {
    val application = context.applicationContext as? YaqmcApplication
    val crash = runCatching { JSONObject(crashFile(context).readFully().toString(Charsets.UTF_8)) }.getOrNull()
    return JSONObject()
        .put("platform", "android")
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("androidRelease", Build.VERSION.RELEASE)
        .put("androidApi", Build.VERSION.SDK_INT)
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull())
        .put("startup", application?.startupDiagnostics?.snapshot())
        .put("lastManagedCrash", crash)
        .put("lastAbnormalExit", if (Build.VERSION.SDK_INT >= 30) lastAbnormalExit(context) else null)
}

internal fun androidSupportText(details: JSONObject): String = buildString {
    appendLine("- Device: ${details.getString("manufacturer")} ${details.getString("model")}")
    appendLine("- Android: ${details.getString("androidRelease")} (API ${details.getInt("androidApi")}) · ${details.optString("abi", "-")}")
    details.optJSONObject("startup")?.let { startup ->
        if (startup.has("coreStartupMs")) appendLine("- Core startup: ${startup.getLong("coreStartupMs")} ms")
        if (startup.has("firstPageMs")) appendLine("- First page: ${startup.getLong("firstPageMs")} ms")
        if (startup.has("coreFailure")) appendLine("- Core startup failure: ${startup.getString("coreFailure")}")
        startup.optJSONObject("firstVisibleHomeArtwork")?.let { timing ->
            val requested = "request at ${timing.getLong("requestStartedMs")} ms"
            val drawn = if (timing.has("displayedMs")) "draw at ${timing.getLong("displayedMs")} ms"
                else "draw not observed"
            val source = timing.optString("dataSource").takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            appendLine("- First visible home artwork (since startup): $requested · $drawn$source")
        }
    }
    details.optJSONObject("lastAbnormalExit")?.let { exit ->
        appendLine("- Last abnormal exit: ${exit.getString("reason")} · ${Instant.ofEpochMilli(exit.getLong("time"))} · status ${exit.getInt("status")}")
    }
    details.optJSONObject("lastManagedCrash")?.let { crash ->
        val time = crash.optLong("time")
        val location = crash.optString("details")
        if (time > 0 && location.isNotBlank()) appendLine("- Last Java crash: ${Instant.ofEpochMilli(time)} · $location")
    }
}.trimEnd()

private fun crashFile(context: Context) = AtomicFile(File(context.filesDir, "last-managed-crash.json"))

// Exception messages and raw system traces can contain app data. Capture a bounded code location.
private fun exceptionLocation(error: Throwable): String {
    val frame = error.stackTrace.firstOrNull { it.className.startsWith("org.yaqmc.android.") }
        ?: error.stackTrace.firstOrNull()
    return buildString {
        append(error.javaClass.name)
        frame?.let { append(" at ${it.className}.${it.methodName}:${it.lineNumber}") }
    }.take(220)
}

@RequiresApi(30)
private fun lastAbnormalExit(context: Context): JSONObject? = runCatching {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return null
    for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, 12)) {
        if (exit.processName != context.packageName) continue
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "java-crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
            else -> continue
        }
        return JSONObject().put("reason", reason).put("time", exit.timestamp).put("status", exit.status)
    }
    null
}.getOrNull()

package org.yaqmc.android.nativeui

import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.yaqmc.android.BuildConfig
import org.yaqmc.android.core.CoreManager

/** Original upstream packages exercise the real Android JNI / Wasmtime / provider boundary. */
@UnstableApi
class NativePluginProviderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun invoke(method: String, params: JSONObject = JSONObject(), host: Boolean = false): Any? = withCoreDeadline {
        suspendCancellableCoroutine { continuation ->
            val id = CoreManager.invoke(method, params.toString(), if (host) "host" else "main", onResponse = { response ->
                if (continuation.isActive) continuation.resumeWith(runCatching { decodeEnvelope(response) }.recoverCatching { throw AssertionError("$method: ${JSONObject(response).optJSONObject("error")}", it) })
            })
            continuation.invokeOnCancellation { CoreManager.forgetResponse(id) }
        }
    }
    private suspend fun objectResult(method: String, params: JSONObject = JSONObject(), host: Boolean = false) = invoke(method, params, host) as JSONObject
    private fun provider(id: String) = JSONObject().put("providerId", id).put("profileId", "default")

    @Test fun originalComponentsProvideCatalogPlaybackLyricsAndIsolatedFixtureAccount() = runBlocking {
        val ownsCore = !CoreManager.isReady()
        if (ownsCore) CoreManager.initialize(context, JSONObject().put("platform", "android").put("version", BuildConfig.VERSION_NAME).put("buildCommit", BuildConfig.YAQMC_BUILD_COMMIT).put("releaseChannel", "android").toString())
        val ids = listOf("dev.yaqmc.example.catalog", "dev.yaqmc.example.platform")
        val files = mutableListOf<File>()
        val qqBefore = withTimeout(10000) {
            var snapshot = objectResult("provider_account_snapshot", provider("qqmusic"))
            while (snapshot.text("state") == "restoring-session") { delay(50); snapshot = objectResult("provider_account_snapshot", provider("qqmusic")) }
            snapshot
        }
        val queueBefore = objectResult("player_snapshot").optJSONArray("queue") ?: JSONArray()
        try {
            for ((name, id) in listOf("catalog" to ids[0], "platform" to ids[1])) {
                val file = File(context.cacheDir, "test-$name.yaqmc-plugin")
                instrumentation.context.assets.open("plugins/$name.yaqmc-plugin").use { input -> file.outputStream().use(input::copyTo) }
                files += file
                val inspection = objectResult("plugin_inspect_path", JSONObject().put("path", file.absolutePath))
                assertEquals(id, inspection.getJSONObject("manifest").getString("id"))
                val existing = (invoke("plugin_list") as JSONArray).objects().any { it.text("id") == id }
                if (existing) invoke("plugin_uninstall", JSONObject().put("request", JSONObject().put("id", id).put("removeData", true)))
                invoke("plugin_install", JSONObject().put("request", JSONObject().put("path", file.absolutePath).put("enable", false).put("grant", inspection.getJSONArray("permissions"))))
                val record = (invoke("plugin_list") as JSONArray).objects().single { it.text("id") == id }
                assertFalse(record.getBoolean("enabled"))
                invoke("plugin_set_enabled", JSONObject().put("request", JSONObject().put("id", id).put("enabled", true).put("grant", inspection.getJSONArray("permissions"))))
                assertTrue((invoke("provider_list") as JSONArray).objects().single { it.text("providerId") == id }.getBoolean("available"))
                val search = objectResult("provider_search", provider(id).put("query", "fixture").put("kind", "song").put("page", 1).put("limit", 20))
                val song = search.getJSONArray("items").getJSONObject(0)
                assertEquals(id, song.getJSONObject("provider").getString("providerId"))
                assertEquals("default", song.getJSONObject("provider").getString("profileId"))
            }
            val platform = ids[1]
            val song = objectResult("provider_song", provider(platform).put("id", "platform-song-1"))
            val lyrics = objectResult("provider_lyrics", provider(platform).put("id", "platform-song-1"))
            assertTrue(lyrics.getJSONArray("lines").length() > 0)
            invoke("player_play_tracks", JSONObject().put("request", JSONObject().put("tracks", JSONArray().put(song)).put("shuffle", false)))
            withTimeout(10000) {
                while (true) {
                    val snapshot = objectResult("player_snapshot")
                    assertTrue("plugin playback error: ${snapshot.optJSONObject("playbackError")}", snapshot.isNull("playbackError"))
                    if (snapshot.optBoolean("isPlaying") || snapshot.optLong("positionMs") > 0) break
                    delay(40)
                }
            }
            invoke("player_pause")
            val prepared = objectResult("provider_auth_oauth_prepare", provider(platform).put("methodId", "example"), host = true)
            val callback = android.net.Uri.Builder().scheme("https").authority("accounts.example.com").path("/callback")
                .appendQueryParameter("state", android.net.Uri.parse(prepared.getString("url")).getQueryParameter("state")).appendQueryParameter("code", "local-fixture").build().toString()
            val account = objectResult("provider_auth_oauth_complete", provider(platform).put("attemptId", prepared.getString("attemptId")).put("callbackUrl", callback), host = true)
            assertEquals("authenticated", account.getString("state"))
            assertEquals("Component Listener", account.getJSONObject("profile").getString("nickname"))
            assertEquals(qqBefore.getString("state"), objectResult("provider_account_snapshot", provider("qqmusic")).getString("state"))
            invoke("provider_sign_out", provider(platform))
            invoke("plugin_set_enabled", JSONObject().put("request", JSONObject().put("id", platform).put("enabled", false).put("grant", JSONArray())))
            val disabled = objectResult("player_snapshot").getJSONArray("queue").getJSONObject(0)
            assertEquals(platform, disabled.getJSONObject("provider").getString("providerId"))
            assertFalse((invoke("provider_list") as JSONArray).objects().single { it.text("providerId") == platform }.getBoolean("available"))
            val record = (invoke("plugin_list") as JSONArray).objects().single { it.text("id") == platform }
            invoke("plugin_set_enabled", JSONObject().put("request", JSONObject().put("id", platform).put("enabled", true).put("grant", record.getJSONArray("permissions"))))
            assertEquals(platform, objectResult("provider_song", provider(platform).put("id", "platform-song-1")).getJSONObject("provider").getString("providerId"))
        } finally {
            runCatching { invoke("player_stop") }
            runCatching { invoke("player_hydrate_queue", JSONObject().put("tracks", queueBefore)) }
            for (id in ids) runCatching { invoke("plugin_uninstall", JSONObject().put("request", JSONObject().put("id", id).put("removeData", true))) }
            files.forEach(File::delete)
            if (ownsCore) CoreManager.shutdown()
        }
    }
}

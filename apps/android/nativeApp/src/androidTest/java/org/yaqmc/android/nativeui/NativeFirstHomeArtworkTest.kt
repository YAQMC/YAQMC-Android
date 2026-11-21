package org.yaqmc.android.nativeui

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.yaqmc.android.AndroidStartupDiagnostics
import org.yaqmc.android.androidSupportText

@UnstableApi
class NativeFirstHomeArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pairsTheFirstRealDrawWithItsOwnRequest() {
        val diagnostic = AndroidStartupDiagnostics()
        val viewport = HomeArtworkViewport(diagnostic) { true }
        val slowResponse = CompletableDeferred<Unit>()
        val slow = request { slowResponse.await(); image(android.graphics.Color.RED) }
        val fast = request { image(android.graphics.Color.GREEN) }
        val showFast = mutableStateOf(false)
        compose.setContent {
            Row(Modifier.size(160.dp, 80.dp).onGloballyPositioned { viewport.coordinates = it }) {
                Artwork(slow, Modifier.size(76.dp), viewport)
                if (showFast.value) Artwork(fast, Modifier.size(76.dp).testTag("visible-fast-cover"), viewport)
            }
        }

        compose.waitUntil(5000) { timing(diagnostic) != null }
        val pending = checkNotNull(timing(diagnostic))
        assertFalse(pending.has("displayedMs"))
        assertFalse(pending.has("dataSource"))
        SystemClock.sleep(12)
        compose.runOnIdle { showFast.value = true }
        compose.waitUntil(5000) { diagnostic.homeArtworkDrawn }
        val completed = checkNotNull(timing(diagnostic))
        assertTrue(completed.getLong("requestStartedMs") > pending.getLong("requestStartedMs"))
        assertTrue(completed.getLong("displayedMs") >= completed.getLong("requestStartedMs"))
        assertEquals("network", completed.getString("dataSource"))
        val pixel = compose.onNodeWithTag("visible-fast-cover").captureToImage().toPixelMap()
        assertEquals(Color.Green, pixel[pixel.width / 2, pixel.height / 2])
        slowResponse.complete(Unit)
        compose.waitForIdle()
        assertEquals(completed.toString(), timing(diagnostic)?.toString())
    }

    @Test fun aVisibleFailedRequestAndANullArtworkStayUndrawn() {
        val diagnostic = AndroidStartupDiagnostics()
        val viewport = HomeArtworkViewport(diagnostic) { true }
        val response = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val source = request(onError = { failed.complete(Unit) }) {
            response.await()
            throw IOException("synthetic image failure")
        }
        compose.setContent {
            Row(Modifier.size(160.dp, 80.dp).onGloballyPositioned { viewport.coordinates = it }) {
                Artwork(null, Modifier.size(76.dp), viewport)
                Artwork(source, Modifier.size(76.dp), viewport)
            }
        }
        compose.waitUntil(5000) { timing(diagnostic) != null }
        val requested = checkNotNull(timing(diagnostic)).getLong("requestStartedMs")
        response.complete(Unit)
        compose.waitUntil(5000) { failed.isCompleted }
        compose.waitForIdle()
        assertEquals(requested, checkNotNull(timing(diagnostic)).getLong("requestStartedMs"))
        assertFalse(diagnostic.homeArtworkDrawn)
    }

    @Test fun ignoresOffscreenAndOutgoingDrawsThenMeasuresAVisibleCacheHit() {
        val diagnostic = AndroidStartupDiagnostics()
        val visibleHome = mutableStateOf(true)
        val currentViewport = mutableStateOf(HomeArtworkViewport(diagnostic) { visibleHome.value })
        val fetchedOffscreen = CompletableDeferred<Unit>()
        val cached = request(onSuccess = { fetchedOffscreen.complete(Unit) }) { image(android.graphics.Color.RED) }
        val outgoingResponse = CompletableDeferred<Unit>()
        val outgoingFinished = CompletableDeferred<Unit>()
        val outgoing = request(onSuccess = { outgoingFinished.complete(Unit) }) {
            outgoingResponse.await()
            image(android.graphics.Color.GREEN)
        }
        val frame = mutableStateOf(0)
        compose.setContent {
            val viewport = currentViewport.value
            Box(Modifier.size(100.dp).clipToBounds().onGloballyPositioned { viewport.coordinates = it }) {
                when (frame.value) {
                    0 -> Artwork(cached, Modifier.size(76.dp).offset(y = 120.dp), viewport)
                    1 -> Artwork(outgoing, Modifier.size(76.dp), viewport)
                    2 -> Artwork(cached, Modifier.size(76.dp), viewport)
                }
            }
        }

        compose.waitUntil(5000) { fetchedOffscreen.isCompleted }
        compose.waitForIdle()
        assertNull(timing(diagnostic))
        compose.runOnIdle { frame.value = 1 }
        compose.waitUntil(5000) { timing(diagnostic) != null }
        val pending = checkNotNull(timing(diagnostic)).getLong("requestStartedMs")
        compose.runOnIdle { visibleHome.value = false }
        outgoingResponse.complete(Unit)
        compose.waitUntil(5000) { outgoingFinished.isCompleted }
        compose.waitForIdle()
        assertFalse(diagnostic.homeArtworkDrawn)

        compose.runOnIdle {
            currentViewport.value = HomeArtworkViewport(diagnostic) { visibleHome.value }
            visibleHome.value = true
            frame.value = 2
        }
        compose.waitUntil(5000) { diagnostic.homeArtworkDrawn }
        val completed = checkNotNull(timing(diagnostic))
        assertEquals("memory-cache", completed.getString("dataSource"))
        assertTrue(completed.getLong("requestStartedMs") > pending)
    }

    @Test fun theHomeCatalogAddsOnlyTimingAndSourceToTheSupportSnapshot() {
        val diagnostic = AndroidStartupDiagnostics()
        val cover = ByteArrayOutputStream().apply {
            coloredBitmap(android.graphics.Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, this)
        }
        val artwork = "data:image/png;base64," + Base64.encodeToString(cover.toByteArray(), Base64.NO_WRAP)
        val feed = JSONObject().put("recommendedSonglists", JSONArray().put(JSONObject()
            .put("id", "fixture-playlist").put("title", "Fixture playlist")
            .put("artwork", JSONObject().put("src", artwork))))
        lateinit var model: NativeViewModel
        compose.runOnUiThread {
            model = NativeViewModel(compose.activity.application, object : NativeCoreApi {
                override suspend fun invoke(method: String, params: JSONObject, host: Boolean): Any =
                    if (method == "provider_home") feed else JSONObject()
            })
        }
        try {
            compose.setContent { NativeApp(model, {}, { _, _ -> }, {}, startupDiagnostics = diagnostic) }
            compose.waitUntil(5000) { diagnostic.homeArtworkDrawn }
            val completed = checkNotNull(timing(diagnostic))
            assertEquals(setOf("requestStartedMs", "displayedMs", "dataSource"), completed.keys().asSequence().toSet())
            val support = JSONObject().put("manufacturer", "Test").put("model", "Emulator")
                .put("androidRelease", "15").put("androidApi", 35).put("startup", diagnostic.snapshot())
            val text = androidSupportText(support)
            assertTrue(text.contains("First visible home artwork"))
            assertTrue(text.contains("draw at ${completed.getLong("displayedMs")} ms"))
            assertFalse(text.contains("Fixture playlist"))
            assertFalse(text.contains("base64"))
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    private fun timing(diagnostic: AndroidStartupDiagnostics) = diagnostic.snapshot().optJSONObject("firstVisibleHomeArtwork")

    private fun request(onSuccess: () -> Unit = {}, onError: () -> Unit = {}, fetch: suspend () -> ImageFetchResult): ImageRequest =
        ImageRequest.Builder(context).data("https://example.invalid/cover/${UUID.randomUUID()}.png")
            .size(96).listener(onSuccess = { _, _ -> onSuccess() }, onError = { _, _ -> onError() })
            .fetcherFactory(Fetcher.Factory<Uri> { _, _, _ -> Fetcher { fetch() } }).build()

    private fun image(color: Int) = ImageFetchResult(coloredBitmap(color).asImage(), false, DataSource.NETWORK)

    private fun coloredBitmap(color: Int) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}

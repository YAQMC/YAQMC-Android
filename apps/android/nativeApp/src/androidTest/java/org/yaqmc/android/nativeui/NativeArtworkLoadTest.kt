package org.yaqmc.android.nativeui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class NativeArtworkLoadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun simultaneousThumbnailsFetchTheSmallUrlOnceAndReuseItsDecodedImage() = runBlocking {
        withTimeout(5000) {
            val small = "https://example.invalid/artwork/shared-small.jpg"
            val artwork = checkNotNull(NativeArtwork.parse(JSONObject("""
                {"src":"https://example.invalid/artwork/shared-default.jpg","variants":[
                    {"src":"$small","width":150,"height":150},
                    {"src":"https://example.invalid/artwork/shared-default.jpg","width":300,"height":300}
                ]}
            """.trimIndent())))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fetches = AtomicInteger()
            val request = ImageRequest.Builder(context).data(artwork).size(144).fetcherFactory(Fetcher.Factory<Uri> { data, _, _ ->
                Fetcher {
                    check(data.toString() == small)
                    fetches.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    result()
                }
            }).build()
            val first = async { context.imageLoader.execute(request) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { context.imageLoader.execute(request) }
            release.complete(Unit)

            assertEquals(DataSource.NETWORK, (first.await() as SuccessResult).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, (second.await() as SuccessResult).dataSource)
            assertEquals(1, fetches.get())
        }
    }

    @Test fun cancelledAndFailedSingleSizeRequestsCanBeRetried() = runBlocking {
        withTimeout(5000) {
            val source = "https://example.invalid/artwork/retry.jpg"
            val artwork = checkNotNull(NativeArtwork.parse(JSONObject().put("src", source)))
            val entered = CompletableDeferred<Unit>()
            val fetches = AtomicInteger()
            val request = ImageRequest.Builder(context).data(artwork).size(144).fetcherFactory(Fetcher.Factory<Uri> { data, _, _ ->
                Fetcher {
                    check(data.toString() == source)
                    when (fetches.incrementAndGet()) {
                        1 -> { entered.complete(Unit); awaitCancellation() }
                        2 -> throw IOException("synthetic image failure")
                        else -> result()
                    }
                }
            }).build()
            val first = async { context.imageLoader.execute(request) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { context.imageLoader.execute(request) }
            first.cancelAndJoin()

            assertTrue(second.await() is ErrorResult)
            assertEquals(DataSource.NETWORK, (context.imageLoader.execute(request) as SuccessResult).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, (context.imageLoader.execute(request) as SuccessResult).dataSource)
            assertEquals(3, fetches.get())
        }
    }

    private fun result() = ImageFetchResult(
        Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888).asImage(),
        isSampled = false,
        dataSource = DataSource.NETWORK,
    )
}

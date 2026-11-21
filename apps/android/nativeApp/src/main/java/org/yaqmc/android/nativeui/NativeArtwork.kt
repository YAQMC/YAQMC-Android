package org.yaqmc.android.nativeui

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.pxOrElse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.yaqmc.android.AndroidStartupDiagnostics

@ConsistentCopyVisibility
internal data class NativeArtwork private constructor(
    val source: String,
    private val variants: List<Variant>,
    private val thumbnail: Boolean,
) {
    private data class Variant(val source: String, val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
    }

    fun sourceFor(width: Int, height: Int): String {
        if (width <= 0 && height <= 0) return source
        // A 150 px cover fits a 168 px thumbnail with at most 12.5% upscaling.
        val minimumWidth = if (thumbnail) (width * 8L + 8) / 9 else width.toLong()
        val minimumHeight = if (thumbnail) (height * 8L + 8) / 9 else height.toLong()
        val original = variants.firstOrNull { it.source == source }
        val suitable = variants.firstOrNull { it.width >= minimumWidth && it.height >= minimumHeight }
        // Keep the existing cover for large tiles to avoid increasing first-screen downloads.
        return suitable?.takeIf { original == null || it.pixels <= original.pixels }?.source ?: source
    }

    companion object {
        fun parse(value: JSONObject?, fallback: String = "", thumbnail: Boolean = false): NativeArtwork? {
            val variants = value?.optJSONArray("variants").objects().mapNotNull {
                val source = it.text("src").trim()
                val width = it.optInt("width")
                val height = it.optInt("height")
                if (source.isEmpty() || width <= 0 || height <= 0) null else Variant(source, width, height)
            }.sortedBy { it.pixels }
            val source = value?.text("src").orEmpty().trim().ifBlank { fallback.trim() }
                .ifBlank { variants.lastOrNull()?.source.orEmpty() }
            return if (source.isEmpty()) null else NativeArtwork(source, variants, thumbnail)
        }
    }
}

internal fun newNativeImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .components { add(NativeArtworkInterceptor()) }
    .build()

/** Only artwork drawn on the current home surface may contribute a completed timing. */
internal class HomeArtworkViewport(
    private val diagnostics: AndroidStartupDiagnostics,
    private val isVisibleHome: () -> Boolean,
) {
    var coordinates: LayoutCoordinates? = null
    val complete: Boolean get() = diagnostics.homeArtworkDrawn

    fun observe(image: LayoutCoordinates?, requestedAt: Long, dataSource: DataSource?) {
        if (complete || !isVisibleHome()) return
        val viewport = coordinates?.takeIf { it.isAttached } ?: return
        val cover = image?.takeIf { it.isAttached } ?: return
        val bounds = cover.boundsInWindow()
        val viewportBounds = viewport.boundsInWindow()
        if (bounds.isEmpty || viewportBounds.isEmpty || !viewportBounds.overlaps(bounds)) return
        if (dataSource == null) diagnostics.observeHomeArtwork(requestedAt)
        else diagnostics.observeHomeArtwork(requestedAt, SystemClock.elapsedRealtime(), dataSource.name.lowercase().replace('_', '-'))
    }
}

internal class HomeArtworkDraw(private val viewport: HomeArtworkViewport) {
    private var coordinates: LayoutCoordinates? = null
    private var requestedAt: Long? = null
    private var dataSource: DataSource? = null

    fun loading() {
        if (viewport.complete) return
        val now = SystemClock.elapsedRealtime()
        requestedAt = now
        dataSource = null
        // A null loading painter may leave the existing display list intact.
        viewport.observe(coordinates, now, null)
    }

    fun positioned(value: LayoutCoordinates) {
        coordinates = value
        requestedAt?.let { viewport.observe(value, it, null) }
    }

    fun success(source: DataSource) { dataSource = source }

    fun failed() {
        requestedAt = null
        dataSource = null
    }

    fun drawn() { requestedAt?.let { viewport.observe(coordinates, it, dataSource) } }
}

private class NativeArtworkInterceptor : Interceptor {
    private class Pending {
        val mutex = Mutex()
        var users = 0
    }

    private val pending = mutableMapOf<String, Pending>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val artwork = chain.request.data as? NativeArtwork ?: return chain.proceed()
        val source = artwork.sourceFor(chain.size.width.pxOrElse { 0 }, chain.size.height.pxOrElse { 0 })
        val result = load(chain, source)
        return if (result is ErrorResult && source != artwork.source) load(chain, artwork.source) else result
    }

    private suspend fun load(chain: Interceptor.Chain, source: String): ImageResult {
        val request = chain.request.newBuilder().data(source).build()
        // A following request checks Coil's caches after the preceding request completes.
        val entry = synchronized(pending) { pending.getOrPut(source, ::Pending).also { it.users++ } }
        try {
            return entry.mutex.withLock { chain.withRequest(request).proceed() }
        } finally {
            synchronized(pending) {
                entry.users--
                if (entry.users == 0) pending.remove(source)
            }
        }
    }
}

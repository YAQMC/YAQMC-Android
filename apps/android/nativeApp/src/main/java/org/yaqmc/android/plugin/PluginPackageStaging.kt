package org.yaqmc.android.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Copies a picked `.yaqmc-plugin` document into app-private staging before Core sees it.
 * The renderer only ever receives a path inside [directory]; content URIs, display names and
 * declared lengths are untrusted, so the copy is bounded while streaming.
 */
class PluginPackageStaging(private val directory: File, private val maxBytes: Long = MAX_PACKAGE_BYTES) {
    fun stage(open: () -> InputStream?): File {
        check(directory.isDirectory || directory.mkdirs()) { "plugin import directory is unavailable" }
        clear()
        val target = File(directory, "selected-${UUID.randomUUID()}.yaqmc-plugin")
        try {
            val input = open() ?: error("selected package stream is unavailable")
            input.use { source ->
                FileOutputStream(target).use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maxBytes) { "selected package exceeds ${maxBytes / (1024 * 1024)} MiB" }
                        destination.write(buffer, 0, read)
                    }
                    require(total > 0) { "selected package is empty" }
                    destination.fd.sync()
                }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** Removes earlier selections; Core copies what it installs into its own transaction. */
    fun clear() {
        directory.listFiles()?.forEach { candidate ->
            if (candidate.isFile && candidate.name.startsWith("selected-")) candidate.delete()
        }
    }

    /** Update preparation is asynchronous; a new picker selection must not erase its input. */
    fun handoff(selected: File): File {
        require(selected.canonicalFile.parentFile == directory.canonicalFile && selected.name.startsWith("selected-"))
        val pending = File(directory, "pending-${UUID.randomUUID()}.yaqmc-plugin")
        check(selected.renameTo(pending)) { "plugin update input could not be retained" }
        return pending
    }

    fun release(path: String) {
        val candidate = File(path)
        if (candidate.canonicalFile.parentFile == directory.canonicalFile && candidate.name.startsWith("pending-")) candidate.delete()
    }

    companion object {
        /** Matches Core `COMPONENT_MAX_COMPRESSED_BYTES`; Core still enforces its own limits. */
        const val MAX_PACKAGE_BYTES = 32L * 1024L * 1024L
    }
}

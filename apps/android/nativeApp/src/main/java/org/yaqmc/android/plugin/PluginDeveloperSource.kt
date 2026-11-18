package org.yaqmc.android.plugin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

/** SAF trees have no filesystem path. Mirror only package files into an immutable Core input. */
internal class PluginDeveloperSource(private val context: Context) {
    private val root = File(context.filesDir, "plugin-developer")

    fun mirror(tree: Uri): File {
        check(root.isDirectory || root.mkdirs())
        val target = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        var bytes = 0L
        var files = 0
        val seen = mutableSetOf<String>()
        fun visit(id: String, directory: File, depth: Int) {
            require(depth <= 32) { "plugin directory is too deep" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = context.contentResolver.query(children, fields, null, null, null) ?: error("plugin directory is unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0)
                    val name = it.getString(1)
                    if (name in setOf(".git", "node_modules", "target")) continue
                    require(name.isNotBlank() && name != "." && name != ".." && name.none { char -> char == '/' || char == '\\' || char.code < 32 }) { "invalid plugin path" }
                    val child = File(directory, name)
                    require(child.canonicalPath.startsWith(target.canonicalPath + File.separator))
                    require(seen.add(child.relativeTo(target).path.lowercase())) { "duplicate plugin path" }
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        check(child.mkdir()); visit(childId, child, depth + 1)
                    } else {
                        require(++files <= 512) { "too many plugin files" }
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                        val input = context.contentResolver.openInputStream(uri) ?: error("plugin file is unavailable")
                        input.use { source -> child.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var size = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                size += read; bytes += read
                                require(size <= 32L * 1024 * 1024 && bytes <= 96L * 1024 * 1024) { "plugin exceeds package limits" }
                                output.write(buffer, 0, read)
                            }
                        } }
                    }
                }
            }
        }
        try { visit(DocumentsContract.getTreeDocumentId(tree), target, 0); return target }
        catch (error: Throwable) { target.deleteRecursively(); throw error }
    }

    fun release(path: String) {
        val directory = File(path).canonicalFile
        if (directory.parentFile == root.canonicalFile) directory.deleteRecursively()
    }
}

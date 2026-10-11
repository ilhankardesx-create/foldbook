package com.foldbook.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.ArrayDeque

/**
 * Discovers PDF/EPUB books from shared storage.
 *
 * Android 11+ uses MANAGE_EXTERNAL_STORAGE after the user explicitly grants it once,
 * so FoldBook can walk normal shared-storage folders directly. Android 10 and below
 * keep the previous MediaStore best-effort fallback.
 */
object DeviceBookScanner {

    private data class Candidate(
        val book: LibraryBook,
        val identity: String
    )

    fun scan(context: Context): List<LibraryBook> {
        val books = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                error("Cihazdaki bütün kitapları taramak için dosya erişim izni gerekli.")
            }
            scanFileSystem(context)
        } else {
            scanMediaStore(context)
        }

        return books
            .distinctBy { it.identity }
            .map { it.book }
            .sortedWith(
                compareByDescending<LibraryBook> { it.modifiedAt }
                    .thenBy { it.title.lowercase() }
            )
    }

    private fun scanFileSystem(context: Context): List<Candidate> {
        val roots = linkedSetOf<File>()
        roots += Environment.getExternalStorageDirectory()

        context.getExternalFilesDirs(null).forEach { appDir ->
            var root: File? = appDir
            repeat(4) { root = root?.parentFile }
            if (root != null && root!!.exists() && root!!.canRead()) {
                roots += root!!
            }
        }

        val result = mutableListOf<Candidate>()
        val visitedDirectories = hashSetOf<String>()
        val stack = ArrayDeque<File>()
        roots.filter { it.exists() && it.canRead() }.forEach { stack.add(it) }

        while (stack.isNotEmpty()) {
            val entry = stack.removeLast()

            if (entry.isDirectory) {
                val canonical = runCatching { entry.canonicalPath }.getOrNull()
                    ?: entry.absolutePath
                if (!visitedDirectories.add(canonical)) continue
                if (shouldSkipDirectory(canonical)) continue

                val children = runCatching { entry.listFiles() }.getOrNull() ?: continue
                children.forEach { child -> stack.add(child) }
                continue
            }

            if (!entry.isFile || !entry.canRead()) continue
            val format = detectFormat(entry.name) ?: continue
            val canonicalPath = runCatching { entry.canonicalPath }.getOrNull()
                ?: entry.absolutePath
            val uri = Uri.fromFile(entry)
            val title = entry.name
                .substringBeforeLast('.', entry.name)
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "İsimsiz Kitap" }

            result += Candidate(
                book = LibraryBook(
                    title = title,
                    uri = uri.toString(),
                    format = format,
                    modifiedAt = entry.lastModified(),
                    isFavorite = LibraryStore.isFavorite(context, uri.toString())
                ),
                identity = canonicalPath
            )
        }

        return result
    }

    private fun shouldSkipDirectory(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return normalized.contains("/android/data/") ||
            normalized.endsWith("/android/data") ||
            normalized.contains("/android/obb/") ||
            normalized.endsWith("/android/obb")
    }

    private fun detectFormat(name: String): BookFormat? {
        return when {
            name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
            name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
            else -> null
        }
    }

    private fun scanMediaStore(context: Context): List<Candidate> {
        val output = mutableListOf<Candidate>()
        val collections = linkedSetOf<Uri>()
        collections += MediaStore.Files.getContentUri("external")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections += MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        collections.forEach { collection ->
            queryCollection(context, collection, output)
        }
        return output
    }

    private fun queryCollection(
        context: Context,
        collection: Uri,
        output: MutableList<Candidate>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ?)"
        val args = arrayOf("%.pdf", "%.epub", "application/pdf", "application/epub+zip")

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext()) {
                    if (idIndex < 0) continue
                    val id = cursor.getLong(idIndex)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                    val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) * 1000L else 0L
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    val format = when {
                        mime.equals("application/pdf", true) || name.endsWith(".pdf", true) -> BookFormat.PDF
                        mime.equals("application/epub+zip", true) || name.endsWith(".epub", true) -> BookFormat.EPUB
                        else -> null
                    } ?: continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    val title = name.substringBeforeLast('.', name)
                        .replace('_', ' ')
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { "İsimsiz Kitap" }

                    output += Candidate(
                        book = LibraryBook(
                            title = title,
                            uri = uri.toString(),
                            format = format,
                            modifiedAt = modified,
                            isFavorite = LibraryStore.isFavorite(context, uri.toString())
                        ),
                        identity = "${name.lowercase()}|$size|$modified"
                    )
                }
            }
        }
    }
}

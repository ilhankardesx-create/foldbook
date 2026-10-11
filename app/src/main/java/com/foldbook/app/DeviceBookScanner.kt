package com.foldbook.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Best-effort discovery of PDF/EPUB files that Android exposes through MediaStore.
 *
 * This intentionally does not request MANAGE_EXTERNAL_STORAGE. The manually selected
 * SAF folder remains the authoritative source, while this scanner adds books Android
 * already exposes to FoldBook (Downloads/indexed shared storage, including removable
 * volumes when available).
 */
object DeviceBookScanner {

    private data class Candidate(
        val book: LibraryBook,
        val size: Long
    )

    fun scan(context: Context): List<LibraryBook> {
        val candidates = mutableListOf<Candidate>()
        val collections = linkedSetOf<Uri>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.getExternalVolumeNames(context).forEach { volumeName ->
                collections += MediaStore.Files.getContentUri(volumeName)
            }
        } else {
            collections += MediaStore.Files.getContentUri("external")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections += MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        collections.forEach { collection ->
            queryCollection(context, collection, candidates)
        }

        return candidates
            .distinctBy { candidate ->
                val book = candidate.book
                "${book.title.lowercase()}|${candidate.size}|${book.modifiedAt}"
            }
            .map { it.book }
            .sortedWith(
                compareByDescending<LibraryBook> { it.modifiedAt }
                    .thenBy { it.title.lowercase() }
            )
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

        val selection = buildString {
            append("(")
            append(MediaStore.MediaColumns.DISPLAY_NAME)
            append(" LIKE ? OR ")
            append(MediaStore.MediaColumns.DISPLAY_NAME)
            append(" LIKE ? OR ")
            append(MediaStore.MediaColumns.MIME_TYPE)
            append(" = ? OR ")
            append(MediaStore.MediaColumns.MIME_TYPE)
            append(" = ?)")
        }
        val selectionArgs = arrayOf(
            "%.pdf",
            "%.epub",
            "application/pdf",
            "application/epub+zip"
        )

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
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
                    val modifiedSeconds = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L

                    val format = detectFormat(name, mime) ?: continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    val title = name
                        .substringBeforeLast('.', name)
                        .replace('_', ' ')
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { "İsimsiz Kitap" }

                    output += Candidate(
                        book = LibraryBook(
                            title = title,
                            uri = uri.toString(),
                            format = format,
                            modifiedAt = modifiedSeconds * 1000L,
                            isFavorite = LibraryStore.isFavorite(context, uri.toString())
                        ),
                        size = size
                    )
                }
            }
        }
    }

    private fun detectFormat(name: String, mime: String): BookFormat? {
        return when {
            mime.equals("application/pdf", ignoreCase = true) -> BookFormat.PDF
            mime.equals("application/epub+zip", ignoreCase = true) -> BookFormat.EPUB
            name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
            name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
            else -> null
        }
    }
}

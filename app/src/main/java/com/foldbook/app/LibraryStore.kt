package com.foldbook.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

enum class BookFormat { EPUB, PDF }

data class LibraryBook(
    val title: String,
    val uri: String,
    val format: BookFormat
)

object LibraryStore {
    private const val PREFS = "foldbook_library"
    private const val KEY_FOLDER_URI = "book_folder_uri"
    private const val KEY_PROGRESS_PREFIX = "reading_progress_"

    fun saveFolder(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
    }

    fun savedFolder(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
            ?: return null

        return runCatching { Uri.parse(value) }.getOrNull()
    }

    fun saveProgress(
        context: Context,
        bookUri: String,
        pageIndex: Int
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(progressKey(bookUri), pageIndex.coerceAtLeast(0))
            .apply()
    }

    fun readProgress(
        context: Context,
        bookUri: String,
        lastPageIndex: Int
    ): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(progressKey(bookUri), 0)

        return saved.coerceIn(0, lastPageIndex.coerceAtLeast(0))
    }

    private fun progressKey(bookUri: String): String {
        return KEY_PROGRESS_PREFIX + bookUri.hashCode().toString()
    }

    fun scanFolder(context: Context, treeUri: Uri): List<LibraryBook> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Seçilen klasör açılamadı.")

        if (!root.exists() || !root.canRead()) {
            error("Kitap klasörüne erişilemiyor.")
        }

        val result = mutableListOf<LibraryBook>()
        collectBooks(root, result)

        return result
            .distinctBy { it.uri }
            .sortedBy { it.title.lowercase() }
    }

    private fun collectBooks(
        directory: DocumentFile,
        output: MutableList<LibraryBook>
    ) {
        for (file in directory.listFiles()) {
            when {
                file.isDirectory -> collectBooks(file, output)
                file.isFile -> {
                    val displayName = file.name.orEmpty()
                    val format = when {
                        displayName.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
                        displayName.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
                        else -> null
                    } ?: continue

                    val title = displayName
                        .substringBeforeLast(".")
                        .replace('_', ' ')
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { "İsimsiz Kitap" }

                    output += LibraryBook(
                        title = title,
                        uri = file.uri.toString(),
                        format = format
                    )
                }
            }
        }
    }
}

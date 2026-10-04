package com.foldbook.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

enum class BookFormat { EPUB, PDF }
enum class ReaderThemeOption { LIGHT, SEPIA, DARK }
enum class ReaderFontSize { SMALL, MEDIUM, LARGE }

data class LibraryBook(
    val title: String,
    val uri: String,
    val format: BookFormat
)

object LibraryStore {
    private const val PREFS = "foldbook_library"
    private const val KEY_FOLDER_URI = "book_folder_uri"
    private const val KEY_PROGRESS_PREFIX = "reading_progress_"
    private const val KEY_READER_THEME = "reader_theme"
    private const val KEY_LAST_OPENED_URI = "last_opened_uri"
    private const val KEY_READER_FONT_SIZE = "reader_font_size"
    private const val KEY_ACTIVE_BOOK_URI = "active_book_uri"
    private const val KEY_ACTIVE_BOOK_TITLE = "active_book_title"
    private const val KEY_ACTIVE_BOOK_FORMAT = "active_book_format"

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

    fun saveActiveBook(context: Context, book: LibraryBook) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_BOOK_URI, book.uri)
            .putString(KEY_ACTIVE_BOOK_TITLE, book.title)
            .putString(KEY_ACTIVE_BOOK_FORMAT, book.format.name)
            .apply()
    }

    fun clearActiveBook(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_BOOK_URI)
            .remove(KEY_ACTIVE_BOOK_TITLE)
            .remove(KEY_ACTIVE_BOOK_FORMAT)
            .apply()
    }

    fun readActiveBook(context: Context): LibraryBook? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(KEY_ACTIVE_BOOK_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = prefs.getString(KEY_ACTIVE_BOOK_TITLE, "Kitap").orEmpty()
        val format = runCatching {
            BookFormat.valueOf(
                prefs.getString(KEY_ACTIVE_BOOK_FORMAT, BookFormat.EPUB.name).orEmpty()
            )
        }.getOrDefault(BookFormat.EPUB)

        return LibraryBook(
            title = title.ifBlank { "Kitap" },
            uri = uri,
            format = format
        )
    }

    fun saveLastOpened(context: Context, bookUri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_OPENED_URI, bookUri)
            .apply()
    }

    private fun lastOpened(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_OPENED_URI, null)
    }

    fun saveReaderTheme(context: Context, theme: ReaderThemeOption) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_READER_THEME, theme.name)
            .apply()
    }

    fun readReaderTheme(context: Context): ReaderThemeOption {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_READER_THEME, ReaderThemeOption.LIGHT.name)

        return runCatching { ReaderThemeOption.valueOf(value.orEmpty()) }
            .getOrDefault(ReaderThemeOption.LIGHT)
    }

    fun saveReaderFontSize(context: Context, size: ReaderFontSize) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_READER_FONT_SIZE, size.name)
            .apply()
    }

    fun readReaderFontSize(context: Context): ReaderFontSize {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_READER_FONT_SIZE, ReaderFontSize.MEDIUM.name)

        return runCatching { ReaderFontSize.valueOf(value.orEmpty()) }
            .getOrDefault(ReaderFontSize.MEDIUM)
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

        val lastOpenedUri = lastOpened(context)

        return result
            .distinctBy { it.uri }
            .sortedWith(
                compareBy<LibraryBook> { if (it.uri == lastOpenedUri) 0 else 1 }
                    .thenBy { it.title.lowercase() }
            )
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

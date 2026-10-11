package com.foldbook.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

enum class BookFormat { EPUB, PDF }
enum class ReaderThemeOption { LIGHT, SEPIA, DARK, AMOLED }
enum class ReaderFontSize { SMALL, MEDIUM, LARGE }

data class LibraryBook(
    val title: String,
    val uri: String,
    val format: BookFormat,
    val modifiedAt: Long = 0L,
    val isFavorite: Boolean = false
)

data class PickedBookAddResult(
    val book: LibraryBook,
    val alreadyExists: Boolean
)

data class BookNote(
    val id: String,
    val bookUri: String,
    val bookTitle: String,
    val pageNumber: Int,
    val text: String,
    val createdAt: Long
)

data class HighlightBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class BookHighlight(
    val id: String,
    val bookUri: String,
    val pageNumber: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val bounds: List<HighlightBox> = emptyList(),
    val createdAt: Long
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
    private const val KEY_FAVORITES = "favorite_book_uris"
    private const val KEY_NOTES_JSON = "book_notes_json"
    private const val KEY_HIGHLIGHTS_JSON = "book_highlights_json"
    private const val KEY_PICKED_BOOKS_JSON = "picked_books_json"

    fun saveFolder(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
    }

    fun savedFolder(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)

        return value
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun addPickedBook(context: Context, uri: Uri): PickedBookAddResult {
        val uriString = uri.toString()
        val existing = readPickedBooks(context).firstOrNull { it.uri == uriString }
        if (existing != null) {
            return PickedBookAddResult(existing, alreadyExists = true)
        }

        val displayName = resolvePickedDisplayName(context, uri)
        val format = detectPickedBookFormat(context, uri, displayName)
            ?: error("FoldBook yalnızca EPUB ve PDF dosyalarını ekleyebilir.")
        val title = displayName
            .substringBeforeLast('.', displayName)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "İsimsiz Kitap" }

        val book = LibraryBook(
            title = title,
            uri = uriString,
            format = format,
            modifiedAt = System.currentTimeMillis(),
            isFavorite = isFavorite(context, uriString)
        )
        savePickedBooks(context, readPickedBooks(context) + book)
        return PickedBookAddResult(book, alreadyExists = false)
    }

    fun readPickedBooks(context: Context): List<LibraryBook> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PICKED_BOOKS_JSON, "[]")
            .orEmpty()

        return runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri").trim()
                    if (uri.isBlank()) continue
                    val format = runCatching {
                        BookFormat.valueOf(item.optString("format"))
                    }.getOrNull() ?: continue
                    val title = item.optString("title")
                        .trim()
                        .ifBlank { "İsimsiz Kitap" }

                    add(
                        LibraryBook(
                            title = title,
                            uri = uri,
                            format = format,
                            modifiedAt = item.optLong("modifiedAt", 0L),
                            isFavorite = isFavorite(context, uri)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun mergePickedBooks(
        context: Context,
        folderBooks: List<LibraryBook>
    ): List<LibraryBook> {
        return (readPickedBooks(context) + folderBooks)
            .distinctBy { it.uri }
            .map { book -> book.copy(isFavorite = isFavorite(context, book.uri)) }
            .sortedWith(
                compareByDescending<LibraryBook> { it.modifiedAt }
                    .thenBy { it.title.lowercase() }
            )
    }

    fun isPickedBook(context: Context, bookUri: String): Boolean {
        return readPickedBooks(context).any { it.uri == bookUri }
    }

    private fun savePickedBooks(context: Context, books: List<LibraryBook>) {
        val array = JSONArray()
        books.distinctBy { it.uri }.forEach { book ->
            array.put(
                JSONObject()
                    .put("title", book.title)
                    .put("uri", book.uri)
                    .put("format", book.format.name)
                    .put("modifiedAt", book.modifiedAt)
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PICKED_BOOKS_JSON, array.toString())
            .apply()
    }

    private fun resolvePickedDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        val name = cursor.getString(index).orEmpty().trim()
                        if (name.isNotBlank()) return name
                    }
                }
            }
        }

        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "kitap"
    }

    private fun detectPickedBookFormat(
        context: Context,
        uri: Uri,
        displayName: String
    ): BookFormat? {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when {
            mime.equals("application/pdf", ignoreCase = true) -> BookFormat.PDF
            mime.equals("application/epub+zip", ignoreCase = true) -> BookFormat.EPUB
            mime.equals("application/x-epub+zip", ignoreCase = true) -> BookFormat.EPUB
            displayName.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
            displayName.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
            else -> null
        }
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
            format = format,
            isFavorite = isFavorite(context, uri)
        )
    }

    fun saveLastOpened(context: Context, bookUri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_OPENED_URI, bookUri)
            .apply()
    }

    fun readLastOpened(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_OPENED_URI, null)
            ?.takeIf { it.isNotBlank() }
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

    fun isFavorite(context: Context, bookUri: String): Boolean {
        return favorites(context).contains(bookUri)
    }

    fun setFavorite(
        context: Context,
        bookUri: String,
        favorite: Boolean
    ) {
        val updated = favorites(context).toMutableSet()
        if (favorite) {
            updated += bookUri
        } else {
            updated -= bookUri
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITES, updated)
            .apply()
    }

    fun toggleFavorite(context: Context, book: LibraryBook): Boolean {
        val newValue = !isFavorite(context, book.uri)
        setFavorite(context, book.uri, newValue)
        return newValue
    }

    private fun favorites(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun renameBook(
        context: Context,
        book: LibraryBook,
        requestedTitle: String
    ): String {
        val safeTitle = requestedTitle
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { error("Kitap adı boş olamaz.") }

        if (isPickedBook(context, book.uri)) {
            val updated = readPickedBooks(context).map { current ->
                if (current.uri == book.uri) current.copy(title = safeTitle) else current
            }
            savePickedBooks(context, updated)

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_ACTIVE_BOOK_URI, null) == book.uri) {
                prefs.edit().putString(KEY_ACTIVE_BOOK_TITLE, safeTitle).apply()
            }
            return book.uri
        }

        val treeUri = savedFolder(context)
        val root = treeUri
            ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
            ?.let { DocumentFile.fromTreeUri(context, it) }

        val file = root?.let { findDocumentByUri(it, book.uri) }
            ?: DocumentFile.fromSingleUri(context, Uri.parse(book.uri))
            ?: error("Kitap dosyasına erişilemedi.")

        val originalName = file.name.orEmpty()
        val extension = originalName
            .substringAfterLast('.', book.format.name.lowercase())
            .lowercase()

        val newFileName = "$safeTitle.$extension"
        if (originalName == newFileName) return book.uri

        val oldUri = book.uri
        val oldProgress = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(progressKey(oldUri), 0)
        val wasFavorite = isFavorite(context, oldUri)

        if (!file.renameTo(newFileName)) {
            error("Kitap yeniden adlandırılamadı. Dosya için yazma izni gerekebilir.")
        }

        val newUri = file.uri.toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (newUri != oldUri) {
            editor.remove(progressKey(oldUri)).putInt(progressKey(newUri), oldProgress)
            if (prefs.getString(KEY_LAST_OPENED_URI, null) == oldUri) {
                editor.putString(KEY_LAST_OPENED_URI, newUri)
            }
            if (prefs.getString(KEY_ACTIVE_BOOK_URI, null) == oldUri) {
                editor.putString(KEY_ACTIVE_BOOK_URI, newUri)
            }
        }

        if (prefs.getString(KEY_ACTIVE_BOOK_TITLE, null) == book.title) {
            editor.putString(KEY_ACTIVE_BOOK_TITLE, safeTitle)
        }
        editor.apply()

        if (newUri != oldUri && wasFavorite) {
            setFavorite(context, oldUri, false)
            setFavorite(context, newUri, true)
        }
        if (newUri != oldUri) migrateHighlights(context, oldUri, newUri)

        return newUri
    }

    private fun findDocumentByUri(
        directory: DocumentFile,
        targetUri: String
    ): DocumentFile? {
        if (directory.uri.toString() == targetUri) return directory

        for (child in directory.listFiles()) {
            if (child.uri.toString() == targetUri) return child
            if (child.isDirectory) {
                val nested = findDocumentByUri(child, targetUri)
                if (nested != null) return nested
            }
        }
        return null
    }

    fun deleteBook(context: Context, book: LibraryBook) {
        val picked = isPickedBook(context, book.uri)
        if (picked) {
            savePickedBooks(
                context,
                readPickedBooks(context).filterNot { it.uri == book.uri }
            )
        } else {
            val file = DocumentFile.fromSingleUri(context, Uri.parse(book.uri))
                ?: error("Kitap dosyasına erişilemedi.")

            if (!file.delete()) {
                error("Kitap silinemedi. Dosya için yazma izni gerekebilir.")
            }
        }

        setFavorite(context, book.uri, false)
        deleteHighlightsForBook(context, book.uri)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().remove(progressKey(book.uri))

        if (prefs.getString(KEY_LAST_OPENED_URI, null) == book.uri) {
            editor.remove(KEY_LAST_OPENED_URI)
        }
        if (prefs.getString(KEY_ACTIVE_BOOK_URI, null) == book.uri) {
            editor.remove(KEY_ACTIVE_BOOK_URI)
                .remove(KEY_ACTIVE_BOOK_TITLE)
                .remove(KEY_ACTIVE_BOOK_FORMAT)
        }
        editor.apply()
    }

    fun addNote(
        context: Context,
        bookUri: String,
        bookTitle: String,
        pageNumber: Int,
        text: String
    ) {
        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        if (cleanText.isBlank()) return

        val now = System.currentTimeMillis()
        val notes = readNotes(context).toMutableList()
        notes.add(
            0,
            BookNote(
                id = now.toString() + "-" + cleanText.hashCode().toString(),
                bookUri = bookUri,
                bookTitle = bookTitle.ifBlank { "Kitap" },
                pageNumber = pageNumber.coerceAtLeast(1),
                text = cleanText,
                createdAt = now
            )
        )
        saveNotes(context, notes)
    }

    fun readNotes(context: Context): List<BookNote> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NOTES_JSON, "[]")
            .orEmpty()

        return runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.isBlank()) continue
                    add(
                        BookNote(
                            id = item.optString("id").ifBlank {
                                item.optLong("createdAt").toString() + "-" + index
                            },
                            bookUri = item.optString("bookUri"),
                            bookTitle = item.optString("bookTitle").ifBlank { "Kitap" },
                            pageNumber = item.optInt("pageNumber", 1).coerceAtLeast(1),
                            text = text,
                            createdAt = item.optLong("createdAt", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun deleteNote(context: Context, noteId: String) {
        saveNotes(context, readNotes(context).filterNot { it.id == noteId })
    }

    private fun saveNotes(context: Context, notes: List<BookNote>) {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("bookUri", note.bookUri)
                    .put("bookTitle", note.bookTitle)
                    .put("pageNumber", note.pageNumber)
                    .put("text", note.text)
                    .put("createdAt", note.createdAt)
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTES_JSON, array.toString())
            .apply()
    }

    fun addHighlight(
        context: Context,
        bookUri: String,
        pageNumber: Int,
        startOffset: Int,
        endOffset: Int,
        text: String,
        bounds: List<HighlightBox> = emptyList()
    ) {
        val cleanText = text.trim()
        if (cleanText.isBlank() || endOffset <= startOffset) return

        val highlights = readAllHighlights(context).toMutableList()
        val duplicate = highlights.any {
            it.bookUri == bookUri &&
                it.pageNumber == pageNumber &&
                it.startOffset == startOffset &&
                it.endOffset == endOffset &&
                it.text == cleanText
        }
        if (duplicate) return

        val now = System.currentTimeMillis()
        highlights.add(
            BookHighlight(
                id = now.toString() + "-" + cleanText.hashCode().toString(),
                bookUri = bookUri,
                pageNumber = pageNumber.coerceAtLeast(1),
                startOffset = startOffset.coerceAtLeast(0),
                endOffset = endOffset.coerceAtLeast(startOffset + 1),
                text = cleanText,
                bounds = bounds,
                createdAt = now
            )
        )
        saveHighlights(context, highlights)
    }

    fun readHighlights(
        context: Context,
        bookUri: String
    ): List<BookHighlight> {
        return readAllHighlights(context)
            .filter { it.bookUri == bookUri }
            .sortedBy { it.createdAt }
    }

    private fun readAllHighlights(context: Context): List<BookHighlight> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HIGHLIGHTS_JSON, "[]")
            .orEmpty()

        return runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    val start = item.optInt("startOffset", -1)
                    val end = item.optInt("endOffset", -1)
                    if (text.isBlank() || start < 0 || end <= start) continue

                    val savedBounds = buildList {
                        val boundsArray = item.optJSONArray("bounds")
                        if (boundsArray != null) {
                            for (boundIndex in 0 until boundsArray.length()) {
                                val bound = boundsArray.optJSONObject(boundIndex) ?: continue
                                val left = bound.optDouble("left", Double.NaN)
                                val top = bound.optDouble("top", Double.NaN)
                                val right = bound.optDouble("right", Double.NaN)
                                val bottom = bound.optDouble("bottom", Double.NaN)
                                if (
                                    left.isFinite() && top.isFinite() &&
                                    right.isFinite() && bottom.isFinite() &&
                                    right > left && bottom > top
                                ) {
                                    add(
                                        HighlightBox(
                                            left = left.toFloat(),
                                            top = top.toFloat(),
                                            right = right.toFloat(),
                                            bottom = bottom.toFloat()
                                        )
                                    )
                                }
                            }
                        }
                    }

                    add(
                        BookHighlight(
                            id = item.optString("id").ifBlank {
                                item.optLong("createdAt").toString() + "-" + index
                            },
                            bookUri = item.optString("bookUri"),
                            pageNumber = item.optInt("pageNumber", 1).coerceAtLeast(1),
                            startOffset = start,
                            endOffset = end,
                            text = text,
                            bounds = savedBounds,
                            createdAt = item.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHighlights(
        context: Context,
        highlights: List<BookHighlight>
    ) {
        val array = JSONArray()
        highlights.forEach { highlight ->
            array.put(
                JSONObject()
                    .put("id", highlight.id)
                    .put("bookUri", highlight.bookUri)
                    .put("pageNumber", highlight.pageNumber)
                    .put("startOffset", highlight.startOffset)
                    .put("endOffset", highlight.endOffset)
                    .put("text", highlight.text)
                    .put(
                        "bounds",
                        JSONArray().apply {
                            highlight.bounds.forEach { bound ->
                                put(
                                    JSONObject()
                                        .put("left", bound.left.toDouble())
                                        .put("top", bound.top.toDouble())
                                        .put("right", bound.right.toDouble())
                                        .put("bottom", bound.bottom.toDouble())
                                )
                            }
                        }
                    )
                    .put("createdAt", highlight.createdAt)
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HIGHLIGHTS_JSON, array.toString())
            .apply()
    }

    private fun migrateHighlights(
        context: Context,
        oldUri: String,
        newUri: String
    ) {
        if (oldUri == newUri) return
        val updated = readAllHighlights(context).map { highlight ->
            if (highlight.bookUri == oldUri) highlight.copy(bookUri = newUri) else highlight
        }
        saveHighlights(context, updated)
    }

    private fun deleteHighlightsForBook(
        context: Context,
        bookUri: String
    ) {
        saveHighlights(
            context,
            readAllHighlights(context).filterNot { it.bookUri == bookUri }
        )
    }

    fun scanFolder(context: Context, treeUri: Uri): List<LibraryBook> {
        val result = mutableListOf<LibraryBook>()

        if (treeUri.scheme.equals("content", ignoreCase = true)) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Seçilen klasör açılamadı.")

            if (!root.exists() || !root.canRead()) {
                error("Kitap klasörüne erişilemiyor.")
            }
            collectBooks(root, result)
        }

        return result
            .distinctBy { book ->
                "${book.title.lowercase()}|${book.format.name}|${book.modifiedAt / 1000L}"
            }
            .map { book ->
                book.copy(isFavorite = isFavorite(context, book.uri))
            }
            .sortedWith(
                compareByDescending<LibraryBook> { it.modifiedAt }
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
                        format = format,
                        modifiedAt = file.lastModified()
                    )
                }
            }
        }
    }
}

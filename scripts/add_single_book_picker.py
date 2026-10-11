from pathlib import Path

root = Path('.')

# --- LibraryStore.kt ---
lib_path = root / 'app/src/main/java/com/foldbook/app/LibraryStore.kt'
lib = lib_path.read_text(encoding='utf-8')

lib = lib.replace(
    'import android.net.Uri\n',
    'import android.net.Uri\nimport android.provider.OpenableColumns\n',
    1,
)

library_book_block = '''data class LibraryBook(
    val title: String,
    val uri: String,
    val format: BookFormat,
    val modifiedAt: Long = 0L,
    val isFavorite: Boolean = false
)
'''
if library_book_block not in lib:
    raise SystemExit('LibraryBook block not found')
lib = lib.replace(
    library_book_block,
    library_book_block + '''\ndata class PickedBookAddResult(\n    val book: LibraryBook,\n    val alreadyExists: Boolean\n)\n''',
    1,
)

key_marker = '    private const val KEY_HIGHLIGHTS_JSON = "book_highlights_json"\n'
if key_marker not in lib:
    raise SystemExit('key marker not found')
lib = lib.replace(
    key_marker,
    key_marker + '    private const val KEY_PICKED_BOOKS_JSON = "picked_books_json"\n',
    1,
)

save_active_marker = '    fun saveActiveBook(context: Context, book: LibraryBook) {'
if save_active_marker not in lib:
    raise SystemExit('saveActiveBook marker not found')

picked_helpers = r'''    fun addPickedBook(context: Context, uri: Uri): PickedBookAddResult {
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

'''
lib = lib.replace(save_active_marker, picked_helpers + save_active_marker, 1)

rename_start = lib.index('    fun renameBook(\n')
rename_end = lib.index('    private fun findDocumentByUri(', rename_start)
new_rename = r'''    fun renameBook(
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

'''
lib = lib[:rename_start] + new_rename + lib[rename_end:]

delete_start = lib.index('    fun deleteBook(context: Context, book: LibraryBook) {')
delete_end = lib.index('    fun addNote(', delete_start)
new_delete = r'''    fun deleteBook(context: Context, book: LibraryBook) {
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

'''
lib = lib[:delete_start] + new_delete + lib[delete_end:]
lib_path.write_text(lib, encoding='utf-8')

# --- MainActivity.kt ---
main_path = root / 'app/src/main/java/com/foldbook/app/MainActivity.kt'
main = main_path.read_text(encoding='utf-8')

old_init = '    var library by remember { mutableStateOf(BundledGuide.withGuide(context, emptyList())) }'
new_init = '    var library by remember { mutableStateOf(BundledGuide.withGuide(context, LibraryStore.readPickedBooks(context))) }'
if old_init not in main:
    raise SystemExit('library init not found')
main = main.replace(old_init, new_init, 1)

main = main.replace(
    '                library = BundledGuide.withGuide(context, found)',
    '                library = BundledGuide.withGuide(context, LibraryStore.mergePickedBooks(context, found))',
    1,
)

# Both rename and delete refreshes use the same statement.
main = main.replace(
    '                    library = BundledGuide.withGuide(context, refreshed)',
    '                    library = BundledGuide.withGuide(context, LibraryStore.mergePickedBooks(context, refreshed))',
)

folder_picker_marker = '''    LaunchedEffect(Unit) {
'''
if folder_picker_marker not in main:
    raise SystemExit('LaunchedEffect marker not found')

book_picker = r'''    val bookPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            scope.launch {
                libraryLoading = true
                libraryError = null

                runCatching {
                    withContext(Dispatchers.IO) {
                        LibraryStore.addPickedBook(context, uri)
                    }
                }.onSuccess { result ->
                    Toast.makeText(
                        context,
                        if (result.alreadyExists) {
                            "Bu kitap zaten rafta."
                        } else {
                            "Kitap rafa eklendi."
                        },
                        Toast.LENGTH_SHORT
                    ).show()

                    val folder = LibraryStore.savedFolder(context)
                    if (folder != null) {
                        scanFolder(folder)
                    } else {
                        library = BundledGuide.withGuide(
                            context,
                            LibraryStore.readPickedBooks(context)
                        )
                        libraryLoading = false
                    }
                }.onFailure { error ->
                    libraryError = error.message ?: "Kitap rafa eklenemedi."
                    libraryLoading = false
                }
            }
        }
    }

'''
main = main.replace(folder_picker_marker, book_picker + folder_picker_marker, 1)

# Refresh must also retain/show individually picked books if no folder is selected.
old_refresh = '''            onRefresh = {
                LibraryStore.savedFolder(context)?.let { scanFolder(it) }
            },
'''
new_refresh = '''            onRefresh = {
                val folder = LibraryStore.savedFolder(context)
                if (folder != null) {
                    scanFolder(folder)
                } else {
                    library = BundledGuide.withGuide(
                        context,
                        LibraryStore.readPickedBooks(context)
                    )
                }
            },
'''
if old_refresh not in main:
    raise SystemExit('onRefresh block not found')
main = main.replace(old_refresh, new_refresh, 1)

old_call = '''            onChooseFolder = { folderPicker.launch(null) },
            onRefresh = {'''
new_call = '''            onChooseFolder = { folderPicker.launch(null) },
            onChooseFile = {
                bookPicker.launch(
                    arrayOf(
                        "application/epub+zip",
                        "application/x-epub+zip",
                        "application/pdf"
                    )
                )
            },
            onRefresh = {'''
if old_call not in main:
    raise SystemExit('LibraryScreen call insertion point not found')
main = main.replace(old_call, new_call, 1)

old_sig = '''    error: String?,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
'''
new_sig = '''    error: String?,
    onChooseFolder: () -> Unit,
    onChooseFile: () -> Unit,
    onRefresh: () -> Unit,
'''
if old_sig not in main:
    raise SystemExit('LibraryScreen signature not found')
main = main.replace(old_sig, new_sig, 1)

settings_marker = '''                            Text(
                                text = "Tema",
'''
settings_insert = '''                            Button(
                                onClick = onChooseFile,
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("+ Kitap Dosyası Ekle (EPUB / PDF)")
                            }

                            Text(
                                text = "Tek bir kitap seçer; daha önce eklediğin kitaplar rafta kalır.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            )

'''
if settings_marker not in main:
    raise SystemExit('settings Tema marker not found')
main = main.replace(settings_marker, settings_insert + settings_marker, 1)

old_empty_call = '''                    EmptyLibrary(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        onChooseFolder = onChooseFolder
                    )
'''
new_empty_call = '''                    EmptyLibrary(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        onChooseFolder = onChooseFolder,
                        onChooseFile = onChooseFile
                    )
'''
if old_empty_call not in main:
    raise SystemExit('EmptyLibrary call not found')
main = main.replace(old_empty_call, new_empty_call, 1)

old_empty_sig = '''private fun EmptyLibrary(
    modifier: Modifier = Modifier,
    onChooseFolder: () -> Unit
) {
'''
new_empty_sig = '''private fun EmptyLibrary(
    modifier: Modifier = Modifier,
    onChooseFolder: () -> Unit,
    onChooseFile: () -> Unit
) {
'''
if old_empty_sig not in main:
    raise SystemExit('EmptyLibrary signature not found')
main = main.replace(old_empty_sig, new_empty_sig, 1)

old_empty_button = '''            Button(onClick = onChooseFolder) {
                Text("Kitapların Olduğu Klasörü Seç")
            }
'''
new_empty_button = '''            Button(onClick = onChooseFolder) {
                Text("Kitapların Olduğu Klasörü Seç")
            }

            Spacer(Modifier.height(10.dp))

            Button(onClick = onChooseFile) {
                Text("+ Kitap Dosyası Ekle")
            }
'''
if old_empty_button not in main:
    raise SystemExit('EmptyLibrary button block not found')
main = main.replace(old_empty_button, new_empty_button, 1)

old_confirm = '''                    text = if (BundledGuide.isGuide(book.uri)) {
                        "FoldBook Kullanım Kılavuzu raftan kaldırılacak ve uygulamayı tekrar açtığında geri gelmeyecek."
                    } else {
                        "“${book.title}” cihazındaki kitap klasöründen silinecek."
                    }
'''
new_confirm = '''                    text = when {
                        BundledGuide.isGuide(book.uri) -> {
                            "FoldBook Kullanım Kılavuzu raftan kaldırılacak ve uygulamayı tekrar açtığında geri gelmeyecek."
                        }
                        LibraryStore.isPickedBook(context, book.uri) -> {
                            "“${book.title}” FoldBook rafından kaldırılacak. Orijinal dosya cihazında kalacak."
                        }
                        else -> {
                            "“${book.title}” cihazındaki kitap klasöründen silinecek."
                        }
                    }
'''
if old_confirm not in main:
    raise SystemExit('delete confirmation block not found')
main = main.replace(old_confirm, new_confirm, 1)

main_path.write_text(main, encoding='utf-8')

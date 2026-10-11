from pathlib import Path

root = Path('.')

guide_path = root / 'app/src/main/java/com/foldbook/app/BundledGuide.kt'
guide = guide_path.read_text(encoding='utf-8')

guide = guide.replace(
    '    private const val KEY_VERSION = "guide_version"\n    private const val GUIDE_VERSION = 1\n',
    '    private const val KEY_VERSION = "guide_version"\n    private const val KEY_DISMISSED = "guide_dismissed"\n    private const val GUIDE_VERSION = 1\n'
)

guide = guide.replace(
'''    fun withGuide(context: Context, books: List<LibraryBook>): List<LibraryBook> {
        val guide = ensureBook(context)
        return buildList {
            add(guide)
            addAll(books.filterNot { it.uri == guide.uri })
        }
    }

    fun isGuide(uri: String): Boolean {
''',
'''    fun withGuide(context: Context, books: List<LibraryBook>): List<LibraryBook> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISMISSED, false)) return books

        val guide = ensureBook(context)
        return buildList {
            add(guide)
            addAll(books.filterNot { it.uri == guide.uri })
        }
    }

    fun dismiss(context: Context) {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        val destination = File(directory, FILE_NAME)
        if (destination.exists()) destination.delete()

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISMISSED, true)
            .apply()

        LibraryStore.setFavorite(context, Uri.fromFile(destination).toString(), false)
    }

    fun isGuide(uri: String): Boolean {
'''
)

guide_path.write_text(guide, encoding='utf-8')

main_path = root / 'app/src/main/java/com/foldbook/app/MainActivity.kt'
main = main_path.read_text(encoding='utf-8')

old_delete = '''    fun deleteBook(book: LibraryBook) {
        val folder = LibraryStore.savedFolder(context)

        scope.launch {
            libraryLoading = true
            libraryError = null

            runCatching {
                withContext(Dispatchers.IO) {
                    LibraryStore.deleteBook(context, book)
                    folder?.let { LibraryStore.scanFolder(context, it) }
                }
            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    library = BundledGuide.withGuide(context, refreshed)
                } else {
                    library = library.filterNot { it.uri == book.uri }
                }
            }.onFailure {
                libraryError = it.message ?: "Kitap silinemedi."
            }

            libraryLoading = false
        }
    }
'''
new_delete = '''    fun deleteBook(book: LibraryBook) {
        if (BundledGuide.isGuide(book.uri)) {
            BundledGuide.dismiss(context)
            library = library.filterNot { it.uri == book.uri }
            return
        }

        val folder = LibraryStore.savedFolder(context)

        scope.launch {
            libraryLoading = true
            libraryError = null

            runCatching {
                withContext(Dispatchers.IO) {
                    LibraryStore.deleteBook(context, book)
                    folder?.let { LibraryStore.scanFolder(context, it) }
                }
            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    library = BundledGuide.withGuide(context, refreshed)
                } else {
                    library = library.filterNot { it.uri == book.uri }
                }
            }.onFailure {
                libraryError = it.message ?: "Kitap silinemedi."
            }

            libraryLoading = false
        }
    }
'''
if old_delete not in main:
    raise SystemExit('deleteBook block not found')
main = main.replace(old_delete, new_delete)

old_menu = '''                    if (!BundledGuide.isGuide(book.uri)) {
                        Button(
                            onClick = {
                                renameBookTarget = book
                                renameText = book.title
                                actionBook = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Yeniden Adlandır")
                        }

                        Button(
                            onClick = {
                                deleteBookTarget = book
                                actionBook = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Kitabı Sil")
                        }
                    }
'''
new_menu = '''                    if (!BundledGuide.isGuide(book.uri)) {
                        Button(
                            onClick = {
                                renameBookTarget = book
                                renameText = book.title
                                actionBook = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Yeniden Adlandır")
                        }
                    }

                    Button(
                        onClick = {
                            deleteBookTarget = book
                            actionBook = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kitabı Sil")
                    }
'''
if old_menu not in main:
    raise SystemExit('action menu block not found')
main = main.replace(old_menu, new_menu)

old_confirm = '''                Text(
                    text = "“${book.title}” cihazındaki kitap klasöründen silinecek."
                )
'''
new_confirm = '''                Text(
                    text = if (BundledGuide.isGuide(book.uri)) {
                        "FoldBook Kullanım Kılavuzu raftan kaldırılacak ve uygulamayı tekrar açtığında geri gelmeyecek."
                    } else {
                        "“${book.title}” cihazındaki kitap klasöründen silinecek."
                    }
                )
'''
if old_confirm not in main:
    raise SystemExit('delete confirmation block not found')
main = main.replace(old_confirm, new_confirm)

main_path.write_text(main, encoding='utf-8')

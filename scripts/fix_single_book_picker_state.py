from pathlib import Path

path = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
text = path.read_text(encoding='utf-8')

old_rename = '''            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    library = BundledGuide.withGuide(context, LibraryStore.mergePickedBooks(context, refreshed))
                }
            }.onFailure {
                libraryError = it.message ?: "Kitap yeniden adlandırılamadı."
            }
'''
new_rename = '''            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    library = BundledGuide.withGuide(context, LibraryStore.mergePickedBooks(context, refreshed))
                } else {
                    library = BundledGuide.withGuide(
                        context,
                        LibraryStore.readPickedBooks(context)
                    )
                }
            }.onFailure {
                libraryError = it.message ?: "Kitap yeniden adlandırılamadı."
            }
'''
if old_rename not in text:
    raise SystemExit('rename success block not found')
text = text.replace(old_rename, new_rename, 1)

old_success = '''                }.onSuccess { result ->
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
'''
new_success = '''                }.onSuccess { result ->
                    // Yeni seçilen kitabı mevcut rafın üzerine birleştirerek ekle.
                    // Böylece sonraki klasör taraması hata verse bile eski kitaplar kaybolmaz.
                    library = BundledGuide.withGuide(
                        context,
                        LibraryStore.mergePickedBooks(
                            context,
                            library.filterNot { BundledGuide.isGuide(it.uri) }
                        )
                    )

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
'''
if old_success not in text:
    raise SystemExit('picker success block not found')
text = text.replace(old_success, new_success, 1)

path.write_text(text, encoding='utf-8')

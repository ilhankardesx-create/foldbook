package com.foldbook.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * FoldBook ile birlikte gelen küçük EPUB kullanım kılavuzunu uygulamanın özel
 * depolama alanına çıkarır ve normal bir EPUB kitabı gibi okuyucuya sunar.
 *
 * Kılavuz yalnızca uygulamanın kendi dosya alanında tutulur; geniş depolama
 * izinlarına ihtiyaç duymaz.
 */
object BundledGuide {
    private const val ASSET_PATH = "books/foldbook_kullanim_kilavuzu.epub"
    private const val FILE_NAME = "foldbook_kullanim_kilavuzu.epub"
    private const val DIRECTORY_NAME = "bundled_books"
    private const val PREFS = "foldbook_bundled_guide"
    private const val KEY_VERSION = "guide_version"
    private const val GUIDE_VERSION = 1

    const val TITLE = "FoldBook Kullanım Kılavuzu"

    fun ensureBook(context: Context): LibraryBook {
        val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
        val destination = File(directory, FILE_NAME)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installedVersion = prefs.getInt(KEY_VERSION, 0)

        if (!destination.exists() || destination.length() == 0L || installedVersion < GUIDE_VERSION) {
            val temporary = File(directory, "$FILE_NAME.tmp")
            context.assets.open(ASSET_PATH).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }

            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }

            prefs.edit().putInt(KEY_VERSION, GUIDE_VERSION).apply()
        }

        val uri = Uri.fromFile(destination).toString()
        return LibraryBook(
            title = TITLE,
            uri = uri,
            format = BookFormat.EPUB,
            // Kılavuz rafın başında kolayca bulunabilsin.
            modifiedAt = Long.MAX_VALUE - 1024L,
            isFavorite = LibraryStore.isFavorite(context, uri)
        )
    }

    fun withGuide(context: Context, books: List<LibraryBook>): List<LibraryBook> {
        val guide = ensureBook(context)
        return buildList {
            add(guide)
            addAll(books.filterNot { it.uri == guide.uri })
        }
    }

    fun isGuide(uri: String): Boolean {
        return uri.endsWith("/$DIRECTORY_NAME/$FILE_NAME") ||
            uri.endsWith("%2F$DIRECTORY_NAME%2F$FILE_NAME")
    }
}

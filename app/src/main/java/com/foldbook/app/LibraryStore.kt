package com.foldbook.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class LibraryBook(
    val title: String,
    val uri: String
)

object LibraryStore {
    private const val PREFS = "foldbook_library"
    private const val KEY_FOLDER_URI = "book_folder_uri"

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

    fun scanFolder(context: Context, treeUri: Uri): List<LibraryBook> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Seçilen klasör açılamadı.")

        if (!root.exists() || !root.canRead()) {
            error("Kitap klasörüne erişilemiyor.")
        }

        val result = mutableListOf<LibraryBook>()
        collectEpubs(root, result)

        return result
            .distinctBy { it.uri }
            .sortedBy { it.title.lowercase() }
    }

    private fun collectEpubs(
        directory: DocumentFile,
        output: MutableList<LibraryBook>
    ) {
        for (file in directory.listFiles()) {
            when {
                file.isDirectory -> collectEpubs(file, output)
                file.isFile && file.name?.endsWith(".epub", ignoreCase = true) == true -> {
                    val displayName = file.name.orEmpty()
                    val title = displayName
                        .substringBeforeLast(".")
                        .replace('_', ' ')
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { "İsimsiz Kitap" }

                    output += LibraryBook(
                        title = title,
                        uri = file.uri.toString()
                    )
                }
            }
        }
    }
}

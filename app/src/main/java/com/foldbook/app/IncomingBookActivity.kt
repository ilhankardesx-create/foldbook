package com.foldbook.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

/** Receives "Open with FoldBook" intents for PDF/EPUB files. */
class IncomingBookActivity : Activity() {

    companion object {
        private const val REQUEST_FOLDER = 4107
        private const val STATE_PENDING_URI = "pending_book_uri"
        private const val STATE_PENDING_MIME = "pending_book_mime"
    }

    private var pendingBookUri: Uri? = null
    private var pendingMimeType: String? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingBookUri = savedInstanceState
            ?.getString(STATE_PENDING_URI)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: intent?.data
        pendingMimeType = savedInstanceState
            ?.getString(STATE_PENDING_MIME)
            ?: intent?.type

        val incoming = pendingBookUri
        if (intent?.action != Intent.ACTION_VIEW || incoming == null) {
            openMainAndFinish()
            return
        }

        val selectedFolder = LibraryStore.savedFolder(this)
            ?.takeIf { it.scheme.equals("content", ignoreCase = true) }

        if (selectedFolder == null) {
            Toast.makeText(
                this,
                "Kitabı rafa eklemek için önce FoldBook klasörünü seç.",
                Toast.LENGTH_LONG
            ).show()
            launchFolderPicker()
        } else {
            importPendingBook(selectedFolder)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingBookUri?.let { outState.putString(STATE_PENDING_URI, it.toString()) }
        pendingMimeType?.let { outState.putString(STATE_PENDING_MIME, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Uses the platform result callback for ACTION_OPEN_DOCUMENT_TREE.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_FOLDER) return
        if (resultCode != RESULT_OK) {
            finish()
            return
        }

        val folderUri = data?.data ?: run {
            finish()
            return
        }

        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            contentResolver.takePersistableUriPermission(folderUri, takeFlags)
        }

        LibraryStore.saveFolder(this, folderUri)
        importPendingBook(folderUri)
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun launchFolderPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        startActivityForResult(picker, REQUEST_FOLDER)
    }

    private fun importPendingBook(folderUri: Uri) {
        val incomingUri = pendingBookUri ?: run {
            openMainAndFinish()
            return
        }

        ioExecutor.execute {
            val result = runCatching {
                importIntoShelf(
                    incomingUri = incomingUri,
                    incomingMimeType = pendingMimeType,
                    folderUri = folderUri
                )
            }

            runOnUiThread {
                result.onSuccess { book ->
                    LibraryStore.saveLastOpened(this, book.uri)
                    LibraryStore.saveActiveBook(this, book)
                    openMainAndFinish()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Kitap FoldBook'a eklenemedi.",
                        Toast.LENGTH_LONG
                    ).show()
                    openMainAndFinish()
                }
            }
        }
    }

    private fun importIntoShelf(
        incomingUri: Uri,
        incomingMimeType: String?,
        folderUri: Uri
    ): LibraryBook {
        val root = DocumentFile.fromTreeUri(this, folderUri)
            ?: error("FoldBook klasörüne erişilemedi.")
        if (!root.exists() || !root.canWrite()) {
            error("FoldBook klasörüne yazma izni yok.")
        }

        val displayName = resolveDisplayName(incomingUri)
        val format = detectFormat(displayName, incomingMimeType)
            ?: error("FoldBook yalnızca EPUB ve PDF dosyalarını açabilir.")
        val extension = if (format == BookFormat.PDF) "pdf" else "epub"
        val safeName = normalizeFileName(displayName, extension)
        val mimeType = if (format == BookFormat.PDF) {
            "application/pdf"
        } else {
            "application/epub+zip"
        }

        val existing = root.listFiles().firstOrNull { file ->
            file.isFile && file.name.equals(safeName, ignoreCase = true)
        }

        val target = existing ?: root.createFile(mimeType, safeName)
            ?: error("Kitap dosyası raf klasöründe oluşturulamadı.")

        if (existing == null) {
            val input = contentResolver.openInputStream(incomingUri)
                ?: error("Seçilen kitap okunamadı.")
            val output = contentResolver.openOutputStream(target.uri, "w")
                ?: error("Kitap raf klasörüne yazılamadı.")

            input.use { source ->
                output.use { destination ->
                    source.copyTo(destination, DEFAULT_BUFFER_SIZE)
                }
            }
        }

        val title = target.name.orEmpty()
            .substringBeforeLast('.', target.name.orEmpty())
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "İsimsiz Kitap" }

        return LibraryBook(
            title = title,
            uri = target.uri.toString(),
            format = format,
            modifiedAt = target.lastModified(),
            isFavorite = LibraryStore.isFavorite(this, target.uri.toString())
        )
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        return cursor.getString(index).orEmpty()
                    }
                }
            }
        }

        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "kitap"
    }

    private fun detectFormat(name: String, mimeType: String?): BookFormat? {
        return when {
            mimeType.equals("application/pdf", ignoreCase = true) -> BookFormat.PDF
            mimeType.equals("application/epub+zip", ignoreCase = true) -> BookFormat.EPUB
            mimeType.equals("application/x-epub+zip", ignoreCase = true) -> BookFormat.EPUB
            name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
            name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
            else -> null
        }
    }

    private fun normalizeFileName(name: String, extension: String): String {
        val base = name
            .substringBeforeLast('.', name)
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "kitap" }
        return "$base.$extension"
    }

    private fun openMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }
}

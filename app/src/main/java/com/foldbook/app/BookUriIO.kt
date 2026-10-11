package com.foldbook.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

internal fun Context.openBookInputStream(uri: Uri): InputStream? {
    return if (uri.scheme.equals("file", ignoreCase = true)) {
        uri.path?.let { path ->
            runCatching { FileInputStream(File(path)) }.getOrNull()
        }
    } else {
        contentResolver.openInputStream(uri)
    }
}

internal fun Context.openBookFileDescriptor(
    uri: Uri,
    mode: String = "r"
): ParcelFileDescriptor? {
    return if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path ?: return null
        val file = File(path)
        val descriptorMode = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_READ_WRITE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        runCatching {
            ParcelFileDescriptor.open(file, descriptorMode)
        }.getOrNull()
    } else {
        contentResolver.openFileDescriptor(uri, mode)
    }
}

package com.foldbook.app

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>
)

data class EpubChapter(
    val title: String,
    val text: String
)

object EpubLoader {

    fun load(context: Context, uri: Uri): EpubBook {
        val entries = context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                val result = linkedMapOf<String, ByteArray>()

                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && shouldKeep(entry.name)) {
                        val buffer = ByteArrayOutputStream()
                        zip.copyTo(buffer)
                        result[normalizePath(entry.name)] = buffer.toByteArray()
                    }
                    zip.closeEntry()
                }

                result
            }
        } ?: error("Dosya açılamadı.")

        val container = entries["META-INF/container.xml"]
            ?: error("Geçerli bir EPUB değil: container.xml bulunamadı.")

        val packagePath = parseContainer(container)
        val packageBytes = entries[normalizePath(packagePath)]
            ?: error("EPUB paket bilgisi bulunamadı.")

        val packageInfo = parsePackage(packageBytes)
        val baseDir = packagePath.substringBeforeLast('/', "")

        val chapters = packageInfo.spine.mapNotNull { idRef ->
            val item = packageInfo.manifest[idRef] ?: return@mapNotNull null
            val chapterPath = resolvePath(baseDir, item.href)
            val bytes = entries[chapterPath] ?: return@mapNotNull null
            parseChapter(bytes, item.href)
        }.filter { it.text.isNotBlank() }

        if (chapters.isEmpty()) {
            error("EPUB içinde okunabilir bölüm bulunamadı.")
        }

        return EpubBook(
            title = packageInfo.title.ifBlank { "EPUB Kitap" },
            chapters = chapters
        )
    }

    private fun shouldKeep(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".xml") ||
            lower.endsWith(".opf") ||
            lower.endsWith(".xhtml") ||
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            lower.endsWith(".ncx")
    }

    private fun parseContainer(bytes: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            parser.next()
        }

        error("EPUB rootfile bilgisi bulunamadı.")
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String
    )

    private data class PackageInfo(
        val title: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>
    )

    private fun parsePackage(bytes: ByteArray): PackageInfo {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var title = ""
        val manifest = linkedMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> {
                        if (title.isBlank()) {
                            title = runCatching { parser.nextText().trim() }.getOrDefault("")
                        }
                    }

                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()

                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            manifest[id] = ManifestItem(href, mediaType)
                        }
                    }

                    "itemref" -> {
                        val idRef = parser.getAttributeValue(null, "idref")
                        if (!idRef.isNullOrBlank()) spine += idRef
                    }
                }
            }
            parser.next()
        }

        val fallbackSpine = if (spine.isEmpty()) {
            manifest
                .filterValues {
                    it.mediaType.contains("xhtml", ignoreCase = true) ||
                        it.href.endsWith(".html", ignoreCase = true) ||
                        it.href.endsWith(".htm", ignoreCase = true)
                }
                .keys
                .toList()
        } else {
            spine
        }

        return PackageInfo(
            title = title,
            manifest = manifest,
            spine = fallbackSpine
        )
    }

    private fun parseChapter(bytes: ByteArray, fallbackName: String): EpubChapter {
        val raw = bytes.toString(StandardCharsets.UTF_8)

        val titleHtml = Regex(
            pattern = """(?is)<(?:h1|h2|title)[^>]*>(.*?)</(?:h1|h2|title)>"""
        ).find(raw)?.groupValues?.getOrNull(1).orEmpty()

        val chapterTitle = htmlToText(titleHtml)
            .ifBlank {
                fallbackName
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
                    .replace('-', ' ')
                    .replace('_', ' ')
                    .trim()
            }

        val bodyOnly = raw
            .replace(Regex("""(?is)<head[^>]*>.*?</head>"""), " ")
            .replace(Regex("""(?is)<script[^>]*>.*?</script>"""), " ")
            .replace(Regex("""(?is)<style[^>]*>.*?</style>"""), " ")

        val text = htmlToText(bodyOnly)
            .replace("\u00A0", " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return EpubChapter(
            title = chapterTitle.ifBlank { "Bölüm" },
            text = text
        )
    }

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""

        return Html.fromHtml(
            html,
            Html.FROM_HTML_MODE_LEGACY
        ).toString().trim()
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val decoded = URLDecoder.decode(
            href.substringBefore('#'),
            StandardCharsets.UTF_8.name()
        )

        val combined = if (baseDir.isBlank()) decoded else "$baseDir/$decoded"
        return normalizePath(combined)
    }

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()

        for (part in path.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }

        return parts.joinToString("/")
    }
}

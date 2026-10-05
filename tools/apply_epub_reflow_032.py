from pathlib import Path

main_path = Path("app/src/main/java/com/foldbook/app/MainActivity.kt")
gradle_path = Path("app/build.gradle.kts")

main = main_path.read_text()

old_reader_page = """private data class ReaderPage(
    val chapter: String,
    val body: String
)"""
new_reader_page = """private data class ReaderPage(
    val chapter: String,
    val body: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int
)"""

if old_reader_page not in main:
    raise SystemExit("ReaderPage data class not found")
main = main.replace(old_reader_page, new_reader_page, 1)

start_marker = "private fun EpubBook.toReaderPages("
end_marker = "\nprivate fun buildReaderLayout("
start = main.find(start_marker)
end = main.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("EPUB paginator block not found")

new_paginator = r'''private fun EpubBook.toReaderPages(
    fontSize: ReaderFontSize,
    pageWidthPx: Int,
    pageHeightPx: Int,
    density: Float,
    scaledDensity: Float,
    anchorChapterIndex: Int = -1,
    anchorCharOffset: Int = -1
): List<ReaderPage> {
    if (pageWidthPx <= 0 || pageHeightPx <= 0) return emptyList()

    val bodySizeSp = fontSizeSp(fontSize)
    val bodyTextSizePx = bodySizeSp * scaledDensity
    val bodyLineHeightPx = (bodySizeSp + 11f) * scaledDensity

    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = bodyTextSizePx
        typeface = Typeface.SERIF
    }

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (bodySizeSp + 1.5f) * scaledDensity
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    val titleLineHeightPx = (bodySizeSp + 7f) * scaledDensity

    val contentWidthPx = (
        pageWidthPx - (64f * density)
    ).toInt().coerceAtLeast(120)

    // Compose BasicTextField ile StaticLayout font metrikleri birebir aynı değil.
    // Bir satır yetmediği için 1.75 tam satırlık güvenlik alanı bırakıyoruz.
    val bottomSafetyPx = (bodyLineHeightPx * 1.75f).toInt()

    val contentHeightPx = (
        pageHeightPx -
            (28f * density) -
            (20f * density) -
            bottomSafetyPx
    ).toInt().coerceAtLeast((bodyLineHeightPx * 4f).toInt())

    fun normalizeText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    return buildList {
        chapters.forEachIndexed { chapterIndex, chapter ->
            val normalized = normalizeText(chapter.text)
            if (normalized.isBlank()) return@forEachIndexed

            val titleHeightPx = if (chapter.title.isNotBlank()) {
                buildReaderLayout(
                    text = chapter.title,
                    paint = titlePaint,
                    widthPx = contentWidthPx,
                    targetLineHeightPx = titleLineHeightPx
                ).height + (16f * density).toInt()
            } else {
                0
            }

            fun appendSegment(
                segment: String,
                baseOffset: Int,
                showChapterTitle: Boolean
            ) {
                if (segment.isBlank()) return

                val bodyLayout = buildReaderLayout(
                    text = segment,
                    paint = bodyPaint,
                    widthPx = contentWidthPx,
                    targetLineHeightPx = bodyLineHeightPx
                )
                if (bodyLayout.lineCount <= 0) return

                var startLine = 0
                var firstSegmentPage = true

                while (startLine < bodyLayout.lineCount) {
                    val availableHeight = if (firstSegmentPage && showChapterTitle) {
                        (contentHeightPx - titleHeightPx)
                            .coerceAtLeast((bodyLineHeightPx * 3f).toInt())
                    } else {
                        contentHeightPx
                    }

                    val pageTop = bodyLayout.getLineTop(startLine)
                    var endLine = startLine

                    while (endLine + 1 < bodyLayout.lineCount) {
                        val nextBottom = bodyLayout.getLineBottom(endLine + 1)
                        if (nextBottom - pageTop > availableHeight) break
                        endLine++
                    }

                    val rawStart = bodyLayout.getLineStart(startLine)
                    val rawEnd = bodyLayout.getLineEnd(endLine)
                    val rawBody = segment.substring(rawStart, rawEnd)

                    val firstVisible = rawBody.indexOfFirst { !it.isWhitespace() }
                    val lastVisible = rawBody.indexOfLast { !it.isWhitespace() }

                    if (firstVisible >= 0 && lastVisible >= firstVisible) {
                        val body = rawBody.substring(firstVisible, lastVisible + 1)
                        add(
                            ReaderPage(
                                chapter = if (firstSegmentPage && showChapterTitle) {
                                    chapter.title
                                } else {
                                    ""
                                },
                                body = body,
                                chapterIndex = chapterIndex,
                                startOffset = baseOffset + rawStart + firstVisible,
                                endOffset = baseOffset + rawStart + lastVisible + 1
                            )
                        )
                        firstSegmentPage = false
                    }

                    startLine = endLine + 1
                }
            }

            val useAnchor =
                chapterIndex == anchorChapterIndex &&
                    anchorCharOffset > 0 &&
                    anchorCharOffset < normalized.length

            if (useAnchor) {
                // Ekran yönü değişince mevcut sayfanın ilk görünen karakterini
                // yeni düzenin de ilk karakteri yap. Böylece yatay/dikey geçişte
                // okuma noktası başka bir paragrafa kaymıyor.
                val safeAnchor = anchorCharOffset.coerceIn(1, normalized.lastIndex)
                appendSegment(
                    segment = normalized.substring(0, safeAnchor),
                    baseOffset = 0,
                    showChapterTitle = true
                )
                appendSegment(
                    segment = normalized.substring(safeAnchor),
                    baseOffset = safeAnchor,
                    showChapterTitle = false
                )
            } else {
                appendSegment(
                    segment = normalized,
                    baseOffset = 0,
                    showChapterTitle = true
                )
            }
        }
    }
}
'''

main = main[:start] + new_paginator + main[end:]

old_anchor_state = """    var currentSpreadIndex by rememberSaveable(bookKey) { mutableIntStateOf(0) }
    var ttsReadPageIndex by rememberSaveable(bookKey) { mutableIntStateOf(0) }"""
new_anchor_state = """    var currentSpreadIndex by rememberSaveable(bookKey) { mutableIntStateOf(0) }
    var currentAnchorChapter by rememberSaveable(bookKey) { mutableIntStateOf(-1) }
    var currentAnchorOffset by rememberSaveable(bookKey) { mutableIntStateOf(-1) }
    var ttsReadPageIndex by rememberSaveable(bookKey) { mutableIntStateOf(0) }"""
if old_anchor_state not in main:
    raise SystemExit("Reader anchor state insertion point not found")
main = main.replace(old_anchor_state, new_anchor_state, 1)

old_pages_block = """                val pages by produceState(
                    initialValue = emptyList<ReaderPage>(),
                    book,
                    fontSize,
                    pageWidthPx,
                    pageHeightPx,
                    density,
                    scaledDensity
                ) {
                    value = withContext(Dispatchers.Default) {
                        book.toReaderPages(
                            fontSize = fontSize,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            density = density,
                            scaledDensity = scaledDensity
                        )
                    }
                }"""

new_pages_block = """                val pages by produceState(
                    initialValue = emptyList<ReaderPage>(),
                    book,
                    fontSize,
                    pageWidthPx,
                    pageHeightPx,
                    density,
                    scaledDensity
                ) {
                    // Bu değerler özellikle key değil: normal sayfa çevirmede yeniden
                    // pagination yapma. Yalnızca ekran ölçüsü değiştiğinde producer
                    // yeniden başlar ve o andaki okuma noktasını anchor olarak alır.
                    val reflowAnchorChapter = currentAnchorChapter
                    val reflowAnchorOffset = currentAnchorOffset

                    value = withContext(Dispatchers.Default) {
                        book.toReaderPages(
                            fontSize = fontSize,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            density = density,
                            scaledDensity = scaledDensity,
                            anchorChapterIndex = reflowAnchorChapter,
                            anchorCharOffset = reflowAnchorOffset
                        )
                    }
                }"""

if old_pages_block not in main:
    raise SystemExit("produceState pages block not found")
main = main.replace(old_pages_block, new_pages_block, 1)

old_saved_page = """                    val savedPage = if (twoPage) {
                        (savedRawPage - (savedRawPage % 2)).coerceAtLeast(0)
                    } else {
                        savedRawPage
                    }

                    LaunchedEffect(bookKey, pages.size, twoPage) {
                        currentSpreadIndex = savedPage.coerceIn(0, pages.lastIndex)
                        if (!ttsActive) {
                            ttsReadPageIndex = currentSpreadIndex
                            ttsCharOffset = 0
                        }
                    }"""

new_saved_page = """                    val anchoredPage = if (
                        currentAnchorChapter >= 0 &&
                        currentAnchorOffset >= 0
                    ) {
                        val exact = pages.indexOfFirst { page ->
                            page.chapterIndex == currentAnchorChapter &&
                                page.startOffset == currentAnchorOffset
                        }

                        if (exact >= 0) {
                            exact
                        } else {
                            pages.indexOfFirst { page ->
                                page.chapterIndex == currentAnchorChapter &&
                                    currentAnchorOffset >= page.startOffset &&
                                    currentAnchorOffset < page.endOffset
                            }
                        }
                    } else {
                        -1
                    }

                    // Çift sayfada tek/çift sayıya zorlamıyoruz. Dikeyde hangi sayfa
                    // başlıyorsa Fold/yatay modda o sayfa SOL tarafta başlasın.
                    val savedPage = if (anchoredPage >= 0) {
                        anchoredPage
                    } else {
                        savedRawPage
                    }.coerceIn(0, pages.lastIndex)

                    LaunchedEffect(bookKey, pages.size, twoPage, savedPage) {
                        currentSpreadIndex = savedPage

                        pages.getOrNull(savedPage)?.let { page ->
                            currentAnchorChapter = page.chapterIndex
                            currentAnchorOffset = page.startOffset
                        }

                        if (!ttsActive) {
                            ttsReadPageIndex = currentSpreadIndex
                            ttsCharOffset = 0
                        }
                    }"""

if old_saved_page not in main:
    raise SystemExit("savedPage block not found")
main = main.replace(old_saved_page, new_saved_page, 1)

old_changed = """                                currentSpreadIndex = pageIndex

                                if (ttsActive) {"""
new_changed = """                                currentSpreadIndex = pageIndex

                                pages.getOrNull(pageIndex)?.let { page ->
                                    currentAnchorChapter = page.chapterIndex
                                    currentAnchorOffset = page.startOffset
                                }

                                if (ttsActive) {"""
if old_changed not in main:
    raise SystemExit("onPageChanged anchor insertion point not found")
main = main.replace(old_changed, new_changed, 1)

old_two_page_effect = """    LaunchedEffect(twoPage) {
        if (twoPage && pageIndex % 2 != 0) {
            pageIndex = (pageIndex - 1).coerceAtLeast(0)
        }

        dragPx = 0f
        dragProgress = 0f
        dragYFraction = 0.5f
        turnDirection = 0
    }"""

new_two_page_effect = """    LaunchedEffect(twoPage) {
        // Tek sayfadan çift sayfaya geçerken mevcut başlangıç sayfasını koru.
        // Örn. dikeyde 95. sayfadaysak yatayda 95 solda, 96 sağda açılır.
        dragPx = 0f
        dragProgress = 0f
        dragYFraction = 0.5f
        turnDirection = 0
    }"""

if old_two_page_effect not in main:
    raise SystemExit("twoPage parity block not found")
main = main.replace(old_two_page_effect, new_two_page_effect, 1)

main = main.replace('text = "v0.9.31"', 'text = "v0.9.32"', 1)
main_path.write_text(main)

gradle = gradle_path.read_text()
if 'versionCode = 42' not in gradle or 'versionName = "0.9.31"' not in gradle:
    raise SystemExit("Expected 0.9.31 Gradle version not found")
gradle = gradle.replace('versionCode = 42', 'versionCode = 43', 1)
gradle = gradle.replace('versionName = "0.9.31"', 'versionName = "0.9.32"', 1)
gradle_path.write_text(gradle)

print("Applied EPUB clipping + orientation anchor patch 0.9.32")

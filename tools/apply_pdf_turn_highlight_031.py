from pathlib import Path

pdf_path = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
main_path = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
gradle_path = Path('app/build.gradle.kts')

pdf = pdf_path.read_text()

# Pass highlights into both turning-page renderers.
pdf = pdf.replace(
'''                    theme = theme,\n                    renderWidthPx = renderWidthPx,\n                    crossSpine = true,''',
'''                    theme = theme,\n                    renderWidthPx = renderWidthPx,\n                    highlights = highlights,\n                    crossSpine = true,'''
)

pdf = pdf.replace(
'''                theme = theme,\n                renderWidthPx = renderWidthPx,\n                modifier = Modifier.fillMaxSize().zIndex(4f)\n            )''',
'''                theme = theme,\n                renderWidthPx = renderWidthPx,\n                highlights = highlights,\n                modifier = Modifier.fillMaxSize().zIndex(4f)\n            )''',
1
)

pdf = pdf.replace(
'''private fun PdfSingleTurningPage(\n    document: PdfBookDocument,\n    frontIndex: Int,\n    progress: Float,\n    direction: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    modifier: Modifier = Modifier''',
'''private fun PdfSingleTurningPage(\n    document: PdfBookDocument,\n    frontIndex: Int,\n    progress: Float,\n    direction: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    highlights: List<BookHighlight>,\n    modifier: Modifier = Modifier'''
)

pdf = pdf.replace(
'''private fun PdfTurningPage(\n    document: PdfBookDocument,\n    frontIndex: Int?,\n    backIndex: Int?,\n    progress: Float,\n    direction: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    cameraDistanceValue: Float = 30f,''',
'''private fun PdfTurningPage(\n    document: PdfBookDocument,\n    frontIndex: Int?,\n    backIndex: Int?,\n    progress: Float,\n    direction: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    highlights: List<BookHighlight>,\n    cameraDistanceValue: Float = 30f,'''
)

# Upgrade frozen-page calls so the animation layer knows about saved highlights.
pdf = pdf.replace(
'''        PdfFrozenPage(\n            bitmap = bitmap,\n            index = frontIndex,\n            totalPages = document.pageCount,\n            theme = theme,\n            modifier = Modifier.fillMaxSize()\n        )''',
'''        PdfFrozenPage(\n            document = document,\n            bitmap = bitmap,\n            index = frontIndex,\n            totalPages = document.pageCount,\n            theme = theme,\n            renderWidthPx = renderWidthPx,\n            highlights = highlights,\n            modifier = Modifier.fillMaxSize()\n        )'''
)

pdf = pdf.replace(
'''            PdfFrozenPage(\n                bitmap = if (showingBack) backBitmap else frontBitmap,\n                index = if (showingBack) backIndex else frontIndex,\n                totalPages = document.pageCount,\n                theme = theme,\n                modifier = Modifier.fillMaxSize()\n            )''',
'''            PdfFrozenPage(\n                document = document,\n                bitmap = if (showingBack) backBitmap else frontBitmap,\n                index = if (showingBack) backIndex else frontIndex,\n                totalPages = document.pageCount,\n                theme = theme,\n                renderWidthPx = renderWidthPx,\n                highlights = highlights,\n                modifier = Modifier.fillMaxSize()\n            )'''
)

old_sig = '''@Composable\nprivate fun PdfFrozenPage(\n    bitmap: Bitmap?,\n    index: Int?,\n    totalPages: Int,\n    theme: ReaderThemeOption,\n    modifier: Modifier = Modifier\n) {'''
new_sig = '''@Composable\nprivate fun PdfFrozenPage(\n    document: PdfBookDocument,\n    bitmap: Bitmap?,\n    index: Int?,\n    totalPages: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    highlights: List<BookHighlight>,\n    modifier: Modifier = Modifier\n) {'''
if old_sig not in pdf:
    raise SystemExit('PdfFrozenPage signature not found')
pdf = pdf.replace(old_sig, new_sig)

# Draw the exact same saved marker overlay inside the frozen page used during turn animation.
needle = '''            bitmap?.let {\n                Image(\n                    bitmap = it.asImageBitmap(),\n                    contentDescription = index?.let { page -> "PDF sayfa " + (page + 1) },\n                    modifier = Modifier.fillMaxSize().padding(2.dp),\n                    contentScale = ContentScale.Fit\n                )\n            }\n            if (index != null) {'''
replacement = '''            bitmap?.let {\n                Image(\n                    bitmap = it.asImageBitmap(),\n                    contentDescription = index?.let { page -> "PDF sayfa " + (page + 1) },\n                    modifier = Modifier.fillMaxSize().padding(2.dp),\n                    contentScale = ContentScale.Fit\n                )\n            }\n\n            PdfFrozenHighlightOverlay(\n                document = document,\n                bitmap = bitmap,\n                index = index,\n                theme = theme,\n                renderWidthPx = renderWidthPx,\n                highlights = highlights,\n                modifier = Modifier.fillMaxSize()\n            )\n\n            if (index != null) {'''
if needle not in pdf:
    raise SystemExit('Frozen page image block not found')
pdf = pdf.replace(needle, replacement)

helper = r'''

@Composable
private fun PdfFrozenHighlightOverlay(
    document: PdfBookDocument,
    bitmap: Bitmap?,
    index: Int?,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    highlights: List<BookHighlight>,
    modifier: Modifier = Modifier
) {
    if (bitmap == null || index == null || index !in 0 until document.pageCount) return

    val densityInfo = LocalDensity.current
    val geometry = document.cachedGeometry(index, renderWidthPx, theme)
    var boxSize by remember(index, bitmap) { mutableStateOf(IntSize.Zero) }
    val imagePaddingPx = with(densityInfo) { 2.dp.toPx() }

    val pageHighlights = remember(index, highlights) {
        highlights.filter { it.pageNumber == index + 1 }
    }

    val savedHighlightBounds by produceState(
        initialValue = emptyList<RectF>(),
        key1 = index,
        key2 = pageHighlights.hashCode(),
        key3 = document.supportsTextSelection
    ) {
        value = if (document.supportsTextSelection && pageHighlights.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                pageHighlights.flatMap { highlight ->
                    if (highlight.bounds.isNotEmpty()) {
                        highlight.bounds.map { bound ->
                            RectF(bound.left, bound.top, bound.right, bound.bottom)
                        }
                    } else {
                        document.selectByIndices(
                            index = index,
                            startIndex = highlight.startOffset,
                            endIndex = highlight.endOffset
                        )?.bounds.orEmpty()
                    }
                }
            }
        } else {
            emptyList()
        }
    }

    fun imageMetrics(): FloatArray? {
        if (boxSize.width <= 0 || boxSize.height <= 0) return null
        val innerWidth = (boxSize.width - imagePaddingPx * 2f).coerceAtLeast(1f)
        val innerHeight = (boxSize.height - imagePaddingPx * 2f).coerceAtLeast(1f)
        val scale = min(
            innerWidth / bitmap.width.toFloat(),
            innerHeight / bitmap.height.toFloat()
        ).coerceAtLeast(0.0001f)
        val shownWidth = bitmap.width * scale
        val shownHeight = bitmap.height * scale
        val left = (boxSize.width - shownWidth) / 2f
        val top = (boxSize.height - shownHeight) / 2f
        return floatArrayOf(scale, left, top)
    }

    fun toDisplayRect(rect: RectF): RectF? {
        val g = geometry ?: return null
        val m = imageMetrics() ?: return null
        val scale = m[0]
        val imageLeft = m[1]
        val imageTop = m[2]
        fun x(value: Float): Float {
            val source = value / g.pageWidthPoints * g.sourceWidthPx
            return imageLeft + (source - g.cropLeftPx) * scale
        }
        fun y(value: Float): Float {
            val source = value / g.pageHeightPoints * g.sourceHeightPx
            return imageTop + (source - g.cropTopPx) * scale
        }
        return RectF(x(rect.left), y(rect.top), x(rect.right), y(rect.bottom))
    }

    Box(modifier = modifier.onSizeChanged { boxSize = it }) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val markerColor = when (theme) {
                ReaderThemeOption.LIGHT -> Color(0xFFDFFF3F).copy(alpha = 0.60f)
                ReaderThemeOption.SEPIA -> Color(0xFFD8F23B).copy(alpha = 0.54f)
                ReaderThemeOption.DARK -> Color(0xFFC8FF3D).copy(alpha = 0.42f)
            }
            val markerSheen = when (theme) {
                ReaderThemeOption.LIGHT -> Color(0xFFE9FF64).copy(alpha = 0.24f)
                ReaderThemeOption.SEPIA -> Color(0xFFE6F75C).copy(alpha = 0.20f)
                ReaderThemeOption.DARK -> Color(0xFFD9FF65).copy(alpha = 0.16f)
            }
            val markerBleed = 1.8.dp.toPx()
            val markerRadius = 2.8.dp.toPx()

            savedHighlightBounds.forEach { rect ->
                toDisplayRect(rect)?.let { shown ->
                    val lineHeight = (shown.bottom - shown.top).coerceAtLeast(1f)
                    val markerTop = shown.top + lineHeight * 0.04f
                    val markerBottom = shown.bottom - lineHeight * 0.02f
                    val markerHeight = (markerBottom - markerTop).coerceAtLeast(1f)
                    val markerWidth =
                        (shown.right - shown.left + markerBleed * 2f).coerceAtLeast(1f)

                    drawRoundRect(
                        color = markerColor,
                        topLeft = Offset(shown.left - markerBleed, markerTop),
                        size = Size(markerWidth, markerHeight),
                        cornerRadius = CornerRadius(markerRadius, markerRadius)
                    )
                    drawRoundRect(
                        color = markerSheen,
                        topLeft = Offset(
                            shown.left - markerBleed * 0.45f,
                            markerTop + lineHeight * 0.17f
                        ),
                        size = Size(
                            (shown.right - shown.left + markerBleed * 0.9f)
                                .coerceAtLeast(1f),
                            (markerHeight * 0.56f).coerceAtLeast(1f)
                        ),
                        cornerRadius = CornerRadius(markerRadius, markerRadius)
                    )
                }
            }
        }
    }
}
'''

insert_before = '\n@Composable\nprivate fun PdfFrozenPage('
if insert_before not in pdf:
    raise SystemExit('PdfFrozenPage insertion point missing')
pdf = pdf.replace(insert_before, helper + insert_before, 1)

pdf_path.write_text(pdf)

main = main_path.read_text()
main = main.replace('text = "v0.9.30"', 'text = "v0.9.31"')
main_path.write_text(main)

gradle = gradle_path.read_text()
gradle = gradle.replace('versionCode = 41', 'versionCode = 42')
gradle = gradle.replace('versionName = "0.9.30"', 'versionName = "0.9.31"')
gradle_path.write_text(gradle)

print('Applied PDF turning highlight continuity patch 0.9.31')

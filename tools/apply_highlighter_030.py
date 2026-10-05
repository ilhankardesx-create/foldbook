from pathlib import Path

root = Path('.')
lib_path = root / 'app/src/main/java/com/foldbook/app/LibraryStore.kt'
pdf_path = root / 'app/src/main/java/com/foldbook/app/PdfReader.kt'
main_path = root / 'app/src/main/java/com/foldbook/app/MainActivity.kt'
build_path = root / 'app/build.gradle.kts'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing pattern: {label}')
    return text.replace(old, new, 1)

# -----------------------------------------------------------------------------
# Persistent highlight geometry. EPUB keeps using text offsets; PDF additionally
# stores native page-space rectangles so a highlight survives theme/render/cache
# changes and can be redrawn exactly where the user marked it.
# -----------------------------------------------------------------------------
lib = lib_path.read_text(encoding='utf-8')

lib = replace_once(
    lib,
    '''data class BookHighlight(\n    val id: String,\n    val bookUri: String,\n    val pageNumber: Int,\n    val startOffset: Int,\n    val endOffset: Int,\n    val text: String,\n    val createdAt: Long\n)''',
    '''data class HighlightBox(\n    val left: Float,\n    val top: Float,\n    val right: Float,\n    val bottom: Float\n)\n\ndata class BookHighlight(\n    val id: String,\n    val bookUri: String,\n    val pageNumber: Int,\n    val startOffset: Int,\n    val endOffset: Int,\n    val text: String,\n    val bounds: List<HighlightBox> = emptyList(),\n    val createdAt: Long\n)''',
    'BookHighlight model'
)

lib = replace_once(
    lib,
    '''        endOffset: Int,\n        text: String\n    ) {''',
    '''        endOffset: Int,\n        text: String,\n        bounds: List<HighlightBox> = emptyList()\n    ) {''',
    'addHighlight signature'
)

lib = replace_once(
    lib,
    '''                endOffset = endOffset.coerceAtLeast(startOffset + 1),\n                text = cleanText,\n                createdAt = now''',
    '''                endOffset = endOffset.coerceAtLeast(startOffset + 1),\n                text = cleanText,\n                bounds = bounds,\n                createdAt = now''',
    'addHighlight constructor'
)

lib = replace_once(
    lib,
    '''                    if (text.isBlank() || start < 0 || end <= start) continue\n\n                    add(\n                        BookHighlight(''',
    '''                    if (text.isBlank() || start < 0 || end <= start) continue\n\n                    val savedBounds = buildList {\n                        val boundsArray = item.optJSONArray("bounds")\n                        if (boundsArray != null) {\n                            for (boundIndex in 0 until boundsArray.length()) {\n                                val bound = boundsArray.optJSONObject(boundIndex) ?: continue\n                                val left = bound.optDouble("left", Double.NaN)\n                                val top = bound.optDouble("top", Double.NaN)\n                                val right = bound.optDouble("right", Double.NaN)\n                                val bottom = bound.optDouble("bottom", Double.NaN)\n                                if (\n                                    left.isFinite() && top.isFinite() &&\n                                    right.isFinite() && bottom.isFinite() &&\n                                    right > left && bottom > top\n                                ) {\n                                    add(\n                                        HighlightBox(\n                                            left = left.toFloat(),\n                                            top = top.toFloat(),\n                                            right = right.toFloat(),\n                                            bottom = bottom.toFloat()\n                                        )\n                                    )\n                                }\n                            }\n                        }\n                    }\n\n                    add(\n                        BookHighlight(''',
    'highlight bounds parse'
)

lib = replace_once(
    lib,
    '''                            endOffset = end,\n                            text = text,\n                            createdAt = item.optLong("createdAt", 0L)''',
    '''                            endOffset = end,\n                            text = text,\n                            bounds = savedBounds,\n                            createdAt = item.optLong("createdAt", 0L)''',
    'highlight bounds constructor parse'
)

lib = replace_once(
    lib,
    '''                    .put("endOffset", highlight.endOffset)\n                    .put("text", highlight.text)\n                    .put("createdAt", highlight.createdAt)''',
    '''                    .put("endOffset", highlight.endOffset)\n                    .put("text", highlight.text)\n                    .put(\n                        "bounds",\n                        JSONArray().apply {\n                            highlight.bounds.forEach { bound ->\n                                put(\n                                    JSONObject()\n                                        .put("left", bound.left.toDouble())\n                                        .put("top", bound.top.toDouble())\n                                        .put("right", bound.right.toDouble())\n                                        .put("bottom", bound.bottom.toDouble())\n                                )\n                            }\n                        }\n                    )\n                    .put("createdAt", highlight.createdAt)''',
    'highlight bounds save'
)

lib_path.write_text(lib, encoding='utf-8')

# -----------------------------------------------------------------------------
# PDF: save exact selection rectangles and render them as a fluorescent marker
# stroke rather than a plain opaque rectangle.
# -----------------------------------------------------------------------------
pdf = pdf_path.read_text(encoding='utf-8')

pdf = replace_once(
    pdf,
    'import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.geometry.Size\n',
    'import androidx.compose.ui.geometry.CornerRadius\nimport androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.geometry.Size\n',
    'CornerRadius import'
)

pdf = replace_once(
    pdf,
    '''                pageHighlights.flatMap { highlight ->\n                    document.selectByIndices(\n                        index = index,\n                        startIndex = highlight.startOffset,\n                        endIndex = highlight.endOffset\n                    )?.bounds.orEmpty()\n                }''',
    '''                pageHighlights.flatMap { highlight ->\n                    if (highlight.bounds.isNotEmpty()) {\n                        highlight.bounds.map { bound ->\n                            RectF(bound.left, bound.top, bound.right, bound.bottom)\n                        }\n                    } else {\n                        // Eski kayıtlar için geriye dönük destek.\n                        document.selectByIndices(\n                            index = index,\n                            startIndex = highlight.startOffset,\n                            endIndex = highlight.endOffset\n                        )?.bounds.orEmpty()\n                    }\n                }''',
    'PDF saved highlight geometry'
)

pdf = replace_once(
    pdf,
    '''                                endOffset = chosen.endIndex,\n                                text = chosen.text\n                            )''',
    '''                                endOffset = chosen.endIndex,\n                                text = chosen.text,\n                                bounds = chosen.bounds.map { rect ->\n                                    HighlightBox(\n                                        left = rect.left,\n                                        top = rect.top,\n                                        right = rect.right,\n                                        bottom = rect.bottom\n                                    )\n                                }\n                            )''',
    'PDF persist selected bounds'
)

pdf = replace_once(
    pdf,
    '''                savedHighlightBounds.forEach { rect ->\n                    toDisplayRect(rect)?.let { shown ->\n                        drawRect(\n                            color = Color(0xFFFFE45C).copy(alpha = 0.46f),\n                            topLeft = Offset(shown.left, shown.top),\n                            size = Size(\n                                (shown.right - shown.left).coerceAtLeast(1f),\n                                (shown.bottom - shown.top).coerceAtLeast(1f)\n                            )\n                        )\n                    }\n                }''',
    '''                val markerColor = when (theme) {\n                    ReaderThemeOption.LIGHT -> Color(0xFFDFFF3F).copy(alpha = 0.60f)\n                    ReaderThemeOption.SEPIA -> Color(0xFFD8F23B).copy(alpha = 0.54f)\n                    ReaderThemeOption.DARK -> Color(0xFFC8FF3D).copy(alpha = 0.42f)\n                }\n                val markerSheen = when (theme) {\n                    ReaderThemeOption.LIGHT -> Color(0xFFE9FF64).copy(alpha = 0.24f)\n                    ReaderThemeOption.SEPIA -> Color(0xFFE6F75C).copy(alpha = 0.20f)\n                    ReaderThemeOption.DARK -> Color(0xFFD9FF65).copy(alpha = 0.16f)\n                }\n                val markerBleed = 1.8.dp.toPx()\n                val markerRadius = 2.8.dp.toPx()\n\n                savedHighlightBounds.forEach { rect ->\n                    toDisplayRect(rect)?.let { shown ->\n                        val lineHeight = (shown.bottom - shown.top).coerceAtLeast(1f)\n                        val markerTop = shown.top + lineHeight * 0.04f\n                        val markerBottom = shown.bottom - lineHeight * 0.02f\n                        val markerHeight = (markerBottom - markerTop).coerceAtLeast(1f)\n                        val markerWidth =\n                            (shown.right - shown.left + markerBleed * 2f).coerceAtLeast(1f)\n\n                        // İki yarı saydam katman gerçek fosforlu kalem izindeki\n                        // yoğunluk farkını taklit ediyor.\n                        drawRoundRect(\n                            color = markerColor,\n                            topLeft = Offset(shown.left - markerBleed, markerTop),\n                            size = Size(markerWidth, markerHeight),\n                            cornerRadius = CornerRadius(markerRadius, markerRadius)\n                        )\n                        drawRoundRect(\n                            color = markerSheen,\n                            topLeft = Offset(\n                                shown.left - markerBleed * 0.45f,\n                                markerTop + lineHeight * 0.17f\n                            ),\n                            size = Size(\n                                (shown.right - shown.left + markerBleed * 0.9f)\n                                    .coerceAtLeast(1f),\n                                (markerHeight * 0.56f).coerceAtLeast(1f)\n                            ),\n                            cornerRadius = CornerRadius(markerRadius, markerRadius)\n                        )\n                    }\n                }''',
    'PDF fluorescent marker render'
)

pdf_path.write_text(pdf, encoding='utf-8')

# -----------------------------------------------------------------------------
# EPUB: same fluorescent yellow-green visual language. Compose's span background
# naturally breaks at wrapped lines, which matches a real marker dragged over
# individual text lines.
# -----------------------------------------------------------------------------
main = main_path.read_text(encoding='utf-8')
main = replace_once(
    main,
    'background = Color(0xFFFFE45C).copy(alpha = 0.58f)',
    'background = Color(0xFFDFFF3F).copy(alpha = 0.62f)',
    'EPUB fluorescent highlight color'
)
main = replace_once(main, 'text = "v0.9.29"', 'text = "v0.9.30"', 'visible version')
main_path.write_text(main, encoding='utf-8')

# Version bump
build = build_path.read_text(encoding='utf-8')
build = replace_once(build, 'versionCode = 40', 'versionCode = 41', 'versionCode')
build = replace_once(build, 'versionName = "0.9.29"', 'versionName = "0.9.30"', 'versionName')
build_path.write_text(build, encoding='utf-8')

print('FoldBook 0.9.30 fluorescent highlighter patch applied.')

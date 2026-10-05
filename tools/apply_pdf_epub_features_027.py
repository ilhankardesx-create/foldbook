from pathlib import Path

pdf_path = Path("app/src/main/java/com/foldbook/app/PdfReader.kt")
build_path = Path("app/build.gradle.kts")
main_path = Path("app/src/main/java/com/foldbook/app/MainActivity.kt")

pdf = pdf_path.read_text(encoding="utf-8")

# Imports
repls = {
    "import android.graphics.Paint\n": "import android.graphics.Paint\nimport android.graphics.Point\nimport android.graphics.RectF\n",
    "import android.net.Uri\n": "import android.net.Uri\nimport android.os.Build\n",
    "import android.os.ParcelFileDescriptor\n": "import android.os.ParcelFileDescriptor\nimport android.speech.tts.TextToSpeech\nimport android.speech.tts.UtteranceProgressListener\nimport android.widget.Toast\n",
    "import androidx.activity.compose.BackHandler\n": "import androidx.activity.ComponentActivity\nimport androidx.activity.compose.BackHandler\n",
    "import androidx.compose.foundation.Image\n": "import androidx.compose.foundation.Image\nimport androidx.compose.foundation.Canvas as ComposeCanvas\n",
    "import androidx.compose.foundation.gestures.detectHorizontalDragGestures\n": "import androidx.compose.foundation.gestures.detectHorizontalDragGestures\nimport androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress\n",
    "import androidx.compose.foundation.layout.BoxWithConstraints\n": "import androidx.compose.foundation.layout.BoxWithConstraints\nimport androidx.compose.foundation.layout.Column\n",
    "import androidx.compose.foundation.layout.Row\n": "import androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.fillMaxWidth\n",
    "import androidx.compose.material3.Button\n": "import androidx.compose.material3.Button\nimport androidx.compose.material3.ButtonDefaults\n",
    "import androidx.compose.ui.Alignment\n": "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.geometry.Size\n",
    "import androidx.compose.ui.layout.ContentScale\n": "import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.unit.IntSize\n",
    "import java.io.Closeable\n": "import java.io.Closeable\nimport java.util.Locale\n",
    "import kotlin.math.min\n": "import kotlin.math.min\nimport kotlin.math.roundToInt\n",
}
for old, new in repls.items():
    if old not in pdf:
        raise SystemExit(f"import pattern missing: {old!r}")
    pdf = pdf.replace(old, new, 1)

# Data models inserted before document class.
marker = "private class PdfBookDocument(\n"
models = '''private data class PdfCropResult(\n    val bitmap: Bitmap,\n    val left: Int,\n    val top: Int,\n    val sourceWidth: Int,\n    val sourceHeight: Int\n)\n\nprivate data class PdfPageGeometry(\n    val sourceWidthPx: Int,\n    val sourceHeightPx: Int,\n    val cropLeftPx: Int,\n    val cropTopPx: Int,\n    val cropWidthPx: Int,\n    val cropHeightPx: Int,\n    val pageWidthPoints: Int,\n    val pageHeightPoints: Int\n)\n\nprivate data class PdfSelectionSnapshot(\n    val text: String,\n    val startIndex: Int,\n    val endIndex: Int,\n    val bounds: List<RectF>\n)\n\n'''
if marker not in pdf:
    raise SystemExit("PdfBookDocument marker missing")
pdf = pdf.replace(marker, models + marker, 1)

# Replace the whole PDF document implementation so rendering geometry, text extraction
# and native selection all use the same synchronized PdfRenderer instance.
start = pdf.index("private class PdfBookDocument(\n")
end = pdf.index("fun loadPdfCover", start)
new_document = r'''private class PdfBookDocument(
    context: Context,
    uri: Uri
) : Closeable {
    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("PDF açılamadı.")
    private val renderer = PdfRenderer(descriptor)

    private val pageCache = object : LruCache<String, Bitmap>(96 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }
    private val geometryCache = mutableMapOf<String, PdfPageGeometry>()
    private val textCache = mutableMapOf<Int, String>()

    val pageCount: Int
        get() = renderer.pageCount

    val supportsTextSelection: Boolean
        get() = Build.VERSION.SDK_INT >= 35

    private fun normalizedRenderWidth(targetWidth: Int): Int {
        return ((targetWidth.coerceIn(720, 2400) / 64) * 64)
            .coerceAtLeast(720)
    }

    private fun cacheKey(
        index: Int,
        targetWidth: Int,
        theme: ReaderThemeOption
    ): String {
        val safeIndex = index.coerceIn(0, pageCount - 1)
        return "$safeIndex:${normalizedRenderWidth(targetWidth)}:${theme.name}"
    }

    @Synchronized
    fun cachedPage(
        index: Int,
        targetWidth: Int,
        theme: ReaderThemeOption = ReaderThemeOption.LIGHT
    ): Bitmap? {
        if (pageCount <= 0) return null
        return pageCache.get(cacheKey(index, targetWidth, theme))
    }

    @Synchronized
    fun cachedGeometry(
        index: Int,
        targetWidth: Int,
        theme: ReaderThemeOption = ReaderThemeOption.LIGHT
    ): PdfPageGeometry? {
        if (pageCount <= 0) return null
        return geometryCache[cacheKey(index, targetWidth, theme)]
    }

    @Synchronized
    fun renderPage(
        index: Int,
        targetWidth: Int,
        theme: ReaderThemeOption = ReaderThemeOption.LIGHT
    ): Bitmap {
        val safeIndex = index.coerceIn(0, pageCount - 1)
        val renderWidth = normalizedRenderWidth(targetWidth)
        val key = cacheKey(safeIndex, targetWidth, theme)

        pageCache.get(key)?.let { return it }

        var pageWidthPoints = 1
        var pageHeightPoints = 1
        val rendered = renderer.openPage(safeIndex).use { page ->
            pageWidthPoints = page.width.coerceAtLeast(1)
            pageHeightPoints = page.height.coerceAtLeast(1)
            val ratio = page.height.toFloat() / page.width.toFloat()
            val height = (renderWidth * ratio).toInt().coerceAtLeast(1)

            Bitmap.createBitmap(
                renderWidth,
                height,
                Bitmap.Config.ARGB_8888
            ).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
            }
        }

        val crop = cropWhiteMargins(rendered)
        val themed = applyTheme(crop.bitmap, theme)

        geometryCache[key] = PdfPageGeometry(
            sourceWidthPx = crop.sourceWidth,
            sourceHeightPx = crop.sourceHeight,
            cropLeftPx = crop.left,
            cropTopPx = crop.top,
            cropWidthPx = themed.width,
            cropHeightPx = themed.height,
            pageWidthPoints = pageWidthPoints,
            pageHeightPoints = pageHeightPoints
        )
        pageCache.put(key, themed)
        return themed
    }

    @Suppress("NewApi")
    @Synchronized
    fun pageText(index: Int): String {
        if (!supportsTextSelection || pageCount <= 0) return ""
        val safeIndex = index.coerceIn(0, pageCount - 1)
        textCache[safeIndex]?.let { return it }

        val text = renderer.openPage(safeIndex).use { page ->
            page.textContents
                .joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        textCache[safeIndex] = text
        return text
    }

    @Suppress("NewApi")
    @Synchronized
    fun selectByPoints(
        index: Int,
        start: Point,
        stop: Point
    ): PdfSelectionSnapshot? {
        if (!supportsTextSelection || pageCount <= 0) return null
        val safeIndex = index.coerceIn(0, pageCount - 1)

        return renderer.openPage(safeIndex).use { page ->
            val result = page.selectContent(
                android.graphics.pdf.models.selection.SelectionBoundary(start),
                android.graphics.pdf.models.selection.SelectionBoundary(stop)
            ) ?: return@use null

            val selected = result.selectedTextContents
            val text = selected
                .joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isBlank()) return@use null

            val startIndex = result.start.index.coerceAtLeast(0)
            val rawStop = result.stop.index
            val endIndex = if (rawStop > startIndex) rawStop else startIndex + 1
            PdfSelectionSnapshot(
                text = text,
                startIndex = startIndex,
                endIndex = endIndex,
                bounds = selected.flatMap { content ->
                    content.bounds.map { RectF(it) }
                }
            )
        }
    }

    @Suppress("NewApi")
    @Synchronized
    fun selectByIndices(
        index: Int,
        startIndex: Int,
        endIndex: Int
    ): PdfSelectionSnapshot? {
        if (!supportsTextSelection || pageCount <= 0) return null
        val safeIndex = index.coerceIn(0, pageCount - 1)
        val safeStart = startIndex.coerceAtLeast(0)
        val safeEnd = endIndex.coerceAtLeast(safeStart + 1)

        return runCatching {
            renderer.openPage(safeIndex).use { page ->
                val result = page.selectContent(
                    android.graphics.pdf.models.selection.SelectionBoundary(safeStart),
                    android.graphics.pdf.models.selection.SelectionBoundary(safeEnd)
                ) ?: return@use null

                val selected = result.selectedTextContents
                val text = selected
                    .joinToString(" ") { it.text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (text.isBlank()) return@use null

                PdfSelectionSnapshot(
                    text = text,
                    startIndex = result.start.index.coerceAtLeast(0),
                    endIndex = result.stop.index.coerceAtLeast(result.start.index + 1),
                    bounds = selected.flatMap { content ->
                        content.bounds.map { RectF(it) }
                    }
                )
            }
        }.getOrNull()
    }

    private fun cropWhiteMargins(source: Bitmap): PdfCropResult {
        val width = source.width
        val height = source.height
        fun original() = PdfCropResult(source, 0, 0, width, height)
        if (width < 80 || height < 80) return original()

        val sampleStep = (min(width, height) / 360).coerceAtLeast(2)
        var left = width
        var top = height
        var right = -1
        var bottom = -1

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = source.getPixel(x, y)
                val alpha = android.graphics.Color.alpha(pixel)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)

                val nearWhite = red >= 244 && green >= 244 && blue >= 244
                if (alpha > 20 && !nearWhite) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                x += sampleStep
            }
            y += sampleStep
        }

        if (right < left || bottom < top) return original()

        val contentWidth = right - left + 1
        val contentHeight = bottom - top + 1
        if (contentWidth < width * 0.16f || contentHeight < height * 0.16f) {
            return original()
        }

        val padX = (contentWidth * 0.025f).toInt().coerceAtLeast(sampleStep * 2)
        val padY = (contentHeight * 0.018f).toInt().coerceAtLeast(sampleStep * 2)
        val cropLeft = (left - padX).coerceAtLeast(0)
        val cropTop = (top - padY).coerceAtLeast(0)
        val cropRight = (right + padX).coerceAtMost(width - 1)
        val cropBottom = (bottom + padY).coerceAtMost(height - 1)
        val cropWidth = cropRight - cropLeft + 1
        val cropHeight = cropBottom - cropTop + 1

        val removesUsefulMargin =
            cropWidth < width * 0.97f || cropHeight < height * 0.97f
        if (!removesUsefulMargin) return original()

        val cropped = Bitmap.createBitmap(
            source,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight
        )
        if (cropped !== source) source.recycle()
        return PdfCropResult(cropped, cropLeft, cropTop, width, height)
    }

    private fun applyTheme(
        source: Bitmap,
        theme: ReaderThemeOption
    ): Bitmap {
        when (theme) {
            ReaderThemeOption.LIGHT -> return source
            ReaderThemeOption.SEPIA -> {
                Canvas(source).drawColor(
                    android.graphics.Color.argb(34, 214, 168, 96)
                )
                return source
            }
            ReaderThemeOption.DARK -> {
                val output = Bitmap.createBitmap(
                    source.width,
                    source.height,
                    Bitmap.Config.ARGB_8888
                )
                val matrix = ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(matrix)
                }
                Canvas(output).drawBitmap(source, 0f, 0f, paint)
                source.recycle()
                return output
            }
        }
    }

    override fun close() {
        pageCache.evictAll()
        geometryCache.clear()
        textCache.clear()
        renderer.close()
        descriptor.close()
    }
}

'''
pdf = pdf[:start] + new_document + pdf[end:]

# Replace PDF reader screen with EPUB-equivalent TTS controls and highlight state.
start = pdf.index("@Composable\nfun PdfReaderScreen(")
end = pdf.index("@Composable\nprivate fun PdfSpread(", start)
new_screen = r'''@Composable
fun PdfReaderScreen(
    bookKey: String,
    hasSeparatingVerticalHinge: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val document = remember(bookKey) {
        PdfBookDocument(context, Uri.parse(bookKey))
    }
    val bookTitle = remember(bookKey) {
        LibraryStore.readActiveBook(context)
            ?.takeIf { it.uri == bookKey }
            ?.title
            ?.ifBlank { "Kitap" }
            ?: "Kitap"
    }

    DisposableEffect(document) {
        onDispose { document.close() }
    }

    val initialPage = remember(bookKey, document.pageCount) {
        LibraryStore.readProgress(
            context = context,
            bookUri = bookKey,
            lastPageIndex = document.pageCount - 1
        )
    }

    var controlsVisible by rememberSaveable(bookKey) { mutableStateOf(false) }
    var highlights by remember(bookKey) {
        mutableStateOf(LibraryStore.readHighlights(context, bookKey))
    }
    var themeName by rememberSaveable(bookKey) {
        mutableStateOf(LibraryStore.readReaderTheme(context).name)
    }
    val theme = runCatching {
        ReaderThemeOption.valueOf(themeName)
    }.getOrDefault(ReaderThemeOption.LIGHT)
    val backgroundColor = when (theme) {
        ReaderThemeOption.LIGHT -> Color(0xFFE8DFD0)
        ReaderThemeOption.SEPIA -> Color(0xFFC9B38E)
        ReaderThemeOption.DARK -> Color(0xFF111111)
    }

    var ttsReady by remember { mutableStateOf(false) }
    var ttsActive by rememberSaveable(bookKey) { mutableStateOf(false) }
    var ttsPaused by rememberSaveable(bookKey) { mutableStateOf(false) }
    var ttsRate by rememberSaveable(bookKey) { mutableFloatStateOf(1.0f) }
    var ttsMessage by remember { mutableStateOf<String?>(null) }
    var currentSpreadIndex by rememberSaveable(bookKey) { mutableIntStateOf(initialPage) }
    var ttsReadPageIndex by rememberSaveable(bookKey) { mutableIntStateOf(initialPage) }
    var ttsCharOffset by rememberSaveable(bookKey) { mutableIntStateOf(0) }
    var ttsSpeakBaseOffset by remember { mutableIntStateOf(0) }
    var speakRequestToken by remember { mutableIntStateOf(0) }
    var utteranceDoneToken by remember { mutableIntStateOf(0) }
    var autoForwardToken by remember { mutableIntStateOf(0) }
    var utteranceSerial by remember { mutableIntStateOf(0) }

    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            activity.runOnUiThread {
                ttsReady = status == TextToSpeech.SUCCESS
                ttsMessage = if (ttsReady) null else "Yerel ses motoru başlatılamadı."
            }
        }
    }

    DisposableEffect(tts) {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    activity.runOnUiThread {
                        if (ttsActive && !ttsPaused) {
                            ttsCharOffset = 0
                            utteranceDoneToken++
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    activity.runOnUiThread {
                        ttsMessage = "Sesli okuma sırasında bir hata oluştu."
                        ttsActive = false
                        ttsPaused = false
                    }
                }
                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int
                ) {
                    activity.runOnUiThread {
                        if (ttsActive && !ttsPaused) {
                            ttsCharOffset = (ttsSpeakBaseOffset + start).coerceAtLeast(0)
                        }
                    }
                }
            }
        )
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    BackHandler {
        tts.stop()
        onBack()
    }

    LaunchedEffect(ttsReady, ttsRate) {
        if (ttsReady) {
            val localeResult = tts.setLanguage(Locale.getDefault())
            if (
                localeResult == TextToSpeech.LANG_MISSING_DATA ||
                localeResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.setLanguage(Locale("tr", "TR"))
            }
            tts.setSpeechRate(ttsRate)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        ) {
            val twoPage = hasSeparatingVerticalHinge || maxWidth >= 700.dp
            val savedPage = if (twoPage) {
                (initialPage - (initialPage % 2)).coerceAtLeast(0)
            } else {
                initialPage
            }.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))

            LaunchedEffect(bookKey, twoPage, document.pageCount) {
                currentSpreadIndex = savedPage
                if (!ttsActive) {
                    ttsReadPageIndex = savedPage
                    ttsCharOffset = 0
                }
            }

            LaunchedEffect(
                speakRequestToken,
                ttsReady,
                ttsActive,
                ttsPaused,
                ttsRate
            ) {
                if (
                    speakRequestToken > 0 &&
                    ttsReady &&
                    ttsActive &&
                    !ttsPaused
                ) {
                    val fullText = withContext(Dispatchers.IO) {
                        document.pageText(ttsReadPageIndex)
                    }
                    if (fullText.isBlank()) {
                        tts.stop()
                        ttsActive = false
                        ttsPaused = false
                        ttsMessage = if (document.supportsTextSelection) {
                            "Bu PDF sayfasında seçilebilir/okunabilir metin yok."
                        } else {
                            "PDF metin seçimi ve sesli okuma için Android 15 veya üzeri gerekiyor."
                        }
                    } else {
                        val safeOffset = ttsCharOffset
                            .coerceIn(0, (fullText.length - 1).coerceAtLeast(0))
                        ttsSpeakBaseOffset = safeOffset
                        utteranceSerial++
                        tts.setSpeechRate(ttsRate)
                        tts.speak(
                            fullText.substring(safeOffset),
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "foldbook-pdf-${ttsReadPageIndex}-$utteranceSerial"
                        )
                    }
                }
            }

            LaunchedEffect(utteranceDoneToken) {
                if (
                    utteranceDoneToken <= 0 ||
                    !ttsActive ||
                    ttsPaused
                ) return@LaunchedEffect

                val rightPageIndex = if (twoPage) {
                    currentSpreadIndex + 1
                } else {
                    currentSpreadIndex
                }

                if (
                    twoPage &&
                    ttsReadPageIndex < rightPageIndex &&
                    ttsReadPageIndex + 1 < document.pageCount
                ) {
                    ttsReadPageIndex++
                    ttsCharOffset = 0
                    speakRequestToken++
                } else {
                    val step = if (twoPage) 2 else 1
                    if (currentSpreadIndex + step < document.pageCount) {
                        autoForwardToken++
                    } else {
                        tts.stop()
                        ttsActive = false
                        ttsPaused = false
                        ttsCharOffset = 0
                        ttsMessage = "Kitabın sonuna geldin."
                    }
                }
            }

            PdfSpread(
                document = document,
                bookKey = bookKey,
                bookTitle = bookTitle,
                initialPage = savedPage,
                twoPage = twoPage,
                theme = theme,
                highlights = highlights,
                onHighlightsChanged = {
                    highlights = LibraryStore.readHighlights(context, bookKey)
                },
                autoForwardToken = autoForwardToken,
                onPageChanged = { page ->
                    LibraryStore.saveProgress(
                        context = context,
                        bookUri = bookKey,
                        pageIndex = page
                    )
                    currentSpreadIndex = page
                    if (ttsActive) {
                        tts.stop()
                        ttsReadPageIndex = page
                        ttsCharOffset = 0
                        if (!ttsPaused) speakRequestToken++
                    }
                },
                onSingleTap = {
                    controlsVisible = !controlsVisible
                },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .zIndex(20f)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.76f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    tts.stop()
                                    ttsActive = false
                                    ttsPaused = false
                                    onBack()
                                }
                            ) { Text("Rafa Dön") }

                            when {
                                !ttsReady -> Button(onClick = {}, enabled = false) {
                                    Text("🔊 Hazırlanıyor")
                                }
                                !ttsActive -> Button(onClick = {
                                    ttsMessage = null
                                    ttsActive = true
                                    ttsPaused = false
                                    ttsReadPageIndex = currentSpreadIndex
                                    ttsCharOffset = 0
                                    speakRequestToken++
                                }) { Text("🔊 Sesli Oku") }
                                !ttsPaused -> Button(onClick = {
                                    tts.stop()
                                    ttsPaused = true
                                }) { Text("⏸ Duraklat") }
                                else -> Button(onClick = {
                                    ttsPaused = false
                                    speakRequestToken++
                                }) { Text("▶ Devam") }
                            }

                            if (ttsActive) {
                                Button(onClick = {
                                    tts.stop()
                                    ttsActive = false
                                    ttsPaused = false
                                    ttsCharOffset = 0
                                }) { Text("⏹ Durdur") }
                            }

                            Button(onClick = {
                                ttsRate = when (ttsRate) {
                                    0.8f -> 1.0f
                                    1.0f -> 1.2f
                                    1.2f -> 1.5f
                                    else -> 0.8f
                                }
                                if (ttsActive && !ttsPaused) {
                                    tts.stop()
                                    speakRequestToken++
                                }
                            }) { Text("${ttsRate}x") }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                ReaderThemeOption.LIGHT to "Açık",
                                ReaderThemeOption.SEPIA to "Sepya",
                                ReaderThemeOption.DARK to "Koyu"
                            ).forEach { (option, label) ->
                                Button(onClick = {
                                    themeName = option.name
                                    LibraryStore.saveReaderTheme(context, option)
                                }) { Text(label) }
                            }
                        }

                        ttsMessage?.let { message ->
                            Text(
                                text = message,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                    }
                }
            }
        }
    }
}

'''
pdf = pdf[:start] + new_screen + pdf[end:]

# Replace spread motor; retain 0.9.26 Fold handoff and add auto-forward for TTS.
start = pdf.index("@Composable\nprivate fun PdfSpread(")
end = pdf.index("@Composable\nprivate fun PdfTwoPageSpread(", start)
new_spread = r'''@Composable
private fun PdfSpread(
    document: PdfBookDocument,
    bookKey: String,
    bookTitle: String,
    initialPage: Int,
    twoPage: Boolean,
    theme: ReaderThemeOption,
    highlights: List<BookHighlight>,
    onHighlightsChanged: () -> Unit,
    autoForwardToken: Int = 0,
    onPageChanged: (Int) -> Unit,
    onSingleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable(bookKey) {
        mutableIntStateOf(initialPage.coerceIn(0, document.pageCount - 1))
    }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var turnDirection by remember { mutableIntStateOf(0) }
    var pageWidthPx by remember { mutableFloatStateOf(900f) }
    var settling by remember { mutableStateOf(false) }

    val settleAnimation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val step = if (twoPage) 2 else 1
    val progress = if (settling) settleAnimation.value else dragProgress
    val densityInfo = LocalDensity.current
    val spineWidthPx = with(densityInfo) { 10.dp.toPx() }
    val renderScale = if (twoPage) 1.45f else 1.15f
    val renderWidthPx = (pageWidthPx * renderScale).toInt().coerceIn(820, 2200)

    fun canTurn(direction: Int): Boolean = when (direction) {
        1 -> pageIndex + step < document.pageCount
        -1 -> pageIndex - step >= 0
        else -> false
    }

    var warmingDirection by remember { mutableIntStateOf(0) }

    fun turnAssetIndices(direction: Int): List<Int> {
        return if (twoPage) {
            when (direction) {
                1 -> listOf(pageIndex, pageIndex + 1, pageIndex + 2, pageIndex + 3)
                -1 -> listOf(pageIndex - 2, pageIndex - 1, pageIndex, pageIndex + 1)
                else -> emptyList()
            }
        } else {
            when (direction) {
                1 -> listOf(pageIndex, pageIndex + 1)
                -1 -> listOf(pageIndex - 1, pageIndex)
                else -> emptyList()
            }
        }.filter { it in 0 until document.pageCount }
    }

    fun turnAssetsReady(direction: Int): Boolean {
        if (!canTurn(direction)) return false
        return turnAssetIndices(direction).all {
            document.cachedPage(it, renderWidthPx, theme) != null
        }
    }

    fun warmTurnAssets(direction: Int) {
        if (!canTurn(direction) || warmingDirection == direction) return
        warmingDirection = direction
        scope.launch {
            withContext(Dispatchers.IO) {
                turnAssetIndices(direction).forEach {
                    document.renderPage(it, renderWidthPx, theme)
                }
            }
            if (warmingDirection == direction) warmingDirection = 0
        }
    }

    fun settleTurn(cancelOnly: Boolean = false) {
        val direction = turnDirection
        val start = dragProgress
        val shouldComplete =
            !cancelOnly && direction != 0 && canTurn(direction) && start >= 0.18f

        scope.launch {
            settling = true
            settleAnimation.snapTo(start)
            settleAnimation.animateTo(
                targetValue = if (shouldComplete) {
                    if (twoPage) 0.985f else 1f
                } else {
                    0f
                },
                animationSpec = tween(
                    durationMillis = if (shouldComplete) 235 else 155,
                    easing = FastOutSlowInEasing
                )
            )

            if (shouldComplete) {
                val newIndex = (
                    pageIndex + if (direction == 1) step else -step
                ).coerceIn(0, document.pageCount - 1)
                Snapshot.withMutableSnapshot {
                    pageIndex = newIndex
                    dragPx = 0f
                    dragProgress = 0f
                    turnDirection = 0
                    settling = false
                }
                onPageChanged(newIndex)
            } else {
                Snapshot.withMutableSnapshot {
                    dragPx = 0f
                    dragProgress = 0f
                    turnDirection = 0
                    settling = false
                }
            }
            settleAnimation.snapTo(0f)
        }
    }

    LaunchedEffect(bookKey, initialPage) {
        pageIndex = initialPage.coerceIn(0, document.pageCount - 1)
        dragPx = 0f
        dragProgress = 0f
        turnDirection = 0
    }

    LaunchedEffect(twoPage) {
        if (twoPage && pageIndex % 2 != 0) {
            pageIndex = (pageIndex - 1).coerceAtLeast(0)
        }
        dragPx = 0f
        dragProgress = 0f
        turnDirection = 0
    }

    LaunchedEffect(autoForwardToken) {
        if (autoForwardToken <= 0 || settling || !canTurn(1)) return@LaunchedEffect
        if (!turnAssetsReady(1)) {
            withContext(Dispatchers.IO) {
                turnAssetIndices(1).forEach {
                    document.renderPage(it, renderWidthPx, theme)
                }
            }
        }
        settling = true
        turnDirection = 1
        dragPx = -pageWidthPx
        dragProgress = 0f
        settleAnimation.snapTo(0f)
        settleAnimation.animateTo(
            targetValue = if (twoPage) 0.985f else 1f,
            animationSpec = tween(
                durationMillis = 360,
                easing = FastOutSlowInEasing
            )
        )
        val newIndex = (pageIndex + step).coerceIn(0, document.pageCount - 1)
        Snapshot.withMutableSnapshot {
            pageIndex = newIndex
            dragPx = 0f
            dragProgress = 0f
            turnDirection = 0
            settling = false
        }
        onPageChanged(newIndex)
        settleAnimation.snapTo(0f)
    }

    LaunchedEffect(pageIndex, twoPage, renderWidthPx, theme) {
        withContext(Dispatchers.IO) {
            val candidates = if (twoPage) {
                listOf(
                    pageIndex + 2,
                    pageIndex + 3,
                    pageIndex - 1,
                    pageIndex - 2,
                    pageIndex,
                    pageIndex + 1
                )
            } else {
                listOf(
                    pageIndex + 1,
                    pageIndex - 1,
                    pageIndex,
                    pageIndex + 2,
                    pageIndex - 2
                )
            }
            candidates
                .filter { it in 0 until document.pageCount }
                .distinct()
                .forEach { document.renderPage(it, renderWidthPx, theme) }
        }
    }

    val gestureModifier = Modifier
        .onSizeChanged {
            pageWidthPx = if (twoPage) {
                ((it.width - spineWidthPx) / 2f).coerceAtLeast(1f)
            } else {
                it.width.toFloat().coerceAtLeast(1f)
            }
        }
        .pointerInput(pageIndex, twoPage, pageWidthPx, settling) {
            if (settling) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = {
                    dragPx = 0f
                    dragProgress = 0f
                    turnDirection = 0
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    val proposed = (dragPx + dragAmount)
                        .coerceIn(-pageWidthPx, pageWidthPx)
                    val direction = when {
                        proposed < 0f -> 1
                        proposed > 0f -> -1
                        else -> 0
                    }
                    when {
                        direction == 0 -> {
                            dragPx = 0f
                            dragProgress = 0f
                            turnDirection = 0
                        }
                        canTurn(direction) && turnAssetsReady(direction) -> {
                            dragPx = proposed
                            turnDirection = direction
                            dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                        }
                        canTurn(direction) -> {
                            warmTurnAssets(direction)
                            dragPx = 0f
                            dragProgress = 0f
                            turnDirection = 0
                        }
                        else -> {
                            dragPx = proposed.coerceIn(
                                -pageWidthPx * 0.055f,
                                pageWidthPx * 0.055f
                            )
                            dragProgress = 0f
                            turnDirection = 0
                        }
                    }
                },
                onDragEnd = { settleTurn() },
                onDragCancel = { settleTurn(cancelOnly = true) }
            )
        }
        .pointerInput(bookKey, pageIndex, twoPage) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val startPosition = down.position
                val startTime = down.uptimeMillis
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dx = change.position.x - startPosition.x
                    val dy = change.position.y - startPosition.y
                    if (
                        kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                        kotlin.math.abs(dy) > viewConfiguration.touchSlop
                    ) moved = true
                    if (!change.pressed) {
                        val duration = change.uptimeMillis - startTime
                        if (!moved && duration < viewConfiguration.longPressTimeoutMillis) {
                            onSingleTap()
                        }
                        break
                    }
                }
            }
        }

    Box(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        if (twoPage) {
            PdfTwoPageSpread(
                document = document,
                bookKey = bookKey,
                bookTitle = bookTitle,
                pageIndex = pageIndex,
                turnDirection = turnDirection,
                progress = progress,
                theme = theme,
                renderWidthPx = renderWidthPx,
                highlights = highlights,
                onHighlightsChanged = onHighlightsChanged
            )
        } else {
            PdfSinglePageSpread(
                document = document,
                bookKey = bookKey,
                bookTitle = bookTitle,
                pageIndex = pageIndex,
                turnDirection = turnDirection,
                progress = progress,
                theme = theme,
                renderWidthPx = renderWidthPx,
                highlights = highlights,
                onHighlightsChanged = onHighlightsChanged
            )
        }
    }
}

'''
pdf = pdf[:start] + new_spread + pdf[end:]

# Replace two-page spread with feature-aware stationary pages.
start = pdf.index("@Composable\nprivate fun PdfTwoPageSpread(")
end = pdf.index("@Composable\nprivate fun PdfBookSpine(", start)
new_two = r'''@Composable
private fun PdfTwoPageSpread(
    document: PdfBookDocument,
    bookKey: String,
    bookTitle: String,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    highlights: List<BookHighlight>,
    onHighlightsChanged: () -> Unit
) {
    val isForward = turnDirection == 1
    val isBackward = turnDirection == -1

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .zIndex(if (isBackward) 3f else 0f)
        ) {
            val leftIndex = if (isBackward) pageIndex - 2 else pageIndex
            PdfStablePage(
                document = document,
                bookKey = bookKey,
                bookTitle = bookTitle,
                index = leftIndex,
                theme = theme,
                renderWidthPx = renderWidthPx,
                highlights = highlights,
                onHighlightsChanged = onHighlightsChanged,
                interactive = turnDirection == 0,
                modifier = Modifier.fillMaxSize()
            )
            if (isBackward) {
                PdfTurningPage(
                    document = document,
                    frontIndex = pageIndex,
                    backIndex = pageIndex - 1,
                    progress = progress,
                    direction = -1,
                    theme = theme,
                    renderWidthPx = renderWidthPx,
                    crossSpine = true,
                    modifier = Modifier.fillMaxSize().zIndex(4f)
                )
            }
        }

        PdfBookSpine(progress = progress)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .zIndex(if (isForward) 3f else 0f)
        ) {
            val rightIndex = if (isForward) pageIndex + 3 else pageIndex + 1
            PdfStablePage(
                document = document,
                bookKey = bookKey,
                bookTitle = bookTitle,
                index = rightIndex,
                theme = theme,
                renderWidthPx = renderWidthPx,
                highlights = highlights,
                onHighlightsChanged = onHighlightsChanged,
                interactive = turnDirection == 0,
                modifier = Modifier.fillMaxSize()
            )
            if (isForward) {
                PdfTurningPage(
                    document = document,
                    frontIndex = pageIndex + 1,
                    backIndex = pageIndex + 2,
                    progress = progress,
                    direction = 1,
                    theme = theme,
                    renderWidthPx = renderWidthPx,
                    crossSpine = true,
                    modifier = Modifier.fillMaxSize().zIndex(4f)
                )
            }
        }
    }
}

'''
pdf = pdf[:start] + new_two + pdf[end:]

# Replace stable + single page section up to turning page.
start = pdf.index("@Composable\nprivate fun PdfStablePage(")
end = pdf.index("@Composable\nprivate fun PdfTurningPage(", start)
new_middle = r'''@Composable
private fun PdfStablePage(
    document: PdfBookDocument,
    bookKey: String,
    bookTitle: String,
    index: Int?,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    highlights: List<BookHighlight>,
    onHighlightsChanged: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    if (index == null || index !in 0 until document.pageCount) {
        Box(modifier = modifier)
        return
    }

    val cached = document.cachedPage(index, renderWidthPx, theme)
    if (cached != null) {
        PdfPageSurface(
            document = document,
            bookKey = bookKey,
            bookTitle = bookTitle,
            bitmap = cached,
            index = index,
            theme = theme,
            renderWidthPx = renderWidthPx,
            highlights = highlights,
            onHighlightsChanged = onHighlightsChanged,
            interactive = interactive,
            modifier = modifier
        )
    } else {
        PdfPage(
            document = document,
            bookKey = bookKey,
            bookTitle = bookTitle,
            index = index,
            theme = theme,
            renderWidthPx = renderWidthPx,
            highlights = highlights,
            onHighlightsChanged = onHighlightsChanged,
            interactive = interactive,
            modifier = modifier
        )
    }
}

@Composable
private fun PdfSinglePageSpread(
    document: PdfBookDocument,
    bookKey: String,
    bookTitle: String,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    highlights: List<BookHighlight>,
    onHighlightsChanged: () -> Unit
) {
    val targetIndex = when (turnDirection) {
        1 -> pageIndex + 1
        -1 -> pageIndex - 1
        else -> pageIndex
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 3.dp)
    ) {
        PdfPage(
            document = document,
            bookKey = bookKey,
            bookTitle = bookTitle,
            index = targetIndex,
            theme = theme,
            renderWidthPx = renderWidthPx,
            highlights = highlights,
            onHighlightsChanged = onHighlightsChanged,
            interactive = turnDirection == 0,
            modifier = Modifier.fillMaxSize()
        )

        if (turnDirection != 0) {
            PdfSingleTurningPage(
                document = document,
                frontIndex = pageIndex,
                progress = progress,
                direction = turnDirection,
                theme = theme,
                renderWidthPx = renderWidthPx,
                modifier = Modifier.fillMaxSize().zIndex(4f)
            )
        }
    }
}

@Composable
private fun PdfSingleTurningPage(
    document: PdfBookDocument,
    frontIndex: Int,
    progress: Float,
    direction: Int,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val rotation = if (direction == 1) -88f * p else 88f * p
    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }
    val bitmap = remember(document, frontIndex, renderWidthPx, theme) {
        document.cachedPage(frontIndex, renderWidthPx, theme)
    }

    Box(
        modifier = modifier.graphicsLayer {
            transformOrigin = origin
            rotationY = rotation
            cameraDistance = 48f
            shadowElevation = 18f * p
            scaleY = 1f - (0.008f * p)
        }
    ) {
        PdfFrozenPage(
            bitmap = bitmap,
            index = frontIndex,
            totalPages = document.pageCount,
            theme = theme,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(if (direction == 1) Alignment.CenterEnd else Alignment.CenterStart)
                .width(18.dp)
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f * p))
        )
    }
}

'''
pdf = pdf[:start] + new_middle + pdf[end:]

# Make turning sheet page number style match EPUB.
pdf = pdf.replace(
    """            PdfFrozenPage(\n                bitmap = if (showingBack) backBitmap else frontBitmap,\n                index = if (showingBack) backIndex else frontIndex,\n                theme = theme,""",
    """            PdfFrozenPage(\n                bitmap = if (showingBack) backBitmap else frontBitmap,\n                index = if (showingBack) backIndex else frontIndex,\n                totalPages = document.pageCount,\n                theme = theme,""",
    1
)

# Replace frozen/page tail with total-page numbering + long-press native PDF selection overlay.
start = pdf.index("@Composable\nprivate fun PdfFrozenPage(")
new_tail = r'''@Composable
private fun PdfFrozenPage(
    bitmap: Bitmap?,
    index: Int?,
    totalPages: Int,
    theme: ReaderThemeOption,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(7.dp))
            .clip(RoundedCornerShape(7.dp)),
        color = when (theme) {
            ReaderThemeOption.LIGHT -> Color(0xFFF5F2EA)
            ReaderThemeOption.SEPIA -> Color(0xFFE7D3B2)
            ReaderThemeOption.DARK -> Color(0xFF191919)
        }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = index?.let { page -> "PDF sayfa " + (page + 1) },
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            }
            if (index != null) {
                Text(
                    text = if (totalPages > 0) "${index + 1} / $totalPages" else (index + 1).toString(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
                    color = if (theme == ReaderThemeOption.DARK) {
                        Color.White.copy(alpha = 0.52f)
                    } else {
                        Color.Black.copy(alpha = 0.42f)
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PdfPage(
    document: PdfBookDocument,
    bookKey: String,
    bookTitle: String,
    index: Int?,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    highlights: List<BookHighlight>,
    onHighlightsChanged: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    if (index == null || index !in 0 until document.pageCount) {
        Box(modifier = modifier)
        return
    }

    val bitmap by produceState<Bitmap?>(
        initialValue = document.cachedPage(index, renderWidthPx, theme),
        key1 = document,
        key2 = index,
        key3 = "$theme:$renderWidthPx"
    ) {
        value = withContext(Dispatchers.IO) {
            document.renderPage(index, renderWidthPx, theme)
        }
    }

    val ready = bitmap
    if (ready == null) {
        Box(modifier = modifier)
    } else {
        PdfPageSurface(
            document = document,
            bookKey = bookKey,
            bookTitle = bookTitle,
            bitmap = ready,
            index = index,
            theme = theme,
            renderWidthPx = renderWidthPx,
            highlights = highlights,
            onHighlightsChanged = onHighlightsChanged,
            interactive = interactive,
            modifier = modifier
        )
    }
}

@Composable
private fun PdfPageSurface(
    document: PdfBookDocument,
    bookKey: String,
    bookTitle: String,
    bitmap: Bitmap,
    index: Int,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    highlights: List<BookHighlight>,
    onHighlightsChanged: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val densityInfo = LocalDensity.current
    val scope = rememberCoroutineScope()
    val geometry = document.cachedGeometry(index, renderWidthPx, theme)
    var boxSize by remember(index, bitmap) { mutableStateOf(IntSize.Zero) }
    var selection by remember(index) { mutableStateOf<PdfSelectionSnapshot?>(null) }
    var dragStart by remember(index) { mutableStateOf<Offset?>(null) }
    var dragEnd by remember(index) { mutableStateOf<Offset?>(null) }
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
                    document.selectByIndices(
                        index = index,
                        startIndex = highlight.startOffset,
                        endIndex = highlight.endOffset
                    )?.bounds.orEmpty()
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

    fun toPdfPoint(offset: Offset): Point? {
        val g = geometry ?: return null
        val m = imageMetrics() ?: return null
        val scale = m[0]
        val left = m[1]
        val top = m[2]
        val cropX = ((offset.x - left) / scale).coerceIn(0f, bitmap.width.toFloat())
        val cropY = ((offset.y - top) / scale).coerceIn(0f, bitmap.height.toFloat())
        val sourceX = cropX + g.cropLeftPx
        val sourceY = cropY + g.cropTopPx
        val pdfX = (sourceX / g.sourceWidthPx * g.pageWidthPoints)
            .roundToInt().coerceIn(0, g.pageWidthPoints)
        val pdfY = (sourceY / g.sourceHeightPx * g.pageHeightPoints)
            .roundToInt().coerceIn(0, g.pageHeightPoints)
        return Point(pdfX, pdfY)
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

    val selectionModifier = if (
        interactive &&
        document.supportsTextSelection &&
        geometry != null
    ) {
        Modifier.pointerInput(index, boxSize, renderWidthPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    dragStart = offset
                    dragEnd = offset
                    selection = null
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragEnd = change.position
                },
                onDragEnd = {
                    val startPoint = dragStart?.let(::toPdfPoint)
                    val stopPoint = dragEnd?.let(::toPdfPoint)
                    dragStart = null
                    dragEnd = null
                    if (startPoint != null && stopPoint != null) {
                        scope.launch {
                            selection = withContext(Dispatchers.IO) {
                                document.selectByPoints(index, startPoint, stopPoint)
                            }
                        }
                    }
                },
                onDragCancel = {
                    dragStart = null
                    dragEnd = null
                }
            )
        }
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(7.dp))
            .clip(RoundedCornerShape(7.dp)),
        color = when (theme) {
            ReaderThemeOption.LIGHT -> Color(0xFFF5F2EA)
            ReaderThemeOption.SEPIA -> Color(0xFFE7D3B2)
            ReaderThemeOption.DARK -> Color(0xFF191919)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .then(selectionModifier),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF sayfa ${index + 1}",
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit
            )

            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                savedHighlightBounds.forEach { rect ->
                    toDisplayRect(rect)?.let { shown ->
                        drawRect(
                            color = Color(0xFFFFE45C).copy(alpha = 0.46f),
                            topLeft = Offset(shown.left, shown.top),
                            size = Size(
                                (shown.right - shown.left).coerceAtLeast(1f),
                                (shown.bottom - shown.top).coerceAtLeast(1f)
                            )
                        )
                    }
                }
                selection?.bounds?.forEach { rect ->
                    toDisplayRect(rect)?.let { shown ->
                        drawRect(
                            color = Color(0xFF74A9FF).copy(alpha = 0.34f),
                            topLeft = Offset(shown.left, shown.top),
                            size = Size(
                                (shown.right - shown.left).coerceAtLeast(1f),
                                (shown.bottom - shown.top).coerceAtLeast(1f)
                            )
                        )
                    }
                }
            }

            selection?.takeIf { it.text.isNotBlank() }?.let { chosen ->
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = {
                        LibraryStore.addNote(
                            context = context,
                            bookUri = bookKey,
                            bookTitle = bookTitle,
                            pageNumber = index + 1,
                            text = chosen.text
                        )
                        Toast.makeText(context, "Notlara eklendi.", Toast.LENGTH_SHORT).show()
                        selection = null
                    }) {
                        Text("Notlara Ekle")
                    }

                    Button(
                        onClick = {
                            LibraryStore.addHighlight(
                                context = context,
                                bookUri = bookKey,
                                pageNumber = index + 1,
                                startOffset = chosen.startIndex,
                                endOffset = chosen.endIndex,
                                text = chosen.text
                            )
                            onHighlightsChanged()
                            Toast.makeText(
                                context,
                                "Sarı fosforla çizildi.",
                                Toast.LENGTH_SHORT
                            ).show()
                            selection = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFD83D),
                            contentColor = Color(0xFF332A00)
                        )
                    ) {
                        Text("Fosforla Çiz")
                    }
                }
            }

            Text(
                text = "${index + 1} / ${document.pageCount}",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
                color = if (theme == ReaderThemeOption.DARK) {
                    Color.White.copy(alpha = 0.52f)
                } else {
                    Color.Black.copy(alpha = 0.42f)
                },
                fontSize = 12.sp
            )
        }
    }
}
'''
pdf = pdf[:start] + new_tail

pdf_path.write_text(pdf, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
if 'versionCode = 37' not in build or 'versionName = "0.9.26"' not in build:
    raise SystemExit("expected version 0.9.26 not found")
build = build.replace('versionCode = 37', 'versionCode = 38', 1)
build = build.replace('versionName = "0.9.26"', 'versionName = "0.9.27"', 1)
build_path.write_text(build, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")
if 'text = "v0.9.26"' not in main:
    raise SystemExit("UI version v0.9.26 not found")
main = main.replace('text = "v0.9.26"', 'text = "v0.9.27"', 1)
main_path.write_text(main, encoding="utf-8")

print("Applied FoldBook 0.9.27 PDF EPUB feature parity patch")

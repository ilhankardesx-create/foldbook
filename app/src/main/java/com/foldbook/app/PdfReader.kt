package com.foldbook.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.min

private class PdfBookDocument(
    context: Context,
    uri: Uri
) : Closeable {
    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("PDF açılamadı.")
    private val renderer = PdfRenderer(descriptor)

    private val pageCache = object : LruCache<String, Bitmap>(64 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    val pageCount: Int
        get() = renderer.pageCount

    @Synchronized
    fun cachedPage(
        index: Int,
        targetWidth: Int,
        theme: ReaderThemeOption = ReaderThemeOption.LIGHT
    ): Bitmap? {
        if (pageCount <= 0) return null
        val safeIndex = index.coerceIn(0, pageCount - 1)
        val renderWidth = ((targetWidth.coerceIn(720, 2400) / 64) * 64)
            .coerceAtLeast(720)
        return pageCache.get("$safeIndex:$renderWidth:${theme.name}")
    }

    @Synchronized
    fun renderPage(
        index: Int,
        targetWidth: Int,
        theme: ReaderThemeOption = ReaderThemeOption.LIGHT
    ): Bitmap {
        val safeIndex = index.coerceIn(0, pageCount - 1)
        val renderWidth = ((targetWidth.coerceIn(720, 2400) / 64) * 64)
            .coerceAtLeast(720)
        val key = "$safeIndex:$renderWidth:${theme.name}"

        pageCache.get(key)?.let { return it }

        val rendered = renderer.openPage(safeIndex).use { page ->
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

        val cropped = cropWhiteMargins(rendered)
        val themed = applyTheme(cropped, theme)
        pageCache.put(key, themed)
        return themed
    }

    private fun cropWhiteMargins(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 80 || height < 80) return source

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

        if (right < left || bottom < top) return source

        val contentWidth = right - left + 1
        val contentHeight = bottom - top + 1

        if (
            contentWidth < width * 0.16f ||
            contentHeight < height * 0.16f
        ) {
            return source
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

        if (!removesUsefulMargin) return source

        val cropped = Bitmap.createBitmap(
            source,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight
        )

        if (cropped !== source) {
            source.recycle()
        }

        return cropped
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
        renderer.close()
        descriptor.close()
    }
}

fun loadPdfCover(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        PdfBookDocument(context, uri).use { document ->
            if (document.pageCount > 0) {
                document.renderPage(0, 700, ReaderThemeOption.LIGHT).copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )
            } else {
                null
            }
        }
    }.getOrNull()
}

@Composable
fun PdfReaderScreen(
    bookKey: String,
    hasSeparatingVerticalHinge: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val document = remember(bookKey) {
        PdfBookDocument(context, Uri.parse(bookKey))
    }

    DisposableEffect(document) {
        onDispose { document.close() }
    }

    BackHandler(onBack = onBack)

    val initialPage = remember(bookKey, document.pageCount) {
        LibraryStore.readProgress(
            context = context,
            bookUri = bookKey,
            lastPageIndex = document.pageCount - 1
        )
    }

    var controlsVisible by rememberSaveable(bookKey) { mutableStateOf(false) }
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

            PdfSpread(
                document = document,
                bookKey = bookKey,
                initialPage = initialPage,
                twoPage = twoPage,
                theme = theme,
                onPageChanged = { page ->
                    LibraryStore.saveProgress(
                        context = context,
                        bookUri = bookKey,
                        pageIndex = page
                    )
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
                    .padding(top = 16.dp)
                    .zIndex(20f)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = onBack) {
                            Text("Rafa Dön")
                        }

                        listOf(
                            ReaderThemeOption.LIGHT to "Açık",
                            ReaderThemeOption.SEPIA to "Sepya",
                            ReaderThemeOption.DARK to "Koyu"
                        ).forEach { (option, label) ->
                            Button(
                                onClick = {
                                    themeName = option.name
                                    LibraryStore.saveReaderTheme(context, option)
                                }
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfSpread(
    document: PdfBookDocument,
    bookKey: String,
    initialPage: Int,
    twoPage: Boolean,
    theme: ReaderThemeOption,
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
    val renderWidthPx = (pageWidthPx * 1.45f).toInt().coerceIn(900, 2400)

    fun canTurn(direction: Int): Boolean = when (direction) {
        1 -> pageIndex + step < document.pageCount
        -1 -> pageIndex - step >= 0
        else -> false
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
                targetValue = if (shouldComplete) 1f else 0f,
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

    LaunchedEffect(pageIndex, twoPage, renderWidthPx, theme) {
        withContext(Dispatchers.IO) {
            val candidates = if (twoPage) {
                listOf(
                    pageIndex - 2,
                    pageIndex - 1,
                    pageIndex,
                    pageIndex + 1,
                    pageIndex + 2,
                    pageIndex + 3
                )
            } else {
                listOf(
                    pageIndex,
                    pageIndex + 1,
                    pageIndex - 1,
                    pageIndex + 2,
                    pageIndex - 2
                )
            }

            candidates
                .filter { it in 0 until document.pageCount }
                .distinct()
                .forEach {
                    document.renderPage(it, renderWidthPx, theme)
                }
        }
    }

    val gestureModifier = Modifier
        .onSizeChanged {
            pageWidthPx = if (twoPage) {
                ((it.width - 10f) / 2f).coerceAtLeast(1f)
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

                    if (direction == 0 || canTurn(direction)) {
                        dragPx = proposed
                        turnDirection = direction
                        dragProgress =
                            (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                    } else {
                        dragPx = proposed.coerceIn(
                            -pageWidthPx * 0.055f,
                            pageWidthPx * 0.055f
                        )
                        dragProgress = 0f
                        turnDirection = 0
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
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: break

                    val dx = change.position.x - startPosition.x
                    val dy = change.position.y - startPosition.y

                    if (
                        kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                        kotlin.math.abs(dy) > viewConfiguration.touchSlop
                    ) {
                        moved = true
                    }

                    if (!change.pressed) {
                        val duration = change.uptimeMillis - startTime
                        if (
                            !moved &&
                            duration < viewConfiguration.longPressTimeoutMillis
                        ) {
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
                pageIndex = pageIndex,
                turnDirection = turnDirection,
                progress = progress,
                theme = theme,
                renderWidthPx = renderWidthPx
            )
        } else {
            PdfSinglePageSpread(
                document = document,
                pageIndex = pageIndex,
                turnDirection = turnDirection,
                progress = progress,
                theme = theme,
                renderWidthPx = renderWidthPx
            )
        }
    }
}

@Composable
private fun PdfTwoPageSpread(
    document: PdfBookDocument,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    theme: ReaderThemeOption,
    renderWidthPx: Int
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
                .padding(vertical = 3.dp)
        ) {
            val leftIndex = if (isBackward) pageIndex - 2 else pageIndex

            PdfPage(
                document = document,
                index = leftIndex,
                theme = theme,
                renderWidthPx = renderWidthPx,
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
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )
            }
        }

        Box(
            modifier = Modifier
                .width(8.dp)
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.14f))
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(vertical = 3.dp)
        ) {
            val rightIndex = if (isForward) pageIndex + 3 else pageIndex + 1

            PdfPage(
                document = document,
                index = rightIndex,
                theme = theme,
                renderWidthPx = renderWidthPx,
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
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )
            }
        }
    }
}

@Composable
private fun PdfSinglePageSpread(
    document: PdfBookDocument,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    theme: ReaderThemeOption,
    renderWidthPx: Int
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
            index = targetIndex,
            theme = theme,
            renderWidthPx = renderWidthPx,
            modifier = Modifier.fillMaxSize()
        )

        if (turnDirection != 0) {
            PdfTurningPage(
                document = document,
                frontIndex = pageIndex,
                backIndex = targetIndex,
                progress = progress,
                direction = turnDirection,
                theme = theme,
                renderWidthPx = renderWidthPx,
                cameraDistanceValue = 70f,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            )
        }
    }
}

@Composable
private fun PdfTurningPage(
    document: PdfBookDocument,
    frontIndex: Int?,
    backIndex: Int?,
    progress: Float,
    direction: Int,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    cameraDistanceValue: Float = 30f,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val showingBack = p > 0.5f
    val rotation = if (direction == 1) -180f * p else 180f * p
    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }

    Box(
        modifier = modifier.graphicsLayer {
            transformOrigin = origin
            rotationY = rotation
            cameraDistance = cameraDistanceValue
            shadowElevation = 20f * (1f - abs(0.5f - p) * 2f)
            scaleY = 1f - (0.012f * (1f - abs(0.5f - p) * 2f))
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (showingBack) scaleX = -1f
                }
        ) {
            PdfPage(
                document = document,
                index = if (showingBack && backIndex != null) {
                    backIndex
                } else {
                    frontIndex
                },
                theme = theme,
                renderWidthPx = renderWidthPx,
                modifier = Modifier.fillMaxSize()
            )

            val edgeAlpha =
                0.22f * (1f - abs(0.5f - p) * 2f)

            Box(
                modifier = Modifier
                    .align(
                        if (direction == 1) {
                            Alignment.CenterStart
                        } else {
                            Alignment.CenterEnd
                        }
                    )
                    .width(24.dp)
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = edgeAlpha))
            )
        }
    }
}

@Composable
private fun PdfPage(
    document: PdfBookDocument,
    index: Int?,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
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
                    contentDescription = "PDF sayfa " + (index + 1),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = (index + 1).toString(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp),
                color = if (theme == ReaderThemeOption.DARK) {
                    Color.White.copy(alpha = 0.52f)
                } else {
                    Color.Black.copy(alpha = 0.42f)
                },
                fontSize = 10.sp
            )
        }
    }
}

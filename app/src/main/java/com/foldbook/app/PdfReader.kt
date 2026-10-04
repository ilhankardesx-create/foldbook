package com.foldbook.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.abs

private class PdfBookDocument(
    context: Context,
    uri: Uri
) : Closeable {
    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("PDF açılamadı.")
    private val renderer = PdfRenderer(descriptor)

    val pageCount: Int
        get() = renderer.pageCount

    @Synchronized
    fun renderPage(index: Int, targetWidth: Int): Bitmap {
        val safeIndex = index.coerceIn(0, pageCount - 1)
        renderer.openPage(safeIndex).use { page ->
            val width = targetWidth.coerceAtLeast(600)
            val ratio = page.height.toFloat() / page.width.toFloat()
            val height = (width * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
            }
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF171717)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .pointerInput(Unit) {
                    detectTapGestures {
                        controlsVisible = !controlsVisible
                    }
                }
        ) {
            val twoPage = hasSeparatingVerticalHinge || maxWidth >= 700.dp

            PdfSpread(
                document = document,
                bookKey = bookKey,
                initialPage = initialPage,
                twoPage = twoPage,
                onPageChanged = { page ->
                    LibraryStore.saveProgress(
                        context = context,
                        bookUri = bookKey,
                        pageIndex = page
                    )
                },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = onBack) {
                            Text("Rafa Dön")
                        }

                        Text(
                            text = "PDF",
                            color = Color.White,
                            fontSize = 13.sp
                        )
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
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable(bookKey) {
        mutableIntStateOf(initialPage.coerceIn(0, document.pageCount - 1))
    }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var pageWidthPx by remember { mutableFloatStateOf(1f) }
    val step = if (twoPage) 2 else 1

    LaunchedEffect(twoPage) {
        if (twoPage && pageIndex % 2 != 0) {
            pageIndex = (pageIndex - 1).coerceAtLeast(0)
        }
    }

    val direction = when {
        dragPx < 0f -> 1
        dragPx > 0f -> -1
        else -> 0
    }
    val progress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)

    fun canTurn(dir: Int): Boolean = when (dir) {
        1 -> pageIndex + step < document.pageCount
        -1 -> pageIndex - step >= 0
        else -> false
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                pageWidthPx = if (twoPage) it.width / 2f else it.width.toFloat()
            }
            .pointerInput(pageIndex, twoPage, pageWidthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { dragPx = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        val proposed = (dragPx + amount)
                            .coerceIn(-pageWidthPx, pageWidthPx)
                        val dir = when {
                            proposed < 0 -> 1
                            proposed > 0 -> -1
                            else -> 0
                        }
                        dragPx = if (dir == 0 || canTurn(dir)) {
                            proposed
                        } else {
                            proposed.coerceIn(-pageWidthPx * 0.06f, pageWidthPx * 0.06f)
                        }
                    },
                    onDragEnd = {
                        val dir = when {
                            dragPx < 0 -> 1
                            dragPx > 0 -> -1
                            else -> 0
                        }
                        if (dir != 0 && canTurn(dir) && progress >= 0.20f) {
                            pageIndex = (pageIndex + if (dir == 1) step else -step)
                                .coerceIn(0, document.pageCount - 1)
                            onPageChanged(pageIndex)
                        }
                        dragPx = 0f
                    },
                    onDragCancel = { dragPx = 0f }
                )
            }
    ) {
        if (twoPage) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center
            ) {
                PdfPage(
                    document = document,
                    index = pageIndex,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(vertical = 4.dp)
                )

                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.22f))
                )

                PdfPage(
                    document = document,
                    index = (pageIndex + 1).takeIf { it < document.pageCount },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(vertical = 4.dp)
                        .graphicsLayer {
                            if (direction == 1 && pageIndex + 2 < document.pageCount) {
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                rotationY = -165f * progress
                                cameraDistance = 30f
                            }
                        }
                )
            }
        } else {
            PdfPage(
                document = document,
                index = pageIndex,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (direction != 0) {
                            transformOrigin = if (direction == 1) {
                                androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            } else {
                                androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                            }
                            rotationY = (if (direction == 1) -165f else 165f) * progress
                            cameraDistance = 30f
                        }
                    }
            )
        }
    }
}

@Composable
private fun PdfPage(
    document: PdfBookDocument,
    index: Int?,
    modifier: Modifier = Modifier
) {
    if (index == null || index !in 0 until document.pageCount) {
        Box(modifier = modifier)
        return
    }

    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = document,
        key2 = index
    ) {
        value = withContext(Dispatchers.IO) {
            document.renderPage(index, 1400)
        }
    }

    Surface(
        modifier = modifier
            .shadow(7.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        color = Color.White
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "PDF sayfa " + (index + 1),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = (index + 1).toString(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                color = Color.Black.copy(alpha = 0.45f),
                fontSize = 11.sp
            )
        }
    }
}

package com.foldbook.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * FoldBook page-curl renderer.
 *
 * The sheet is rendered as one continuous bitmap mesh. The fold does NOT rotate the
 * whole page at once. Instead a crease travels from the free edge toward the spine:
 *
 * - the part before the crease stays flat,
 * - a narrow cylindrical band forms the physical curl,
 * - the part after the band lies on the opposite side of the fold.
 *
 * This keeps a real page-sized sheet visible throughout the gesture instead of
 * collapsing the complete page into the thin vertical ribbon seen in the previous
 * mesh prototype.
 */
@Composable
internal fun PageCurl(
    progress: Float,
    direction: Int,
    touchYFraction: Float,
    crossSpine: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit
) {
    val p = progress.coerceIn(0f, 1f)
    val safeDirection = if (direction < 0) -1 else 1
    val touchY = touchYFraction.coerceIn(0.08f, 0.92f)

    val frontLayer = rememberGraphicsLayer()
    val backLayer = rememberGraphicsLayer()

    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mirroredBackBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // PageCurl is created only while a turn is active. Let both composables record
    // one frame, then freeze their bitmaps for the complete gesture. This prevents
    // text layout from changing while the mesh is moving.
    LaunchedEffect(frontLayer, backLayer) {
        withFrameNanos { }

        frontBitmap = runCatching {
            frontLayer.toImageBitmap().asAndroidBitmap()
        }.getOrNull()

        mirroredBackBitmap = runCatching {
            val source = backLayer.toImageBitmap().asAndroidBitmap()
            val matrix = Matrix().apply { setScale(-1f, 1f) }
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true
            )
        }.getOrNull()
    }

    Box(
        modifier = modifier.graphicsLayer {
            // Fold/two-page turns need to draw outside the half-page box and over
            // the opposite side of the center spine.
            clip = false
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    frontLayer.record {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            front()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    backLayer.record {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            back()
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { clip = false }
        ) {
            val width = size.width.coerceAtLeast(1f)
            val height = size.height.coerceAtLeast(1f)

            if (p <= 0.001f) {
                drawLayer(frontLayer)
                return@Canvas
            }

            val capturedFront = frontBitmap
            val capturedBack = mirroredBackBitmap
            if (capturedFront == null || capturedBack == null) {
                drawLayer(frontLayer)
                return@Canvas
            }

            // Enough columns for a smooth cylinder, but still inexpensive on large
            // Fold displays. More rows allow a slightly diagonal crease that follows
            // the user's vertical finger position.
            val meshWidth = 34
            val meshHeight = 16
            val columnCount = meshWidth + 1
            val rowCount = meshHeight + 1
            val vertexCount = columnCount * rowCount

            val verts = FloatArray(vertexCount * 2)
            val frontColors = IntArray(vertexCount)
            val backColors = IntArray(vertexCount)

            val wave = sin(PI.toFloat() * p).coerceAtLeast(0f)

            // The crease itself moves across the complete page. Forward starts at
            // the outer RIGHT edge and ends at the spine/LEFT edge. Backward is the
            // exact mirror of that geometry.
            val baseCrease = if (safeDirection == 1) {
                width * (1f - p)
            } else {
                width * p
            }

            // Source width consumed by the cylindrical bend. It grows naturally in
            // the middle of the gesture and almost disappears at both resting states.
            val bandWidth = max(
                width * 0.008f,
                width * 0.19f * wave
            )
            val radius = bandWidth / PI.toFloat()
            val touchCenterY = height * touchY

            var vertex = 0
            for (row in 0..meshHeight) {
                val v = row.toFloat() / meshHeight
                val sourceY = height * v

                // A real corner pull does not produce a perfectly vertical fold.
                // Keep this subtle: enough to stop the sheet looking like a door,
                // but not enough to distort the text layout.
                val rowSkew =
                    (v - touchY) * width * 0.075f * wave *
                        if (safeDirection == 1) 1f else -1f

                val crease = (baseCrease + rowSkew)
                    .coerceIn(-bandWidth * 0.35f, width + bandWidth * 0.35f)
                val bandStart = crease - bandWidth / 2f
                val bandEnd = crease + bandWidth / 2f

                for (column in 0..meshWidth) {
                    val u = column.toFloat() / meshWidth
                    val sourceX = width * u

                    var outputX: Float
                    var lift = 0f
                    var frontAlpha: Float
                    var backAlpha: Float

                    if (safeDirection == 1) {
                        when {
                            // Still-flat part connected to the center spine.
                            sourceX <= bandStart -> {
                                outputX = sourceX
                                frontAlpha = 1f
                                backAlpha = 0f
                            }

                            // Already turned part. Reflection around the crease lets
                            // it cross x=0 and continue naturally onto the left page.
                            sourceX >= bandEnd -> {
                                outputX = 2f * crease - sourceX
                                frontAlpha = 0f
                                backAlpha = 1f
                            }

                            // Continuous half-cylinder. Its derivative is +1 at the
                            // flat entrance and -1 at the turned exit, so there is no
                            // panel seam between mesh regions.
                            else -> {
                                val t = ((sourceX - bandStart) / bandWidth)
                                    .coerceIn(0f, 1f)
                                val angle = PI.toFloat() * t
                                outputX = bandStart + radius * sin(angle)
                                lift = sin(angle).coerceAtLeast(0f)

                                val frontWeight = cos(angle * 0.5f)
                                val backWeight = sin(angle * 0.5f)
                                frontAlpha = frontWeight * frontWeight
                                backAlpha = backWeight * backWeight
                            }
                        }
                    } else {
                        when {
                            // Turned section on a backward LEFT->RIGHT page turn.
                            sourceX <= bandStart -> {
                                outputX = 2f * crease - sourceX
                                frontAlpha = 0f
                                backAlpha = 1f
                            }

                            // Flat part still attached to the right/spine edge.
                            sourceX >= bandEnd -> {
                                outputX = sourceX
                                frontAlpha = 1f
                                backAlpha = 0f
                            }

                            else -> {
                                val t = ((sourceX - bandStart) / bandWidth)
                                    .coerceIn(0f, 1f)
                                val angle = PI.toFloat() * t
                                outputX = bandEnd - radius * sin(angle)
                                lift = sin(angle).coerceAtLeast(0f)

                                // At t=0 we are on the turned/back face; at t=1 we
                                // rejoin the still-flat/front face.
                                val frontWeight = sin(angle * 0.5f)
                                val backWeight = cos(angle * 0.5f)
                                frontAlpha = frontWeight * frontWeight
                                backAlpha = backWeight * backWeight
                            }
                        }
                    }

                    // Lift only the cylindrical band. Flat and already-turned paper
                    // remains full height. The compression is centered around the
                    // actual finger height, so dragging high/low changes the curl feel.
                    val verticalCompression = 1f - (0.075f * lift)
                    var outputY =
                        touchCenterY + (sourceY - touchCenterY) * verticalCompression

                    // A tiny additional bow near the free side keeps top/bottom edges
                    // from looking ruler-straight while the paper is lifted.
                    val freeSideWeight = if (safeDirection == 1) u else (1f - u)
                    outputY +=
                        (touchY - 0.5f) * height * 0.025f * lift * freeSideWeight

                    val offset = vertex * 2
                    verts[offset] = outputX
                    verts[offset + 1] = outputY

                    fun whiteWithAlpha(alpha: Float): Int {
                        return android.graphics.Color.argb(
                            (alpha.coerceIn(0f, 1f) * 255f).toInt(),
                            255,
                            255,
                            255
                        )
                    }

                    frontColors[vertex] = whiteWithAlpha(frontAlpha)
                    backColors[vertex] = whiteWithAlpha(backAlpha)
                    vertex++
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }

            drawIntoCanvas { composeCanvas ->
                val native = composeCanvas.nativeCanvas

                // Front first, reverse face second. The physically lifted/turned
                // section therefore sits over the still-flat paper where their 2D
                // projections overlap around the fold.
                native.drawBitmapMesh(
                    capturedFront,
                    meshWidth,
                    meshHeight,
                    verts,
                    0,
                    frontColors,
                    0,
                    paint
                )
                native.drawBitmapMesh(
                    capturedBack,
                    meshWidth,
                    meshHeight,
                    verts,
                    0,
                    backColors,
                    0,
                    paint
                )
            }

            // One very soft crease cue. The normal center-spine shadow is already
            // drawn by BookSpine/PdfBookSpine, so avoid stacking a dark second line.
            val shadowAlpha = if (crossSpine) {
                0.018f + 0.030f * wave
            } else {
                0.022f + 0.038f * wave
            }
            drawLine(
                color = Color.Black.copy(alpha = shadowAlpha),
                start = Offset(baseCrease.coerceIn(0f, width), 0f),
                end = Offset(baseCrease.coerceIn(0f, width), height),
                strokeWidth = 1.1f + 1.5f * wave
            )
        }
    }
}

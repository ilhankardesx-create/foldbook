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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * FoldBook reference page-turn renderer.
 *
 * The page is treated as one continuous sheet. A smooth travelling bend starts at
 * the free edge and moves toward the spine. The part near the spine can remain flat
 * while the free side has already crossed to the other page, matching the reference
 * Fold-style animation instead of rotating the whole page like a card.
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
    val forward = direction >= 0
    val touchY = touchYFraction.coerceIn(0.06f, 0.94f)

    val frontLayer = rememberGraphicsLayer()
    val backLayer = rememberGraphicsLayer()

    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mirroredBackBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturesReady by remember { mutableStateOf(false) }

    LaunchedEffect(frontLayer, backLayer) {
        capturesReady = false
        withFrameNanos { }

        val capturedFront = runCatching {
            frontLayer.toImageBitmap().asAndroidBitmap()
        }.getOrNull()

        val capturedBack = runCatching {
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

        frontBitmap = capturedFront
        mirroredBackBitmap = capturedBack
        capturesReady = capturedFront != null && capturedBack != null
    }

    Box(
        modifier = modifier.graphicsLayer {
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

                    // On the first one or two frames the bitmap snapshot is not ready
                    // yet. Keep the live current page visible so the page underneath
                    // cannot flash through when the user only nudges and releases.
                    if (!capturesReady) {
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

            // The live front composable above is deliberately visible until both
            // snapshots exist. Drawing an empty graphics layer here was the cause of
            // the full-page flash seen on very short drag/cancel gestures.
            if (!capturesReady) {
                return@Canvas
            }

            if (p <= 0.001f) {
                drawLayer(frontLayer)
                return@Canvas
            }

            val capturedFront = frontBitmap ?: return@Canvas
            val capturedBack = mirroredBackBitmap ?: return@Canvas

            // A cancelled turn used to hard-switch from the mesh to the live page,
            // which looked like a white flash. Keep the exact live page underneath
            // and gradually fade the nearly-flat mesh away during the last 10%.
            val cancelBlendEnd = 0.10f
            val meshOpacity = smootherStep((p / cancelBlendEnd).coerceIn(0f, 1f))
            if (p < cancelBlendEnd) {
                drawLayer(frontLayer)
            }

            val meshWidth = 52
            val meshHeight = 20
            val columns = meshWidth + 1
            val rows = meshHeight + 1
            val vertexCount = columns * rows

            val vertices = FloatArray(vertexCount * 2)
            val frontColors = IntArray(vertexCount)
            val backColors = IntArray(vertexCount)

            val wave = sin(PI.toFloat() * p).coerceAtLeast(0f)

            // Wide travelling bend, like the Samsung Fold reference. At p=0 the
            // transition sits just outside the free edge; at p=1 it has completely
            // passed the spine, leaving the sheet flat on the opposite side.
            val bendWidth = 0.34f
            val halfBend = bendWidth / 2f
            val bendCenter =
                1f + halfBend - p * (1f + bendWidth)

            val segmentLength = width / meshWidth
            val fingerHeight = height * touchY

            // In two-page mode the two page boxes are separated by the 4dp book
            // spine. The old mesh travelled exactly one page width, so the final
            // curled bitmap landed 4dp short and visibly snapped away when the live
            // destination page replaced it. Carry the sheet across that spine gap.
            val spineTravel = if (crossSpine) {
                4f * density * smootherStep(p)
            } else {
                0f
            }

            var vertex = 0

            for (row in 0..meshHeight) {
                val v = row.toFloat() / meshHeight
                val sourceY = height * v

                // Reference animation has a gently diagonal fold that follows the
                // finger height. Keep it subtle so text stays readable.
                // Vertical finger movement changes the curl angle in real time.
                val tilt = -(touchY - 0.5f) * 0.58f * wave
                val rowSkew =
                    (v - 0.5f) * tilt *
                        if (forward) 1f else -1f
                val localCenter = bendCenter + rowSkew
                val bendStart = localCenter - halfBend

                val xAlong = FloatArray(columns)
                val zAlong = FloatArray(columns)
                xAlong[0] = 0f
                zAlong[0] = 0f

                // Integrate the sheet tangent from the fixed spine to the free edge.
                // The tangent changes smoothly from 0° to 180° only inside the moving
                // bend. This preserves the full page length and avoids panel seams.
                for (i in 1..meshWidth) {
                    val midDistance = (i - 0.5f) / meshWidth
                    val raw = ((midDistance - bendStart) / bendWidth)
                        .coerceIn(0f, 1f)
                    val eased = smootherStep(raw)
                    val theta = PI.toFloat() * eased

                    xAlong[i] =
                        xAlong[i - 1] + segmentLength * cos(theta)
                    zAlong[i] =
                        zAlong[i - 1] + segmentLength * sin(theta)
                }

                for (column in 0..meshWidth) {
                    val distanceIndex = if (forward) {
                        column
                    } else {
                        meshWidth - column
                    }
                    val distance = distanceIndex.toFloat() / meshWidth

                    val raw = ((distance - bendStart) / bendWidth)
                        .coerceIn(0f, 1f)
                    val eased = smootherStep(raw)
                    val theta = PI.toFloat() * eased
                    val cosine = cos(theta)

                    val localX = xAlong[distanceIndex]
                    val localZ = zAlong[distanceIndex]

                    val outputX = if (forward) {
                        localX - spineTravel
                    } else {
                        width - localX + spineTravel
                    }

                    val normalizedLift =
                        (abs(localZ) / (width * 0.25f)).coerceIn(0f, 1f)

                    // Lifted paper becomes slightly foreshortened around the exact
                    // finger height. This creates the soft S-shaped sheet seen in the
                    // reference without stretching the page vertically at rest.
                    val verticalScale = 1f - 0.055f * normalizedLift
                    var outputY =
                        fingerHeight + (sourceY - fingerHeight) * verticalScale

                    // Tiny corner-following bow. The user can drag high or low and the
                    // fold leans with the finger instead of remaining ruler-straight.
                    val freeWeight = distance
                    outputY +=
                        (touchY - 0.5f) * height * 0.050f *
                            normalizedLift * freeWeight

                    val offset = vertex * 2
                    vertices[offset] = outputX
                    vertices[offset + 1] = outputY

                    // Use the physical local normal to decide which side of the page
                    // is visible. A narrow blend around edge-on avoids a hard seam.
                    val faceBlend = 0.10f
                    val frontAlpha =
                        ((cosine + faceBlend) / (2f * faceBlend))
                            .coerceIn(0f, 1f)
                    val backAlpha = 1f - frontAlpha

                    // The reference has soft paper shading concentrated in the curl,
                    // not a permanent black center line.
                    val edgeOn = 1f - abs(cosine)
                    val shade =
                        (0.13f * edgeOn * wave)
                            .coerceIn(0f, 0.18f)
                    val brightness =
                        (255f * (1f - shade)).toInt().coerceIn(200, 255)

                    frontColors[vertex] = android.graphics.Color.argb(
                        (frontAlpha * meshOpacity * 255f).toInt().coerceIn(0, 255),
                        brightness,
                        brightness,
                        brightness
                    )
                    backColors[vertex] = android.graphics.Color.argb(
                        (backAlpha * meshOpacity * 255f).toInt().coerceIn(0, 255),
                        brightness,
                        brightness,
                        brightness
                    )

                    vertex++
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }

            drawIntoCanvas { composeCanvas ->
                val native = composeCanvas.nativeCanvas

                native.drawBitmapMesh(
                    capturedFront,
                    meshWidth,
                    meshHeight,
                    vertices,
                    0,
                    frontColors,
                    0,
                    paint
                )

                native.drawBitmapMesh(
                    capturedBack,
                    meshWidth,
                    meshHeight,
                    vertices,
                    0,
                    backColors,
                    0,
                    paint
                )
            }
        }
    }
}

private fun smootherStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

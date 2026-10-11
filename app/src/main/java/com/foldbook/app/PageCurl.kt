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
import androidx.compose.ui.graphics.Color
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
 * FoldBook page-curl renderer.
 *
 * This renderer snapshots the two faces of the page and bends the whole sheet as
 * one continuous bitmap mesh. It deliberately avoids the old vertical-strip
 * renderer: no independent panels are translated or scaled, so text cannot break
 * into columns while the page is moving.
 *
 * Geometry model:
 * - the spine is the fixed edge;
 * - the free edge travels from one side of the book to the other;
 * - each mesh column gets a tangent angle and the x/z position is integrated along
 *   the sheet, producing a continuous cylindrical bend;
 * - at the halfway point the sheet is nearly edge-on, then the reverse face is
 *   used. The reverse snapshot is pre-mirrored so text is readable after crossing
 *   the spine.
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

    // PageCurl exists only while a turn is active. Record one frame first, then
    // snapshot both page faces once for the complete gesture/settle animation.
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
            // Two-page turns intentionally paint past the half-page bounds and
            // across the center spine.
            clip = false
        }
    ) {
        // Record the front face without drawing it directly.
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

        // Record the reverse face separately.
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
                // Snapshot becomes available after the first recorded frame. Keep
                // that first frame stable instead of showing a partially-built curl.
                drawLayer(frontLayer)
                return@Canvas
            }

            val meshWidth = 28
            val meshHeight = 14
            val columnCount = meshWidth + 1
            val rowCount = meshHeight + 1

            // Distance-from-spine positions and virtual height (z) for each column.
            val xAlongSheet = FloatArray(columnCount)
            val zAlongSheet = FloatArray(columnCount)

            val baseAngle = PI.toFloat() * p
            val wave = sin(PI.toFloat() * p).coerceAtLeast(0f)
            // Curvature vanishes at both resting states and is strongest mid-turn.
            val bendAmplitude = 1.05f * wave
            val segmentLength = width / meshWidth

            var integratedX = 0f
            var integratedZ = 0f
            xAlongSheet[0] = 0f
            zAlongSheet[0] = 0f

            for (i in 1..meshWidth) {
                val mid = (i - 0.5f) / meshWidth
                val localAngle = baseAngle + bendAmplitude * (mid - 0.5f)
                integratedX += segmentLength * cos(localAngle)
                integratedZ += segmentLength * sin(localAngle)
                xAlongSheet[i] = integratedX
                zAlongSheet[i] = integratedZ
            }

            val verts = FloatArray(columnCount * rowCount * 2)
            val touchCenterY = height * touchY
            var vertexOffset = 0

            for (row in 0..meshHeight) {
                val v = row.toFloat() / meshHeight
                val sourceY = height * v

                for (column in 0..meshWidth) {
                    val u = column.toFloat() / meshWidth

                    val distanceIndex = if (safeDirection == 1) {
                        column
                    } else {
                        meshWidth - column
                    }

                    val sheetX = xAlongSheet[distanceIndex]
                    val sheetZ = zAlongSheet[distanceIndex]
                    val lift = (abs(sheetZ) / width).coerceIn(0f, 1f)

                    val outputX = if (safeDirection == 1) {
                        // Forward: fixed edge is the left/spine edge.
                        sheetX
                    } else {
                        // Backward: mirror the same geometry around the right edge.
                        width - sheetX
                    }

                    // A lifted sheet gets a small perspective squeeze around the
                    // user's touch height. This bows the top/bottom edges smoothly
                    // instead of creating vertical rectangular panels.
                    val perspectiveY = 1f - (0.11f * lift)
                    var outputY = touchCenterY + (sourceY - touchCenterY) * perspectiveY

                    // Subtle corner bias: dragging high/low makes the free side lean
                    // toward that finger without changing the page layout at rest.
                    val freeEdgeWeight = if (safeDirection == 1) u else (1f - u)
                    outputY += (touchY - 0.5f) * height * 0.045f * lift * freeEdgeWeight

                    verts[vertexOffset++] = outputX
                    verts[vertexOffset++] = outputY
                }
            }

            val bitmapToDraw = if (p < 0.5f) capturedFront else capturedBack
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }

            drawIntoCanvas { composeCanvas ->
                composeCanvas.nativeCanvas.drawBitmapMesh(
                    bitmapToDraw,
                    meshWidth,
                    meshHeight,
                    verts,
                    0,
                    null,
                    0,
                    paint
                )
            }

            // Very light moving-edge shadow only. The center spine itself is drawn
            // by BookSpine/PdfBookSpine; adding another dark band here caused the
            // heavy black seam seen in the previous test build.
            val freeEdgeColumn = if (safeDirection == 1) meshWidth else 0
            val freeIndex = if (safeDirection == 1) meshWidth else meshWidth
            val freeX = if (safeDirection == 1) {
                xAlongSheet[freeIndex]
            } else {
                width - xAlongSheet[freeIndex]
            }
            val freeLift = (abs(zAlongSheet[freeIndex]) / width).coerceIn(0f, 1f)
            val edgeScaleY = 1f - 0.11f * freeLift
            val edgeTop = touchCenterY + (0f - touchCenterY) * edgeScaleY
            val edgeBottom = touchCenterY + (height - touchCenterY) * edgeScaleY

            @Suppress("UNUSED_VARIABLE")
            val keepForGeometryReadability = freeEdgeColumn

            drawLine(
                color = Color.Black.copy(alpha = 0.025f + 0.055f * wave),
                start = androidx.compose.ui.geometry.Offset(freeX, edgeTop),
                end = androidx.compose.ui.geometry.Offset(freeX, edgeBottom),
                strokeWidth = 1.4f + 2.2f * wave
            )
        }
    }
}

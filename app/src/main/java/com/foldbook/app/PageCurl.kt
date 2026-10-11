package com.foldbook.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private data class CurlGeometry(
    val front: Path,
    val fold: Path,
    val seam: Path
)

/**
 * A paper-like page curl layer.
 *
 * Unlike the old whole-page rotationY flip, only the folded portion is transformed.
 * The visible front is clipped by a curved seam, the back face is rendered separately,
 * and on a two-page spread that folded sheet travels across the book spine.
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
    val touchY = touchYFraction.coerceIn(0.05f, 0.95f)

    var widthPx by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier.onSizeChanged {
            widthPx = it.width.toFloat().coerceAtLeast(1f)
        }
    ) {
        // Front face: the still-flat part of the sheet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val geometry = curlGeometry(size, p, safeDirection, touchY)
                    clipPath(geometry.front) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            front()
        }

        // Folded back face. It is mirrored like the reverse side of a real sheet.
        val crossAmount = if (crossSpine) {
            p.pow(1.08f)
        } else {
            0f
        }
        val translation = when (safeDirection) {
            1 -> -widthPx * crossAmount
            else -> widthPx * crossAmount
        }
        val lean = (touchY - 0.5f) * 9f * sin((PI * p).toFloat())
        val squash = 1f - 0.035f * sin((PI * p).toFloat())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = translation
                    rotationZ = if (safeDirection == 1) -lean else lean
                    scaleY = squash
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (safeDirection == 1) 0f else 1f,
                        pivotFractionY = touchY
                    )
                }
                .drawWithContent {
                    val geometry = curlGeometry(size, p, safeDirection, touchY)
                    clipPath(geometry.fold) {
                        this@drawWithContent.drawContent()

                        // Paper depth: a soft dark edge plus a faint reflected highlight.
                        val shadowStrength = 0.09f +
                            0.18f * sin((PI * p).toFloat()).coerceAtLeast(0f)
                        val brush = if (safeDirection == 1) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = shadowStrength),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.055f)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.055f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = shadowStrength)
                                )
                            )
                        }
                        drawRect(brush = brush)
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = -1f
                        alpha = 0.985f
                    }
            ) {
                back()
            }
        }

        // Dynamic seam and cast shadow. Several soft strokes give the fold thickness
        // without the old bright flash/glare.
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (p <= 0.001f || p >= 0.999f) return@Canvas
            val geometry = curlGeometry(size, p, safeDirection, touchY)
            val peak = sin((PI * p).toFloat()).coerceAtLeast(0f)

            drawPath(
                path = geometry.seam,
                color = Color.Black.copy(alpha = 0.055f * peak),
                style = Stroke(width = 28f)
            )
            drawPath(
                path = geometry.seam,
                color = Color.Black.copy(alpha = 0.13f * peak),
                style = Stroke(width = 11f)
            )
            drawPath(
                path = geometry.seam,
                color = Color.White.copy(alpha = 0.12f * peak),
                style = Stroke(width = 2.2f)
            )
        }
    }
}

private fun curlGeometry(
    size: Size,
    progress: Float,
    direction: Int,
    touchYFraction: Float
): CurlGeometry {
    val width = size.width.coerceAtLeast(1f)
    val height = size.height.coerceAtLeast(1f)
    val p = progress.coerceIn(0f, 1f)
    val touchY = height * touchYFraction.coerceIn(0.05f, 0.95f)
    val wave = sin((PI * p).toFloat()).coerceAtLeast(0f)

    // The seam moves from the outer edge toward the spine while bowing toward the
    // user's finger. At the beginning/end the bow naturally collapses to zero.
    val baseX = if (direction == 1) {
        width * (1f - p)
    } else {
        width * p
    }
    val bend = width * 0.24f * wave
    val upperWeight = (1f - touchYFraction).coerceIn(0f, 1f)
    val lowerWeight = touchYFraction.coerceIn(0f, 1f)
    val sign = if (direction == 1) -1f else 1f

    val topX = (baseX - sign * bend * (0.10f + upperWeight * 0.25f))
        .coerceIn(0f, width)
    val bottomX = (baseX - sign * bend * (0.10f + lowerWeight * 0.25f))
        .coerceIn(0f, width)
    val controlX = (baseX + sign * bend).coerceIn(-width * 0.18f, width * 1.18f)
    val controlY1 = (touchY * 0.48f).coerceIn(0f, height)
    val controlY2 = (touchY + (height - touchY) * 0.56f).coerceIn(0f, height)

    val seam = Path().apply {
        moveTo(topX, 0f)
        cubicTo(
            controlX,
            controlY1,
            controlX,
            controlY2,
            bottomX,
            height
        )
    }

    val front = Path()
    val fold = Path()

    if (direction == 1) {
        front.apply {
            moveTo(0f, 0f)
            lineTo(topX, 0f)
            cubicTo(controlX, controlY1, controlX, controlY2, bottomX, height)
            lineTo(0f, height)
            close()
        }
        fold.apply {
            moveTo(topX, 0f)
            lineTo(width, 0f)
            lineTo(width, height)
            lineTo(bottomX, height)
            cubicTo(controlX, controlY2, controlX, controlY1, topX, 0f)
            close()
        }
    } else {
        front.apply {
            moveTo(topX, 0f)
            lineTo(width, 0f)
            lineTo(width, height)
            lineTo(bottomX, height)
            cubicTo(controlX, controlY2, controlX, controlY1, topX, 0f)
            close()
        }
        fold.apply {
            moveTo(0f, 0f)
            lineTo(topX, 0f)
            cubicTo(controlX, controlY1, controlX, controlY2, bottomX, height)
            lineTo(0f, height)
            close()
        }
    }

    return CurlGeometry(front = front, fold = fold, seam = seam)
}

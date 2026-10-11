package com.foldbook.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * FoldBook page curl renderer.
 *
 * Important rule: the destination page is never mirrored as a whole. On a two-page
 * spread it travels across the spine in its normal readable orientation. Only the
 * very narrow physical curl band can show a mirrored reverse face while the paper
 * is actually bent.
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

    Box(modifier = modifier) {
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width.coerceAtLeast(1f)
            val height = size.height.coerceAtLeast(1f)

            if (p <= 0.001f) {
                drawLayer(frontLayer)
                return@Canvas
            }

            val wave = sin((PI * p).toFloat()).coerceAtLeast(0f)
            val turnedLength = width * p
            val bandWidth = min(
                turnedLength,
                width * 0.105f * wave
            ).coerceAtLeast(width * 0.003f)

            val stripCount = 18
            val fingerBias = (touchY - 0.5f) * 0.12f

            // Two-page mode: the reverse face is the next/previous physical page.
            // Move it into the opposite half WITHOUT mirroring it. The still-flat
            // front is drawn afterwards and naturally hides the portion that has not
            // crossed the crease yet.
            if (crossSpine) {
                val travelX = if (safeDirection == 1) {
                    -width * p
                } else {
                    width * p
                }

                withTransform({ translate(left = travelX, top = 0f) }) {
                    drawLayer(backLayer)
                }
            }

            if (safeDirection == 1) {
                // RIGHT page -> LEFT.
                val crease = width * (1f - p)
                val bendEnd = (crease + bandWidth).coerceAtMost(width)

                // Flat front face stays on top of the travelling reverse face.
                if (crease > 0f) {
                    clipRect(0f, 0f, (crease + 1f).coerceAtMost(width), height) {
                        drawLayer(frontLayer)
                    }
                }

                // Only the narrow bent band is physically distorted.
                if (bandWidth > 1f) {
                    repeat(stripCount) { i ->
                        val sourceLeft = crease + bandWidth * i / stripCount
                        val sourceRight = crease + bandWidth * (i + 1) / stripCount
                        if (sourceLeft >= width) return@repeat

                        val left = sourceLeft.coerceIn(0f, width)
                        val right = sourceRight.coerceIn(0f, width)
                        if (right <= left) return@repeat

                        val sourceMid = (left + right) / 2f
                        val s = (sourceMid - crease).coerceIn(0f, bandWidth)
                        val local = (s / bandWidth).coerceIn(0f, 1f)
                        val angle = PI.toFloat() * local
                        val cosine = cos(angle)

                        var mappedMid = crease +
                            (bandWidth / PI.toFloat()) * sin(angle)
                        mappedMid += fingerBias * bandWidth * sin(angle)

                        val projection = abs(cosine).coerceAtLeast(0.12f)
                        val translation = mappedMid - sourceMid

                        withTransform({ translate(left = translation, top = 0f) }) {
                            scale(
                                scaleX = projection,
                                scaleY = 1f,
                                pivot = Offset(sourceMid, height * touchY)
                            ) {
                                clipRect(left - 2f, 0f, right + 2f, height) {
                                    if (cosine >= 0f) {
                                        drawLayer(frontLayer)
                                    } else {
                                        // A mirrored face is correct only INSIDE this tiny
                                        // cylindrical fold; it never covers the full page.
                                        scale(
                                            scaleX = -1f,
                                            scaleY = 1f,
                                            pivot = Offset(sourceMid, height * touchY)
                                        ) {
                                            drawLayer(backLayer)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (bendEnd > crease) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.045f * wave),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.025f * wave)
                                ),
                                startX = crease,
                                endX = bendEnd
                            ),
                            topLeft = Offset(crease, 0f),
                            size = Size(bendEnd - crease, height)
                        )
                    }
                }

                val seamAlpha = 0.018f + 0.040f * wave
                drawLine(
                    color = Color.Black.copy(alpha = seamAlpha),
                    start = Offset(crease.coerceIn(0f, width), 0f),
                    end = Offset(crease.coerceIn(0f, width), height),
                    strokeWidth = 1.2f + 2.0f * wave
                )
            } else {
                // LEFT page -> RIGHT.
                val crease = width * p
                val bendStart = (crease - bandWidth).coerceAtLeast(0f)

                if (crease < width) {
                    clipRect((crease - 1f).coerceAtLeast(0f), 0f, width, height) {
                        drawLayer(frontLayer)
                    }
                }

                if (bandWidth > 1f) {
                    repeat(stripCount) { i ->
                        val sourceRight = crease - bandWidth * i / stripCount
                        val sourceLeft = crease - bandWidth * (i + 1) / stripCount
                        if (sourceRight <= 0f) return@repeat

                        val left = sourceLeft.coerceIn(0f, width)
                        val right = sourceRight.coerceIn(0f, width)
                        if (right <= left) return@repeat

                        val sourceMid = (left + right) / 2f
                        val s = (crease - sourceMid).coerceIn(0f, bandWidth)
                        val local = (s / bandWidth).coerceIn(0f, 1f)
                        val angle = PI.toFloat() * local
                        val cosine = cos(angle)

                        var mappedMid = crease -
                            (bandWidth / PI.toFloat()) * sin(angle)
                        mappedMid -= fingerBias * bandWidth * sin(angle)

                        val projection = abs(cosine).coerceAtLeast(0.12f)
                        val translation = mappedMid - sourceMid

                        withTransform({ translate(left = translation, top = 0f) }) {
                            scale(
                                scaleX = projection,
                                scaleY = 1f,
                                pivot = Offset(sourceMid, height * touchY)
                            ) {
                                clipRect(left - 2f, 0f, right + 2f, height) {
                                    if (cosine >= 0f) {
                                        drawLayer(frontLayer)
                                    } else {
                                        scale(
                                            scaleX = -1f,
                                            scaleY = 1f,
                                            pivot = Offset(sourceMid, height * touchY)
                                        ) {
                                            drawLayer(backLayer)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (crease > bendStart) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.025f * wave),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.045f * wave)
                                ),
                                startX = bendStart,
                                endX = crease
                            ),
                            topLeft = Offset(bendStart, 0f),
                            size = Size(crease - bendStart, height)
                        )
                    }
                }

                val seamAlpha = 0.018f + 0.040f * wave
                drawLine(
                    color = Color.Black.copy(alpha = seamAlpha),
                    start = Offset(crease.coerceIn(0f, width), 0f),
                    end = Offset(crease.coerceIn(0f, width), height),
                    strokeWidth = 1.2f + 2.0f * wave
                )
            }
        }
    }
}

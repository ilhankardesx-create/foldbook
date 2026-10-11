package com.foldbook.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * The previous implementation mirrored and translated a complete page composable,
 * which made text appear as a huge reversed column during the turn. This renderer
 * captures the two page faces once and draws the sheet in three physical regions:
 *
 * 1. the flat, not-yet-turned face,
 * 2. a narrow cylindrical curl band,
 * 3. the already-turned reverse face.
 *
 * Only the curl band is sliced. The rest of the sheet is drawn once, so text does
 * not duplicate or stack while the page is moving.
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
        // Record the current front face without drawing it directly.
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

        // Record the reverse face separately. It is used only once the paper has
        // actually passed the midpoint of the curl.
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

            // At the beginning and end the curl radius collapses. In the middle a
            // small band remains rounded while the rest of the sheet lies flat.
            val wave = sin((PI * p).toFloat()).coerceAtLeast(0f)
            val turnedLength = width * p
            val bandWidth = min(
                turnedLength,
                width * 0.16f * wave
            ).coerceAtLeast(width * 0.004f)

            val stripCount = 12
            val paperTint = Color.White.copy(alpha = 0.045f)
            val fingerBias = (touchY - 0.5f) * 0.18f

            if (safeDirection == 1) {
                // RIGHT page -> LEFT. The crease travels from the outer edge to spine.
                val crease = width * (1f - p)
                val bendEnd = (crease + bandWidth).coerceAtMost(width)

                // Flat front section.
                if (crease > 0f) {
                    clipRect(0f, 0f, (crease + 1f).coerceAtMost(width), height) {
                        drawLayer(frontLayer)
                    }
                }

                // Already turned reverse side. Reflect around the end of the curl
                // band so it naturally crosses the spine instead of being translated
                // as one giant mirrored page.
                if (bendEnd < width) {
                    val pivotX = crease + bandWidth / 2f
                    scale(
                        scaleX = -1f,
                        scaleY = 1f,
                        pivot = Offset(pivotX, height * touchY)
                    ) {
                        clipRect(bendEnd, 0f, width, height) {
                            drawLayer(backLayer)
                            drawRect(
                                color = paperTint,
                                topLeft = Offset(bendEnd, 0f),
                                size = Size(width - bendEnd, height)
                            )
                        }
                    }
                }

                // Cylindrical bend. Only this narrow region is split into strips.
                if (bandWidth > 1f) {
                    repeat(stripCount) { i ->
                        val sourceLeft = crease + bandWidth * i / stripCount
                        val sourceRight = crease + bandWidth * (i + 1) / stripCount
                        if (sourceLeft >= width) return@repeat

                        val left = sourceLeft.coerceIn(0f, width)
                        val right = sourceRight.coerceIn(0f, width)
                        val sourceMid = (left + right) / 2f
                        val s = (sourceMid - crease).coerceIn(0f, bandWidth)
                        val local = (s / bandWidth).coerceIn(0f, 1f)
                        val angle = PI.toFloat() * local
                        val cosine = cos(angle)

                        // Integral of cos(theta) gives a continuous cylindrical arc.
                        var mappedMid = crease +
                            (bandWidth / PI.toFloat()) * sin(angle)
                        mappedMid += fingerBias * bandWidth * sin(angle)

                        val projection = abs(cosine).coerceAtLeast(0.055f)
                        val translation = mappedMid - sourceMid

                        withTransform({ translate(left = translation, top = 0f) }) {
                            scale(
                                scaleX = projection,
                                scaleY = 1f,
                                pivot = Offset(sourceMid, height * touchY)
                            ) {
                                clipRect(left - 1.5f, 0f, right + 1.5f, height) {
                                    if (cosine >= 0f) {
                                        drawLayer(frontLayer)
                                    } else {
                                        // Two mirrors cancel: the reverse page is readable
                                        // when the physical sheet reaches the opposite side.
                                        scale(
                                            scaleX = -1f,
                                            scaleY = 1f,
                                            pivot = Offset(sourceMid, height * touchY)
                                        ) {
                                            drawLayer(backLayer)
                                        }
                                    }

                                    val shade = (0.04f + 0.17f * sin(angle).coerceAtLeast(0f))
                                        .coerceIn(0f, 0.20f)
                                    drawRect(
                                        color = Color.Black.copy(alpha = shade),
                                        topLeft = Offset(left, 0f),
                                        size = Size((right - left).coerceAtLeast(1f), height)
                                    )
                                }
                            }
                        }
                    }
                }

                val seamAlpha = (0.08f + 0.14f * wave).coerceAtMost(0.22f)
                drawLine(
                    color = Color.Black.copy(alpha = seamAlpha),
                    start = Offset(crease.coerceIn(0f, width), 0f),
                    end = Offset(crease.coerceIn(0f, width), height),
                    strokeWidth = (3f + 7f * wave)
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.10f * wave),
                    start = Offset((crease + 2f).coerceIn(0f, width), 0f),
                    end = Offset((crease + 2f).coerceIn(0f, width), height),
                    strokeWidth = 1.5f
                )
            } else {
                // LEFT page -> RIGHT. Mirror of the forward geometry.
                val crease = width * p
                val bendStart = (crease - bandWidth).coerceAtLeast(0f)

                if (crease < width) {
                    clipRect((crease - 1f).coerceAtLeast(0f), 0f, width, height) {
                        drawLayer(frontLayer)
                    }
                }

                if (bendStart > 0f) {
                    val pivotX = crease - bandWidth / 2f
                    scale(
                        scaleX = -1f,
                        scaleY = 1f,
                        pivot = Offset(pivotX, height * touchY)
                    ) {
                        clipRect(0f, 0f, bendStart, height) {
                            drawLayer(backLayer)
                            drawRect(
                                color = paperTint,
                                topLeft = Offset.Zero,
                                size = Size(bendStart, height)
                            )
                        }
                    }
                }

                if (bandWidth > 1f) {
                    repeat(stripCount) { i ->
                        val sourceRight = crease - bandWidth * i / stripCount
                        val sourceLeft = crease - bandWidth * (i + 1) / stripCount
                        if (sourceRight <= 0f) return@repeat

                        val left = sourceLeft.coerceIn(0f, width)
                        val right = sourceRight.coerceIn(0f, width)
                        val sourceMid = (left + right) / 2f
                        val s = (crease - sourceMid).coerceIn(0f, bandWidth)
                        val local = (s / bandWidth).coerceIn(0f, 1f)
                        val angle = PI.toFloat() * local
                        val cosine = cos(angle)

                        var mappedMid = crease -
                            (bandWidth / PI.toFloat()) * sin(angle)
                        mappedMid -= fingerBias * bandWidth * sin(angle)

                        val projection = abs(cosine).coerceAtLeast(0.055f)
                        val translation = mappedMid - sourceMid

                        withTransform({ translate(left = translation, top = 0f) }) {
                            scale(
                                scaleX = projection,
                                scaleY = 1f,
                                pivot = Offset(sourceMid, height * touchY)
                            ) {
                                clipRect(left - 1.5f, 0f, right + 1.5f, height) {
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

                                    val shade = (0.04f + 0.17f * sin(angle).coerceAtLeast(0f))
                                        .coerceIn(0f, 0.20f)
                                    drawRect(
                                        color = Color.Black.copy(alpha = shade),
                                        topLeft = Offset(left, 0f),
                                        size = Size((right - left).coerceAtLeast(1f), height)
                                    )
                                }
                            }
                        }
                    }
                }

                val seamAlpha = (0.08f + 0.14f * wave).coerceAtMost(0.22f)
                drawLine(
                    color = Color.Black.copy(alpha = seamAlpha),
                    start = Offset(crease.coerceIn(0f, width), 0f),
                    end = Offset(crease.coerceIn(0f, width), height),
                    strokeWidth = (3f + 7f * wave)
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.10f * wave),
                    start = Offset((crease - 2f).coerceIn(0f, width), 0f),
                    end = Offset((crease - 2f).coerceIn(0f, width), height),
                    strokeWidth = 1.5f
                )
            }
        }
    }
}

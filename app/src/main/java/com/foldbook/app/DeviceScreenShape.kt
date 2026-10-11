package com.foldbook.app

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Matches a single reading page to the physical rounded corners reported by Android.
 * Fold/two-page layouts intentionally do not use this shape.
 */
@Composable
internal fun singlePageDeviceShape(
    fallbackRadius: Dp = 10.dp,
    horizontalInset: Dp = 3.dp,
    verticalInset: Dp = 4.dp
): Shape {
    val fallback = AbsoluteRoundedCornerShape(fallbackRadius)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback

    val view = LocalView.current
    val density = LocalDensity.current
    val windowInsets = view.rootWindowInsets ?: return fallback
    val pageInset = minOf(horizontalInset, verticalInset)

    fun radius(position: Int): Dp {
        val radiusPx = windowInsets.getRoundedCorner(position)?.radius ?: 0
        if (radiusPx <= 0) return fallbackRadius

        val detected = with(density) { radiusPx.toDp() } - pageInset
        return if (detected > fallbackRadius) detected else fallbackRadius
    }

    return AbsoluteRoundedCornerShape(
        topLeft = radius(RoundedCorner.POSITION_TOP_LEFT),
        topRight = radius(RoundedCorner.POSITION_TOP_RIGHT),
        bottomRight = radius(RoundedCorner.POSITION_BOTTOM_RIGHT),
        bottomLeft = radius(RoundedCorner.POSITION_BOTTOM_LEFT)
    )
}

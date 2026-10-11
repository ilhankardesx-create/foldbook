package com.foldbook.app

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-page reader surfaces can sit very close to the physical display edge.
 * On Android 12+ match those page corners to the display's reported rounded
 * corners, compensating for the small page inset. Older devices keep the
 * existing FoldBook radius as a safe fallback.
 */
internal val LocalMatchedPageShape = staticCompositionLocalOf<RoundedCornerShape?> { null }

@Composable
internal fun deviceMatchedPageShape(
    horizontalInset: Dp,
    verticalInset: Dp,
    fallback: Dp
): RoundedCornerShape {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return RoundedCornerShape(fallback)
    }

    val view = LocalView.current
    val density = LocalDensity.current
    val insets = view.rootWindowInsets ?: return RoundedCornerShape(fallback)
    val pageInset = maxOf(horizontalInset, verticalInset)

    fun radius(position: Int): Dp {
        val radiusPx = insets.getRoundedCorner(position)?.radius ?: return 0.dp
        return (with(density) { radiusPx.toDp() } - pageInset).coerceAtLeast(0.dp)
    }

    // android.view.RoundedCorner positions:
    // 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
    return RoundedCornerShape(
        topStart = radius(0),
        topEnd = radius(1),
        bottomEnd = radius(2),
        bottomStart = radius(3)
    )
}

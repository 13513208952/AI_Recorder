package com.example.airecorder03.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive content widths. Target devices are phones/tablets ≥1080×1980 — most
 * phones report ~400dp width, but foldables/tablets may report 700dp+. We cap
 * content width on wide layouts so chat and cards don't stretch painfully wide.
 */
object ContentWidth {
    val Chat: Dp = 720.dp
    val List: Dp = 900.dp
    val Detail: Dp = 900.dp
}

/** Max width for a single chat bubble, as a fraction of available content width. */
const val ChatBubbleMaxWidthFraction: Float = 0.78f

/** Common corner radii used throughout the app for a consistent rounded feel. */
object CornerRadii {
    val Small: Dp = 10.dp
    val Medium: Dp = 16.dp
    val Large: Dp = 22.dp
    val XLarge: Dp = 28.dp
    val Chip: Dp = 12.dp
}

/** Elevation tokens — a bit higher than material default for a livelier surface. */
object Elevations {
    val Card: Dp = 2.dp
    val RaisedCard: Dp = 6.dp
    val Pill: Dp = 3.dp
}

/** True when the current configuration is "wide" — tablet / landscape-ish. */
@Composable
fun isWideLayout(): Boolean {
    val w = LocalConfiguration.current.screenWidthDp
    return w >= 600
}

/** The content max-width for a chat column at the current screen size. */
@Composable
fun chatContentMaxWidth(): Dp {
    val w = LocalConfiguration.current.screenWidthDp
    return if (w >= 700) ContentWidth.Chat else Dp.Unspecified
}

/** The content max-width for list/detail columns at the current screen size. */
@Composable
fun listContentMaxWidth(): Dp {
    val w = LocalConfiguration.current.screenWidthDp
    return if (w >= 800) ContentWidth.List else Dp.Unspecified
}

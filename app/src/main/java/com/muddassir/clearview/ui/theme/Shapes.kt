package com.muddassir.clearview.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's corner radii, as Material's shape scale.
 *
 * This exists so a dialog, a bottom sheet, a menu, a text field, a FAB and a
 * tooltip all round themselves the way the app's own cards do — the alternative
 * is passing a `shape` at every call site, which is how an app ends up with six
 * corner radii and no two screens that agree.
 *
 * The ramp is deliberately tighter at the top than Material's baseline (which
 * runs from 4dp to 28dp): ClearView's smallest control is a chip or a badge, and
 * a 4dp corner on a 20dp-tall pill reads as a rectangle with dented corners.
 * Each step is one of the tokens in [CvTokens.Radius], so a value that changes
 * here changes in the hand-built surfaces too.
 */
internal val ClearViewShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(CvTokens.Radius.sm),
    medium = RoundedCornerShape(CvTokens.Radius.md),
    large = RoundedCornerShape(CvTokens.Radius.lg),
    extraLarge = RoundedCornerShape(CvTokens.Radius.xl)
)

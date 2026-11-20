package dev.kudde.android.ui.terminal

import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The direction the title bar's content bounces at a tab's end: the swipe's. */
enum class Bounce { LEFT, RIGHT }

/** What a drag on the title bar did (design D4); the title bar plays it. */
sealed interface TitleBarStep {
    /** Another terminal opened: the light haptic (`CLOCK_TICK`). */
    data object Moved : TitleBarStep

    /** Nothing to open: the end haptic (`REJECT`); a bounce only sideways, with animations on. */
    data class End(val bounce: Bounce?) : TitleBarStep

    /** The compose sheet holds text: the end haptic, never a bounce. */
    data object Refused : TitleBarStep
}

/**
 * Null for a drag shorter than [threshold] in its longer direction. A refusal is
 * decided before [onTab] or [onSwipe] is called, so neither runs while refused.
 * Sideways ([dx] longer): [onTab] with next = finger moving left; up and down:
 * [onSwipe] with forward = finger moving up.
 */
fun titleBarStep(
    dx: Float,
    dy: Float,
    threshold: Float,
    refused: Boolean,
    animationsOn: Boolean,
    onTab: (next: Boolean) -> Boolean,
    onSwipe: (forward: Boolean) -> Boolean,
): TitleBarStep? {
    val sideways = abs(dx) > abs(dy)
    if ((if (sideways) abs(dx) else abs(dy)) < threshold) return null
    if (refused) return TitleBarStep.Refused
    return if (sideways) {
        if (onTab(dx < 0)) TitleBarStep.Moved
        else TitleBarStep.End(if (animationsOn) (if (dx < 0) Bounce.LEFT else Bounce.RIGHT) else null)
    } else {
        if (onSwipe(dy < 0)) TitleBarStep.Moved else TitleBarStep.End(null)
    }
}

/**
 * The bounce at a tab's end (design D3): the bar's content moves this far in the
 * swipe's direction and springs back in about [TITLE_BAR_BOUNCE_MS]. These are
 * D3's values until task 4.1 adds `animations.titleBarBounce` to the tokens.
 */
internal val TITLE_BAR_BOUNCE_DISTANCE = 12.dp
internal const val TITLE_BAR_BOUNCE_MS = 200

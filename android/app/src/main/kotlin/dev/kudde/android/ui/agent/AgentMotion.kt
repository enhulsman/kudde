package dev.kudde.android.ui.agent

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

/** Why the host changes; a predictive pop and a button pop use the same entry. */
enum class AgentChangeReason { Push, Pop, BaseSwitch, Leave }

data class AgentFrame(
    val alpha: Float = 1f, val scale: Float = 1f,
    val x: Float = 0f, val y: Float = 0f,
    val dim: Float = 0f, val radiusDp: Float = 0f,
)

data class AgentMotionSpec(
    val name: String,
    val progressSpec: FiniteAnimationSpec<Float> = snap(),
) {
    fun withDurationScale(scale: Float) = if (scale == 0f) copy(progressSpec = snap()) else this

    /** Geometry uses pixels; radii stay in dp until the host applies its density. */
    fun frame(progress: Float, incoming: Boolean, width: Float, height: Float, tile: Rect?): AgentFrame {
        val p = progress.coerceIn(0f, 1f)
        return when (name) {
            "toChat", "toTerminal" -> {
                val time = (p * AgentMotion.fadeDurationNanos).toLong()
                val enterTime = (time - AgentMotion.fadeOut.durationNanos).coerceAtLeast(0L)
                if (incoming) AgentFrame(
                    alpha = AgentMotion.fadeIn.getValueFromNanos(enterTime).coerceIn(0f, 1f),
                    scale = AgentMotion.fadeScale + (1 - AgentMotion.fadeScale) * AgentMotion.fadeSize.getValueFromNanos(enterTime),
                ) else AgentFrame(alpha = (1 - AgentMotion.fadeOut.getValueFromNanos(time)).coerceIn(0f, 1f))
            }
            "pushChanges", "popChanges", "leave" -> {
                val t = if (name == "pushChanges") p else 1 - p
                val cover = if (name == "pushChanges") incoming else !incoming
                if (cover) AgentFrame(x = width * (1 - t))
                else AgentFrame(x = -AgentMotion.baseShift * width * t, dim = t)
            }
            "pushOverview", "popOverview" -> {
                val t = if (name == "pushOverview") p else 1 - p
                val screen = if (name == "pushOverview") !incoming else incoming
                if (screen && tile != null) AgentFrame(
                    scale = 1 + (minOf(tile.width / width, tile.height / height) - 1) * t,
                    x = tile.left * t, y = tile.top * t, radiusDp = AgentMotion.tileRadius * t,
                ) else if (!screen) AgentFrame(alpha = t) else AgentFrame()
            }
            "menu" -> AgentMotion.menuFrame(p, incoming)
            else -> AgentFrame()
        }
    }
}

/** Values from the signed-off motion prototype: openspec/changes/screen-stack/motion.md. */
object AgentMotion {
    val springs: Map<String, SpringSpec<Float>> = mapOf(
        "spatial" to spring(stiffness = 380f, dampingRatio = .8f),
        "fastSpatial" to spring(stiffness = 800f, dampingRatio = .6f),
        "effects" to spring(stiffness = 1600f, dampingRatio = 1f),
        "fastEffects" to spring(stiffness = 3800f, dampingRatio = 1f),
        "screen" to spring(stiffness = 380f, dampingRatio = 1f),
    )
    val fadeScale = .96f
    val baseShift = .12f
    val tileRadius = 14f
    val tileStartMs = 70L
    val tileStaggerMs = 45L
    val tileScale = .88f
    val tileOpacityRate = 1.4f
    val tileExitScale = .94f
    val gridColumns = 2
    val gridPadding = 16f
    val gridGap = 12f
    val labelHeight = 26f
    val menuScale = .82f
    val menuExitScale = .96f
    val menuOpacityRate = 2f
    val itemRate = 1.6f
    val itemStagger = .12f
    val itemOffset = -4f
    val titleFadeMs = 110
    val backScale = .90f
    val backShift = 24f
    val backFollow = .12f
    val backFollowLimit = 28f
    val backRadius = 28f
    val backBaseRelease = .45f
    val backDimRelease = .55f
    val backThreshold = .33f
    val backFling = 450f
    val backFlingThreshold = .04f
    val graphiteAlpha = .42f
    val paperAlpha = .16f
    val paperRed = 20
    val paperGreen = 20
    val paperBlue = 30
    // Numeric contract includes the prototype's distance denominator (.55), and bounds (0, 1).
    val sourceNumbers = setOf(-baseShift, -backFollowLimit, 0f, 1f, fadeScale, baseShift, 42f, paperRed.toFloat(), paperGreen.toFloat(), paperBlue.toFloat(), 16f,
        backScale, backShift, backFollowLimit, backRadius, backBaseRelease, backDimRelease,
        backThreshold, backFling, backFlingThreshold, tileRadius, tileStartMs.toFloat(), tileStaggerMs.toFloat(),
        tileOpacityRate, tileScale, gridColumns.toFloat(), gridPadding, gridGap, labelHeight, tileExitScale,
        menuScale, menuOpacityRate, itemRate, itemOffset, menuExitScale, titleFadeMs.toFloat())

    internal val fadeOut = TargetBasedAnimation(springs.getValue("fastEffects"), Float.VectorConverter, 0f, 1f)
    internal val fadeIn = TargetBasedAnimation(springs.getValue("effects"), Float.VectorConverter, 0f, 1f)
    internal val fadeSize = TargetBasedAnimation(springs.getValue("screen"), Float.VectorConverter, 0f, 1f)
    internal val fadeDurationNanos = fadeOut.durationNanos + maxOf(fadeIn.durationNanos, fadeSize.durationNanos)
    // A linear playhead lets each channel follow its own signed-off spring.
    private val fadeThrough = tween<Float>(durationMillis = (fadeDurationNanos / 1_000_000L).toInt(), easing = LinearEasing)
    val toChat = AgentMotionSpec("toChat", fadeThrough)
    val toTerminal = AgentMotionSpec("toTerminal", fadeThrough)
    val pushChanges = AgentMotionSpec("pushChanges", springs.getValue("screen"))
    val popChanges = AgentMotionSpec("popChanges", springs.getValue("screen"))
    val pushOverview = AgentMotionSpec("pushOverview", springs.getValue("spatial"))
    val popOverview = AgentMotionSpec("popOverview", springs.getValue("spatial"))
    val menu = AgentMotionSpec("menu", springs.getValue("fastSpatial"))
    val leave = AgentMotionSpec("leave", springs.getValue("screen"))
    val entries = listOf(toChat, toTerminal, pushChanges, popChanges, pushOverview, popOverview, menu, leave)

    fun scrim(dark: Boolean) = if (dark) Color.Black.copy(alpha = graphiteAlpha)
        else Color(paperRed, paperGreen, paperBlue).copy(alpha = paperAlpha)
    fun tileDelay(index: Int) = tileStartMs + tileStaggerMs * index
    fun finishesBack(progress: Float, velocity: Float) = progress > backThreshold ||
        (progress > backFlingThreshold && velocity > backFling)
    fun predictiveFrame(p: Float, rightEdge: Boolean, dyDp: Float) = AgentFrame(
        scale = 1 - (1 - backScale) * p,
        x = backShift * p * if (rightEdge) -1 else 1,
        y = (backFollow * dyDp).coerceIn(-backFollowLimit, backFollowLimit) * p,
        radiusDp = backRadius * p,
    )
    fun menuFrame(p: Float, opening: Boolean) = if (opening)
        AgentFrame(alpha = minOf(1f, menuOpacityRate * p), scale = menuScale + (1 - menuScale) * p)
        else AgentFrame(alpha = 1 - p, scale = 1 + (menuExitScale - 1) * p)
    fun menuItem(p: Float, index: Int): AgentFrame {
        val t = (itemRate * p - itemStagger * index).coerceIn(0f, 1f)
        return AgentFrame(alpha = t, y = itemOffset * (1 - t))
    }

    fun reason(from: Any, to: Any): AgentChangeReason = when {
        to is AgentScreen.Changes || to is AgentScreen.Overview -> AgentChangeReason.Push
        from is AgentScreen.Changes || from is AgentScreen.Overview -> AgentChangeReason.Pop
        else -> AgentChangeReason.BaseSwitch
    }

    fun transitionFor(from: Any, to: Any?, reason: AgentChangeReason): AgentMotionSpec = when (reason) {
        AgentChangeReason.Leave -> leave
        AgentChangeReason.Pop -> when (from) {
            is AgentScreen.Overview -> popOverview
            else -> popChanges
        }
        AgentChangeReason.Push -> when (to) {
            is AgentScreen.Overview -> pushOverview
            else -> pushChanges
        }
        AgentChangeReason.BaseSwitch -> if (to == AgentScreen.Chat) toChat else toTerminal
    }
}

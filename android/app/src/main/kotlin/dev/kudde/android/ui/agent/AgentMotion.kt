package dev.kudde.android.ui.agent

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap

/** Why the host changes; a predictive pop and a button pop use the same entry. */
enum class AgentChangeReason { Push, Pop, BaseSwitch, Leave }

/** No visual transforms until task 6.1; progress is already seekable. */
data class AgentMotionSpec(
    val name: String,
    val progressSpec: FiniteAnimationSpec<Float> = snap(),
)

/** Placeholder table. Values are to come from the signed-off motion prototype. */
object AgentMotion {
    val toChat = AgentMotionSpec("toChat") // from the signed-off motion prototype
    val toTerminal = AgentMotionSpec("toTerminal") // from the signed-off motion prototype
    val pushChanges = AgentMotionSpec("pushChanges") // from the signed-off motion prototype
    val popChanges = AgentMotionSpec("popChanges") // from the signed-off motion prototype
    val pushOverview = AgentMotionSpec("pushOverview") // from the signed-off motion prototype
    val popOverview = AgentMotionSpec("popOverview") // from the signed-off motion prototype
    val menu = AgentMotionSpec("menu") // from the signed-off motion prototype
    val leave = AgentMotionSpec("leave") // from the signed-off motion prototype

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

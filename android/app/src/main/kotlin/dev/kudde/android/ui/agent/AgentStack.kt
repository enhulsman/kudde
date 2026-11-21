package dev.kudde.android.ui.agent

sealed interface AgentScreen {
    data object Terminal : AgentScreen
    data object Chat : AgentScreen
    data object Changes : AgentScreen
    data class Overview(val spaceId: String, val tabId: String) : AgentScreen
}

/** One cover over the chat switch's base. The host supplies its observation hook. */
class AgentStack(private val changed: () -> Unit = {}) {
    var cover: AgentScreen? = null
        private set

    fun top(chat: Boolean): AgentScreen = cover ?: if (chat) AgentScreen.Chat else AgentScreen.Terminal

    fun push(cover: AgentScreen) {
        require(cover is AgentScreen.Changes || cover is AgentScreen.Overview)
        if (this.cover != null) return
        this.cover = cover
        changed()
    }

    fun pop(): Boolean {
        if (cover == null) return false
        cover = null
        changed()
        return true
    }
}

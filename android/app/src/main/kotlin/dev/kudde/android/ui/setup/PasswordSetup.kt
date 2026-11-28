package dev.kudde.android.ui.setup

import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.kudde_mobile.ProbeOutcome
import uniffi.kudde_mobile.ReachKind
import uniffi.kudde_mobile.SetupOutcome

/** The password field's text. Held in memory only, never saved (design D12). */
interface SecretField {
    fun text(): String
    fun clear()
}

/** The plain holder the production field wraps; [clear] drops the reference. */
class StringSecretField : SecretField {
    var value: String = ""
    override fun text() = value
    override fun clear() { value = "" }
}

/** The production field: Compose's text state, whose text is read once for the call and emptied after. */
class TextFieldStateSecret : SecretField {
    val state = androidx.compose.foundation.text.input.TextFieldState()
    override fun text() = state.text.toString()
    override fun clear() = state.clearText()
}

/** Cancel for a setup call in flight: the core checks it before the password goes and before the key line is written. */
interface SetupHandle {
    fun cancel()

    object None : SetupHandle { override fun cancel() = Unit }
}

/** The two Rust calls of the password way; each answers once, off the caller's thread. */
interface PasswordPorts {
    fun probe(host: String, port: Int, user: String, pinned: String, done: (ProbeOutcome) -> Unit)
    fun setup(host: String, port: Int, user: String, pinned: String, password: String, signedIn: () -> Unit, done: (SetupOutcome) -> Unit): SetupHandle

    /** For flows that never use the password way. */
    object None : PasswordPorts {
        override fun probe(host: String, port: Int, user: String, pinned: String, done: (ProbeOutcome) -> Unit) = Unit
        override fun setup(host: String, port: Int, user: String, pinned: String, password: String, signedIn: () -> Unit, done: (SetupOutcome) -> Unit): SetupHandle = SetupHandle.None
    }
}

/** What the password detail of step 2 shows. R-numbers are design D12's texts. */
sealed interface PasswordPanel {
    data object Hidden : PasswordPanel
    data object Probing : PasswordPanel

    /** The field; [prompt] is the machine's own first prompt for keyboard-interactive. */
    data class Field(val prompt: String?) : PasswordPanel
    /** The password is being checked: "Signing in" spins, nothing is ticked. */
    data object Working : PasswordPanel

    /** The machine accepted the password: "Signing in" is ticked and "Adding the key" spins. */
    data object WorkingKey : PasswordPanel

    /** R10: no password to give. */
    data object NoPassword : PasswordPanel

    /** R9, or R15 from the second refusal in one visit. */
    data class WrongPassword(val again: Boolean) : PasswordPanel

    /** R11. */
    data object MoreThanPassword : PasswordPanel

    /** R12. */
    data object Dropped : PasswordPanel

    /** R14, with the exit code. */
    data class ScriptFailed(val code: Int) : PasswordPanel
    data class Reach(val kind: ReachKind) : PasswordPanel
    data object NetworkOff : PasswordPanel
    data class ChangedKey(val presented: String) : PasswordPanel
    data object Done : PasswordPanel

    /** R16: done, yet the phone key is still refused. */
    data object StillRefused : PasswordPanel

    /** Cancelled before the password was sent: nothing was sent, nothing changed. */
    data object Cancelled : PasswordPanel

    /** Cancelled after signing in, before the key line: signed out again, nothing changed. */
    data object CancelledSignedIn : PasswordPanel

    /** Cancelled after the key went in: the key is on the machine. */
    data object CancelledKeyAdded : PasswordPanel
}

/**
 * The password way's state machine, apart from the hub: probe, field, one
 * setup call, its outcome. Answers from an earlier run (after the panel was
 * closed or reopened) are dropped. The password is read from [field], which is
 * emptied before the setup call starts and whenever the panel closes.
 */
class PasswordSetup(private val ports: PasswordPorts, val field: SecretField) {
    var panel by mutableStateOf<PasswordPanel>(PasswordPanel.Hidden)
        private set

    /** After R16 the password is not offered again in this visit. */
    var unavailable by mutableStateOf(false)
        private set
    private var refusals = 0
    private var run = 0
    private var handle: SetupHandle? = null

    private val running get() = panel == PasswordPanel.Working || panel == PasswordPanel.WorkingKey

    /** "Cancel" while the key is being added: the core is asked to stop, and the panel waits for what it says. */
    fun cancelRun(): Boolean {
        if (!running) return false
        handle?.cancel()
        return true
    }

    /** A new visit to step 2: R9 again, the password available again. */
    fun newVisit() {
        refusals = 0
        unavailable = false
        close()
    }

    fun close() {
        if (running) handle?.cancel()
        handle = null
        run++
        field.clear()
        panel = PasswordPanel.Hidden
    }

    fun open(host: String, port: Int, user: String, pinned: String, onChangedKey: () -> Unit) {
        if (unavailable) return
        val mine = ++run
        field.clear()
        panel = PasswordPanel.Probing
        ports.probe(host, port, user, pinned) { outcome ->
            if (mine != run) return@probe
            panel = when (outcome) {
                ProbeOutcome.Password -> PasswordPanel.Field(null)
                is ProbeOutcome.KeyboardInteractive -> PasswordPanel.Field(outcome.prompt)
                ProbeOutcome.NoPasswordMethod -> PasswordPanel.NoPassword
                is ProbeOutcome.HostKeyChanged -> PasswordPanel.ChangedKey(outcome.presented).also { onChangedKey() }
                ProbeOutcome.NetworkOff -> PasswordPanel.NetworkOff
                is ProbeOutcome.Unreachable -> PasswordPanel.Reach(outcome.kind)
            }
        }
    }

    /** "Add the key": the typed text goes out once, and the field is empty before the call starts. */
    fun addKey(host: String, port: Int, user: String, pinned: String, onDone: () -> Unit, onChangedKey: () -> Unit) {
        val typeable = panel is PasswordPanel.Field || panel is PasswordPanel.WrongPassword ||
            panel is PasswordPanel.MoreThanPassword || panel is PasswordPanel.Dropped
        if (!typeable) return
        val password = field.text()
        if (password.isEmpty()) return
        field.clear()
        val mine = ++run
        panel = PasswordPanel.Working
        handle = ports.setup(host, port, user, pinned, password, signedIn = {
            if (mine == run && panel == PasswordPanel.Working) panel = PasswordPanel.WorkingKey
        }) { outcome ->
            if (mine != run) return@setup
            handle = null
            panel = when (outcome) {
                SetupOutcome.Done -> PasswordPanel.Done.also { onDone() }
                SetupOutcome.WrongPassword -> PasswordPanel.WrongPassword(again = ++refusals >= 2)
                SetupOutcome.NoPasswordMethod -> PasswordPanel.NoPassword
                SetupOutcome.MoreThanPassword -> PasswordPanel.MoreThanPassword
                SetupOutcome.Dropped -> PasswordPanel.Dropped
                is SetupOutcome.ScriptFailed -> PasswordPanel.ScriptFailed(outcome.code.toInt())
                is SetupOutcome.HostKeyChanged -> PasswordPanel.ChangedKey(outcome.presented).also { onChangedKey() }
                SetupOutcome.NetworkOff -> PasswordPanel.NetworkOff
                is SetupOutcome.Unreachable -> PasswordPanel.Reach(outcome.kind)
                SetupOutcome.Cancelled -> PasswordPanel.Cancelled
                SetupOutcome.CancelledSignedIn -> PasswordPanel.CancelledSignedIn
                SetupOutcome.CancelledKeyAdded -> PasswordPanel.CancelledKeyAdded.also { onDone() }
            }
        }
    }

    /** The hub refused the phone key again after a done run: R16, and no more passwords this visit. */
    fun phoneKeyStillRefused() {
        if (panel == PasswordPanel.Done) {
            panel = PasswordPanel.StillRefused
            unavailable = true
        }
    }
}

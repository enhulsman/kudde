package dev.kudde.android.ui.setup

import android.content.SharedPreferences
import androidx.core.content.edit

/** How the person adds the phone key on step 2 (design D2). */
enum class KeyChoice { PASSWORD, MANUAL }

/**
 * Where first setup stands (design D1). [UNSET] is no record at all (a fresh
 * install, or an update); [WELCOME] means the welcome shows (setup not in
 * progress, typed fields may be kept); [IN_PROGRESS] resumes the checkpoint;
 * [COMPLETED] is set by a real connection at step 3 and never goes back.
 */
enum class SetupPhase { UNSET, WELCOME, IN_PROGRESS, COMPLETED }

/**
 * The non-secret checkpoint: the machine if it is listed, the furthest step,
 * the key-install choice, the entry point and the unsent fields. Never a
 * password, a refusal count or a password session.
 */
data class SetupCheckpoint(
    val machine: String?,
    val step: Int,
    val choice: KeyChoice?,
    val origin: String,
    val name: String,
    val host: String,
    val port: String,
    val user: String,
    val added: Boolean,
    val reached: Boolean,
)

/** Persistent first-setup progress; app-private preferences, excluded from backup and transfer. */
interface SetupProgress {
    var phase: SetupPhase
    var checkpoint: SetupCheckpoint?
}

class MemorySetupProgress(
    override var phase: SetupPhase = SetupPhase.UNSET,
    override var checkpoint: SetupCheckpoint? = null,
) : SetupProgress

class PrefsSetupProgress(private val prefs: SharedPreferences) : SetupProgress {
    override var phase: SetupPhase
        get() = prefs.getString(PHASE, null)?.let { runCatching { SetupPhase.valueOf(it) }.getOrNull() } ?: SetupPhase.UNSET
        set(value) = prefs.edit { putString(PHASE, value.name) }

    override var checkpoint: SetupCheckpoint?
        get() = if (!prefs.contains(STEP)) null else SetupCheckpoint(
            machine = prefs.getString(MACHINE, null),
            step = prefs.getInt(STEP, 1),
            choice = prefs.getString(CHOICE, null)?.let { runCatching { KeyChoice.valueOf(it) }.getOrNull() },
            origin = prefs.getString(ORIGIN, "Agents") ?: "Agents",
            name = prefs.getString(NAME, "").orEmpty(),
            host = prefs.getString(HOST, "").orEmpty(),
            port = prefs.getString(PORT, "22").orEmpty(),
            user = prefs.getString(USER, "").orEmpty(),
            added = prefs.getBoolean(ADDED, false),
            reached = prefs.getBoolean(REACHED, false),
        )
        set(value) = prefs.edit {
            if (value == null) {
                for (key in listOf(MACHINE, STEP, CHOICE, ORIGIN, NAME, HOST, PORT, USER, ADDED, REACHED)) remove(key)
            } else {
                putString(MACHINE, value.machine)
                putInt(STEP, value.step)
                putString(CHOICE, value.choice?.name)
                putString(ORIGIN, value.origin)
                putString(NAME, value.name)
                putString(HOST, value.host)
                putString(PORT, value.port)
                putString(USER, value.user)
                putBoolean(ADDED, value.added)
                putBoolean(REACHED, value.reached)
            }
        }

    companion object {
        const val FILE = "first_setup"
        private const val PHASE = "phase"
        private const val MACHINE = "machine"
        private const val STEP = "step"
        private const val CHOICE = "choice"
        private const val ORIGIN = "origin"
        private const val NAME = "name"
        private const val HOST = "host"
        private const val PORT = "port"
        private const val USER = "user"
        private const val ADDED = "added"
        private const val REACHED = "reached"
    }
}

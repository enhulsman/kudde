package dev.kudde.android.ui.setup

import dev.kudde.android.key.KeyManager
import java.util.concurrent.Executor
import uniffi.kudde_mobile.ProbeOutcome
import uniffi.kudde_mobile.ReachKind
import uniffi.kudde_mobile.SetupOutcome
import uniffi.kudde_mobile.SetupPassword
import uniffi.kudde_mobile.probePasswordMethods
import uniffi.kudde_mobile.setupWithPassword

/**
 * The Rust password way on a background thread, answering on [main]. The
 * phone key signs nothing here: only its public half goes into the script.
 */
class UniffiPasswordPorts(private val keys: KeyManager, private val main: Executor) : PasswordPorts {
    override fun probe(host: String, port: Int, user: String, pinned: String, done: (ProbeOutcome) -> Unit) {
        Thread {
            val outcome = probePasswordMethods(host, port.toUShort(), user, pinned)
            main.execute { done(outcome) }
        }.start()
    }

    override fun setup(host: String, port: Int, user: String, pinned: String, password: String, done: (SetupOutcome) -> Unit) {
        Thread {
            val outcome = try {
                // The public key is read first, and the native secret handle is closed as soon as the call returns.
                val publicKey = keys.publicKey()
                SetupPassword(password).use { secret ->
                    setupWithPassword(host, port.toUShort(), user, pinned, secret, publicKey, KeyManager.COMMENT)
                }
            } catch (e: Exception) {
                SetupOutcome.Unreachable(ReachKind.OTHER, "the phone key could not be read")
            }
            main.execute { done(outcome) }
        }.start()
    }
}

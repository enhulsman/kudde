package dev.kudde.android.testing

/**
 * Runs what is due on the main thread when a test ends; [KuddeTestRunner]
 * calls it inside the test's sandbox, before Robolectric resets the loopers.
 *
 * Compose dispatches its main-thread work (effects, recompositions, the
 * snapshot changes that drive them) through one dispatcher that lives as long
 * as the sandbox and posts a callback only when none is outstanding. A test
 * that ended with that callback queued lost it to the reset, and from then on
 * nothing on Compose's main thread ran in any later test of the run.
 */
object MainThreadDrain {
    @JvmStatic
    fun run() {
        // Bounded (BoundedFrames): a looping screen fails the test with a frame count instead of spinning here.
        BoundedFrames.drain()
    }
}

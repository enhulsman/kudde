package dev.kudde.android.testing

import org.robolectric.shadows.ShadowChoreographer

/**
 * The clock every test that opens a Compose `Popup` runs under (screen-stack D0).
 *
 * In this Robolectric (SDK 35, NATIVE graphics) a Popup window attached while
 * the Choreographer runs freely keeps the main looper in frames that never get
 * past one instant: a single `idleFor(16 ms)` does not return, the heap fills
 * with Robolectric's trace sections and the test dies minutes later
 * (PopupFrameProbeTest). With the Choreographer paused and frames stepped by
 * hand ([BoundedFrames]) opening, dismissing and tearing down a Popup take
 * three frames each.
 *
 * Call [paused] before the activity is built. Robolectric unpauses the
 * Choreographer when the test is reset, so nothing needs undoing.
 */
object PopupSafeClock {
    @JvmStatic
    fun paused() {
        ShadowChoreographer.setPaused(true)
    }
}

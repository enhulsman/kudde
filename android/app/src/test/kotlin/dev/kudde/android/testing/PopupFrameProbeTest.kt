package dev.kudde.android.testing

import android.os.Looper
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Popup
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * Probe for design D0 (screen-stack): how many frames does a bare Compose
 * `Popup` run while it opens, is dismissed and is torn down? Everything is
 * driven by [BoundedFrames]-style stepping, so a loop ends at the bound. The
 * probe counts with a Choreographer callback it posts once per step and never
 * from inside the callback, so the probe cannot itself be the recurring frame.
 * Results are printed as "PROBE ..." lines.
 */
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PopupFrameProbeTest {
    private val looper get() = shadowOf(Looper.getMainLooper())

    /** Steps of 16 ms until the looper has nothing due, at most [max]; returns (steps, framesSeenByTheProbe, idle). */
    private fun steps(max: Int = 60, perStep: () -> Unit = {}): Triple<Int, Int, Boolean> {
        var seen = 0
        var n = 0
        while (n < max) {
            Choreographer.getInstance().postFrameCallback { seen++ }
            looper.idleFor(Duration.ofMillis(16))
            perStep()
            n++
            if (looper.isIdle && n >= 3) break
        }
        return Triple(n, seen, looper.isIdle)
    }

    private fun report(label: String, t: Triple<Int, Int, Boolean>) =
        println("PROBE $label steps=${t.first} probeFrames=${t.second} idleAfter=${t.third}")

    @Test
    fun a_controlWithoutAPopup() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        c.get().setContent { BasicText("plain") }
        report("control(unpaused)", steps())
        c.pause().stop().destroy()
        report("control-teardown", steps())
    }

    // The unpaused variant is not run: observed (2026-11-21), it never leaves the first
    // idleFor(16 ms) of the open phase (the watchdog's stack sits inside one idleFor,
    // in Compose's dispatcher), so no bounded stepping can reach its end.

    @Test
    fun c_threePhasesPaused() = phases(paused = true)

    private fun phases(paused: Boolean) {
        val tag = if (paused) "paused" else "unpaused"
        ShadowChoreographer.setPaused(paused)
        try {
            val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            var open by mutableStateOf(false)
            c.get().setContent { BasicText("host"); if (open) Popup { BasicText("popup") } }
            report("$tag settle-host", steps())
            open = true
            report("$tag open", steps()); println("PROBE $tag open windows=${windowRoots().size} popupText=${semanticsNodes().any { it.texts() == listOf("popup") }}")
            open = false
            report("$tag dismiss", steps()); println("PROBE $tag dismiss windows=${windowRoots().size} popupText=${semanticsNodes().any { it.texts() == listOf("popup") }}")
            c.pause().stop().destroy()
            report("$tag teardown", steps())
        } finally {
            ShadowChoreographer.setPaused(false)
        }
    }
}

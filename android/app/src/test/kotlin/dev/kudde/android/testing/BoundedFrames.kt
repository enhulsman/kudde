package dev.kudde.android.testing

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/**
 * Drives the main looper in frames, with a bound.
 *
 * An unbounded `looper.idle()` returns only when nothing is due, so a screen
 * that keeps asking for the next frame (a looping popup) never lets a test
 * end. Here at most [MAX_FRAMES] frames of [FRAME_MS] run, and hitting the
 * bound fails with the count ("looped: 200 frames").
 */
object BoundedFrames {
    const val MAX_FRAMES = 200
    const val FRAME_MS = 16L

    class Looped(val frames: Int) : AssertionError("looped: $frames frames")

    /**
     * Runs frames until nothing is due now or within the next frame (a screen
     * that asks for a frame every 16 ms is "looping"); returns how many ran.
     * Throws [Looped] at the bound.
     */
    @JvmStatic
    @JvmOverloads
    fun settle(maxFrames: Int = MAX_FRAMES, minFrames: Int = 0): Int {
        val looper = shadowOf(Looper.getMainLooper())
        var frames = 0
        // With the Choreographer paused (PopupSafeClock) a change shows as nothing "due":
        // its frame only runs when stepped, so the first [minFrames] run regardless.
        while (frames < minFrames || busy(looper)) {
            if (frames >= maxFrames) throw Looped(frames)
            looper.idleFor(Duration.ofMillis(FRAME_MS))
            frames++
        }
        return frames
    }

    /** For the runner's end-of-test drain: runs what is due now, in bounded frames, ignoring work due later. */
    @JvmStatic
    @JvmOverloads
    fun drain(maxFrames: Int = MAX_FRAMES): Int {
        val looper = shadowOf(Looper.getMainLooper())
        var frames = 0
        while (!looper.isIdle) {
            if (frames >= maxFrames) throw Looped(frames)
            looper.idleFor(Duration.ofMillis(FRAME_MS))
            frames++
        }
        return frames
    }

    private fun busy(looper: org.robolectric.shadows.ShadowLooper): Boolean {
        if (!looper.isIdle) return true
        val next = looper.nextScheduledTaskTime
        return next != Duration.ZERO && next.toMillis() - SystemClock.uptimeMillis() <= FRAME_MS
    }

    /** Runs exactly [n] frames (the clock moves [n] x [FRAME_MS]); for tests that wait for time to pass. */
    @JvmStatic
    fun step(n: Int = 1) {
        require(n in 0..MAX_FRAMES) { "step bounded to $MAX_FRAMES frames: $n" }
        val looper = shadowOf(Looper.getMainLooper())
        repeat(n) { looper.idleFor(Duration.ofMillis(FRAME_MS)) }
    }
}

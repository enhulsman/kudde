package dev.kudde.android.testing

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(KuddeTestRunner::class)
class BoundedFramesTest {
    @Test
    fun aLoopingClockFailsInSecondsWithItsFrameCount() {
        val handler = Handler(Looper.getMainLooper())
        var posts = 0
        lateinit var again: Runnable
        again = Runnable { posts++; handler.postDelayed(again, 16) }
        handler.post(again)
        val started = System.nanoTime()
        try {
            BoundedFrames.settle(maxFrames = 50)
            fail("a looping clock must not settle")
        } catch (e: BoundedFrames.Looped) {
            assertEquals(50, e.frames)
            assertTrue(e.message, "looped: 50 frames" == e.message)
        } finally {
            handler.removeCallbacks(again)
        }
        assertTrue("took " + (System.nanoTime() - started) / 1_000_000 + " ms", System.nanoTime() - started < 10_000_000_000L)
        assertTrue("frames ran: $posts", posts in 50..60)
    }

    @Test
    fun aQuietLooperSettlesInZeroFrames() {
        assertEquals(0, BoundedFrames.settle())
    }

    @Test
    fun aChainOfFramesSettlesInItsFrames() {
        val handler = Handler(Looper.getMainLooper())
        var left = 5
        lateinit var next: Runnable
        next = Runnable { if (--left > 0) handler.postDelayed(next, 10) }
        handler.post(next)
        val frames = BoundedFrames.settle()
        assertEquals(0, left)
        assertTrue("frames $frames", frames in 2..5)
    }
}

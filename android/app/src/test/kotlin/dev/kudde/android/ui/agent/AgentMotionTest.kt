package dev.kudde.android.ui.agent

import androidx.compose.animation.core.SnapSpec
import androidx.compose.ui.geometry.Rect
import java.io.File
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(dev.kudde.android.testing.KuddeTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentMotionTest {
    private val steps = listOf(0f, .5f, 1f)
    private val tile = Rect(16f, 100f, 196f, 460f)

    @Test fun everyChangeHasAnEntryAndEachMatchesMotionMd() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "openspec/changes/screen-stack/motion.md").exists() }
        val source = File(root, "openspec/changes/screen-stack/motion.md").readText()
        val rows = Regex("\\| (\\w+) \\| ([\\d.]+) \\| ([\\d.]+) \\|").findAll(source).toList()
        assertEquals(5, rows.size)
        for (row in rows) {
            val spring = AgentMotion.springs.getValue(row.groupValues[1])
            assertEquals(row.groupValues[2].toFloat(), spring.stiffness, 0f)
            assertEquals(row.groupValues[3].toFloat(), spring.dampingRatio, 0f)
        }
        assertEquals(setOf("toChat", "toTerminal", "pushChanges", "popChanges", "pushOverview", "popOverview", "menu", "leave"), AgentMotion.entries.map { it.name }.toSet())
        // Compare every authored numeric token, not just a second copy of the springs.
        val perChange = source.substringAfter("## Per change").substringBefore("## Always")
        val numbers = Regex("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?").findAll(perChange)
            .map { it.value.toFloat() }.toSet()
        assertEquals(numbers, AgentMotion.sourceNumbers)
        assertTrue(AgentMotion.leave.progressSpec is SnapSpec<*>)
    }

    @Test fun everyChangeHasFramesAtStartHalfwayAndEnd() {
        for (spec in AgentMotion.entries) for (p in steps) for (incoming in listOf(false, true)) {
            val f = spec.frame(p, incoming, 400f, 800f, tile)
            assertTrue("${spec.name} at $p", f.alpha in 0f..1f && f.scale > 0f && f.x.isFinite() && f.y.isFinite())
        }
    }

    @Test fun chatAndTerminalFadeThroughWithNoSlide() {
        for (spec in listOf(AgentMotion.toChat, AgentMotion.toTerminal)) for (p in steps) {
            val out = spec.frame(p, false, 400f, 800f, tile)
            val into = spec.frame(p, true, 400f, 800f, tile)
            assertEquals(0f, out.x, 0f); assertEquals(0f, into.x, 0f)
            assertEquals(0f, out.alpha * into.alpha, 0f)
            if (p == 0f) { assertEquals(1f, out.alpha, 0f); assertEquals(.96f, into.scale, 0f) }
            if (p == 1f) { assertEquals(0f, out.alpha, 0f); assertEquals(1f, into.scale, 0f) }
        }
    }

    @Test fun changesSlidesInFromTheRightAndTheBaseShiftsLeftAndDims() {
        for (p in steps) {
            val cover = AgentMotion.pushChanges.frame(p, true, 400f, 800f, tile)
            val base = AgentMotion.pushChanges.frame(p, false, 400f, 800f, tile)
            assertEquals(400f * (1 - p), cover.x, .001f)
            assertEquals(-48f * p, base.x, .001f)
            assertEquals(p, base.dim, .001f)
            assertEquals(cover, AgentMotion.popChanges.frame(1 - p, false, 400f, 800f, tile))
        }
        assertEquals(.42f, AgentMotion.scrim(true).alpha, .001f)
        assertEquals(.16f, AgentMotion.scrim(false).alpha, .001f)
    }

    @Test fun theOverviewShrinksToItsTileAndTheTileGrowsBack() {
        for (p in steps) {
            val shrink = AgentMotion.pushOverview.frame(p, false, 400f, 800f, tile)
            val grow = AgentMotion.popOverview.frame(1 - p, true, 400f, 800f, tile)
            assertEquals(shrink, grow)
            assertEquals(1f + (tile.width / 400f - 1f) * p, shrink.scale, .001f)
            assertEquals(14f * p, shrink.radiusDp, .001f)
        }
        assertEquals(70L, AgentMotion.tileDelay(0))
        assertEquals(115L, AgentMotion.tileDelay(1))
    }

    @Test fun theMenuGrowsFromItsButton() {
        for (p in steps) {
            val frame = AgentMotion.menuFrame(p, true)
            assertEquals(.82f + .18f * p, frame.scale, .001f)
            assertEquals(minOf(1f, 2 * p), frame.alpha, .001f)
            assertEquals(1f - .04f * p, AgentMotion.menuFrame(p, false).scale, .001f)
        }
        assertEquals(-4f, AgentMotion.menuItem(0f, 0).y, 0f)
        assertEquals(0f, AgentMotion.menuItem(1f, 0).y, 0f)
    }

    @Test fun aCancelledPredictiveBackReturnsToFullScreenAndChangesNothing() {
        assertFalse(AgentMotion.finishesBack(.33f, 0f))
        assertFalse(AgentMotion.finishesBack(.04f, 451f))
        assertFalse(AgentMotion.finishesBack(.2f, -500f))
        val full = AgentMotion.predictiveFrame(0f, false, 100f)
        assertEquals(1f, full.scale, 0f); assertEquals(0f, full.x, 0f)
        for (p in steps) {
            val f = AgentMotion.predictiveFrame(p, false, 1000f)
            assertEquals(1f - .1f * p, f.scale, .001f)
            assertEquals(28f * p, f.y, .001f)
            assertEquals(28f * p, f.radiusDp, .001f)
        }
    }

    @Test fun aReleasedPredictiveBackFinishes() {
        assertTrue(AgentMotion.finishesBack(.34f, 0f))
        assertTrue(AgentMotion.finishesBack(.05f, 451f))
        assertFalse(AgentMotion.finishesBack(.05f, 450f))
    }

    @Test fun reducedMotionIsAnInstantCutForEveryChange() {
        for (spec in AgentMotion.entries) assertTrue(spec.withDurationScale(0f).progressSpec is SnapSpec<*>)
        val host = ScreenStackHostTest()
        try { host.reducedMotionCutsAndCommitsOrCancelsInOneFrame() }
        finally { host.close() }
    }

    @Test fun noTouchReachesTheScreenUnderneathDuringOrAfterATransition() {
        val host = ScreenStackHostTest()
        try { host.noTouchReachesTheScreenUnderneathDuringOrAfterATransition() }
        finally { host.close() }
    }
}

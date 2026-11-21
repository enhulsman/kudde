package dev.kudde.android.ui.agent

import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteBackTest {
    private var previousDurationScale: String? = null

    // These ownership checks inspect completed navigation without advancing motion.
    // Animated list/file Back is covered in AgentRouteLeakTest on the real host clock.
    @Before fun useInstantMotionForOwnershipChecks() {
        val resolver = org.robolectric.RuntimeEnvironment.getApplication().contentResolver
        val setting = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        previousDurationScale = android.provider.Settings.Global.getString(resolver, setting)
        android.provider.Settings.Global.putFloat(resolver, setting, 0f)
    }

    @After fun restoreMotionSetting() {
        android.provider.Settings.Global.putString(
            org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, previousDurationScale,
        )
    }

    private fun AgentRouteScenario.start() {
        activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, .5f, BackEventCompat.EDGE_LEFT))
    }

    // passes today: BackHandler ignores progress and cancellation, so cancellation changes nothing.
    @Test fun aCancelledBackGestureChangesNothing() = AgentRouteScenario().use {
        it.show()
        it.changes()
        val before = it.opened.view.calls.toList()
        it.start()
        it.rig.frames()
        assertNotNull("Back progress must not pop Changes", nodeSaying("Changes"))
        it.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        it.rig.frames(12)
        assertNotNull("cancelled Back must leave Changes on top", nodeSaying("Changes"))
        assertEquals("cancelled Back must not leave the agent", it.rig.key, it.rig.home.openKey)
        assertEquals("cancelled Back must not detach, send size or take over", before, it.opened.view.calls)
        assertEquals(1, it.rig.ports.opens.size)
    }

    // passes today: dispatching a committed gesture invokes ChangesRoute's existing BackHandler.
    @Test fun aCommittedBackGesturePopsTheCover() = AgentRouteScenario().use {
        it.show(chat = true)
        it.changes()
        val before = it.opened.view.calls.toList()
        it.start()
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        assertNull("committed Back must pop Changes", nodeSaying("Changes"))
        assertNotNull("committed Back returns to the chat", nodeSaying("More options"))
        assertNotNull(nodeSaying("Terminal"))
        assertEquals("committed Back must not leave the agent", it.rig.key, it.rig.home.openKey)
        assertEquals("popping a cover must not detach or resize", before, it.opened.view.calls)
    }

    // red until 5.2
    @Test fun twoBacksWithNoFrameBetweenPopThenLeaveOnce() = AgentRouteScenario().use {
        it.show()
        it.changes()
        val view = it.opened.view
        it.start()
        it.activity.onBackPressedDispatcher.onBackPressed()
        // Deliberately no frame: the second event must read the updated stack.
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        assertNull("second Back before recomposition must leave, not pop the stale cover twice", it.rig.home.openKey)
        assertEquals("two Backs release the agent once", 1, view.calls.count { c -> c == "detach" })
        assertEquals("two Backs destroy its view once", 1, view.calls.count { c -> c == "destroy" })
        it.rig.advance(800)
        assertEquals(1, it.rig.ports.opens.size)
    }

    // passes today: the base BackHandler closes the real AgentsHome.
    @Test fun backFromTheBaseLeaves() = AgentRouteScenario().use {
        it.show(chat = true)
        val view = it.opened.view
        it.start()
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        assertNull("Back from the base leaves the agent", it.rig.home.openKey)
        assertEquals(1, view.calls.count { c -> c == "detach" })
        assertEquals(1, view.calls.count { c -> c == "destroy" })
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.advance(800)
        assertEquals("a second Back at home detaches nothing more", 1, view.calls.count { c -> c == "detach" })
        assertEquals("nothing reopens", 1, it.rig.ports.opens.size)
    }
    @Test fun reducedMotionBaseBackCutsWithoutComposingAPreview() = AgentRouteScenario().use {
        it.show()
        val view = it.opened.view
        it.start()
        it.rig.frames()
        assertTrue(unmergedSemanticsNodes().none { n ->
            n.config.getOrNull(ScreenStackLeaveBaseKey) == true
        })
        assertEquals(it.rig.key, it.rig.home.openKey)
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames(1)
        assertNull(it.rig.home.openKey)
        assertEquals(1, view.calls.count { c -> c == "detach" })
    }

}

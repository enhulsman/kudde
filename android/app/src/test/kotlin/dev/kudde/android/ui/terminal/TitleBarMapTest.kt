package dev.kudde.android.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.ui.agent.AgentHost
import dev.kudde.android.ui.agent.AgentRouteRig
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The one-pane map opens the real overview, including the space's other tabs. */
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TitleBarMapTest {
    private lateinit var rig: AgentRouteRig
    private var controller: ActivityController<ComponentActivity>? = null

    @Before
    fun prepare() {
        rig = AgentRouteRig()
        rig.install()
        rig.publish(TitleBarFixtures.hub)
        rig.home.onSnapshot(TitleBarFixtures.hub)
    }

    @After
    fun close() {
        try { controller?.pause()?.stop()?.destroy() } finally { rig.close() }
    }

    private fun show(address: String, chat: Boolean) {
        rig.app.chatView.choose(chat)
        rig.home.open(AgentKey("workstation", address))
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller!!.get().setContent {
            KuddeTheme(dark = true) {
                rig.home.openKey?.let { AgentHost(rig.app, rig.home, it, rig.ports) }
            }
        }
        rig.await("the title bar shows") { nodeSaying("More options") != null }
    }

    private fun tap(label: String) {
        val node = nodeSaying(label)
        assertNotNull(label, node)
        assertTrue(node!!.click())
        rig.frames()
        val view = controller!!.get().window.decorView
        view.draw(Canvas(Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        rig.frames()
    }

    private fun soloOpensOverview(chat: Boolean) {
        show("t4", chat)
        tap("Tab overview, terminal 1 of 1")
        assertNotNull("the solo terminal's tile", nodeSaying("readme"))
        assertNull("the title bar is covered", nodeSaying("More options"))
        tap("Tab main, 3 terminals")
        tap("Pricing page copy")
        assertEquals(TitleBarFixtures.pricing, rig.home.openKey)
        rig.await("the other tab's terminal opens") { nodeSaying("More options") != null }
    }

    @Test fun theSoloTerminalsMapOpensTheOtherTab() = soloOpensOverview(chat = false)
    @Test fun theSoloChatsMapOpensTheOtherTab() = soloOpensOverview(chat = true)

    @Test fun aTerminalInNoPaneHasNoMap() = noPaneHasNoMap(chat = false)
    @Test fun theChatOfATerminalInNoPaneHasNoMap() = noPaneHasNoMap(chat = true)

    private fun noPaneHasNoMap(chat: Boolean) {
        show("t7", chat)
        assertFalse(semanticsNodes().any { node -> node.descriptions().any { it.startsWith("Tab overview,") } })
        assertNull(terminalUi(TitleBarFixtures.hub, AgentKey("workstation", "t7"), controller!!.get().resources).map)
    }
}

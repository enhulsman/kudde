package dev.kudde.android.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.ProgramIcon
import dev.kudde.android.ui.terminal.TerminalActions
import dev.kudde.android.ui.terminal.TerminalHeader
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatAsk
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatOption
import uniffi.kudde_mobile.ChatPoll
import uniffi.kudde_mobile.ChatQuestion
import uniffi.kudde_mobile.ChatRow

// chat-questions task 4.4: the question card in paper and graphite, at the
// default font and at 1.3x, and with a 700-character description. Still
// frames only (animate = false). Goldens: src/test/snapshots/chat-question-<scene>-<theme>.png.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatQuestionScreenshotTest {
    private val header = TerminalHeader("todo app", ProgramIcon.Claude, "website · workstation")
    private val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
    private val source = object : ChatImageSource {
        override fun cached(key: String) = null
        override suspend fun load(key: String) = null
    }
    private val rows = listOf(
        ChatRow("10:0", "user", "Plan a small todo app: pick the storage and the login.", "", emptyList(), emptyList()),
        ChatRow("90:0", "said", "Two choices shape the rest; I'll ask before writing the plan.", "", emptyList(), emptyList()),
    )
    private val database = ChatAsk(
        text = "Which database should the todo app use?", header = "Database", multi = false, answerable = true,
        options = listOf(
            ChatOption("SQLite (Recommended)", "One file, no server. Enough for a single desk.", null),
            ChatOption("Postgres", "Needs a running server; ready for several users.", "services:\n  db:\n    image: postgres:16\n    ports: [\"5432:5432\"]"),
            ChatOption("Plain JSON files", "No database at all; fine for a demo.", null),
        ),
    )
    private val login = ChatAsk(
        text = "Which login should it have?", header = "Login", multi = false, answerable = true,
        options = listOf(ChatOption("Password", "A form and a reset mail.", null), ChatOption("Passkey", "The phone signs in.", null)),
    )
    private val tests = ChatAsk(
        text = "Which tests come first?", header = "Tests", multi = false, answerable = true,
        options = listOf(ChatOption("Unit", "", null), ChatOption("End to end", "", null)),
    )

    private fun poll(q: ChatQuestion) = ChatPoll(
        found = ChatFound.FOUND, answered = true, agent = "claude", title = "Todo app", version = "2.1.330", status = "blocked",
        health = ChatHealth.Ok, problem = "", exited = false, canSend = false, cut = false, rows = rows,
        gaugeUsed = 41_000u, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "", question = q,
    )

    private fun capture(scene: String, q: ChatQuestion, fontScale: Float = 1f) {
        for (dark in listOf(false, true)) {
            captureRoboImage("src/test/snapshots/chat-question-$scene-${if (dark) "graphite" else "paper"}.png") {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    KuddeTheme(dark = dark) {
                        ChatScreen(header, actions, poll(q), ChatState(), source, { 0L }, onTerminal = {}, onPick = {}, onSend = {}, animate = false)
                    }
                }
            }
        }
    }

    private fun one(vararg asks: ChatAsk, step: UInt = 0u) =
        ChatQuestion("toolu_01", readable = true, questions = asks.toList(), texts = emptyList(), step = step, review = false)

    @Test
    fun card() = capture("card", one(database))

    @Test
    fun cardLargeText() = capture("large-text", one(database), fontScale = 1.3f)

    @Test
    fun longDescription() {
        val long = database.options[0].copy(
            description = ("A single file next to the app, copied with it, read by every process on the desk; " +
                "no server to start, no port to open, no password to keep. ").repeat(5).take(700),
        )
        capture("long", one(database.copy(options = listOf(long) + database.options.drop(1)), login, tests, step = 0u))
    }
}

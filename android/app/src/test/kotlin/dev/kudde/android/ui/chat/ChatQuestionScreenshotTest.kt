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

// chat-questions task 4.4: the question card (direction A) in paper and
// graphite, at the default font and at 1.3x, with a 700-character
// description, a multi-select question with two ticked, and the own-words
// field open with text. The message box is hidden under every card. Still
// frames only (animate = false; the field unfocused, so no cursor blinks and
// no keyboard, which Robolectric does not draw anyway). Goldens: src/test/snapshots/chat-question-<scene>-<theme>.png.
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

    private fun capture(scene: String, q: ChatQuestion, fontScale: Float = 1f, state: () -> ChatState = { ChatState() }) {
        for (dark in listOf(false, true)) {
            val s = state()
            captureRoboImage("src/test/snapshots/chat-question-$scene-${if (dark) "graphite" else "paper"}.png") {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    KuddeTheme(dark = dark) {
                        ChatScreen(header, actions, poll(q), s, source, { 0L }, onTerminal = {}, onPick = {}, onSend = {}, animate = false, focusOther = false)
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

    @Test
    fun multiSelect() {
        val ask = ChatAsk(
            text = "Which pages should the website get first?", header = "Pages", multi = true, answerable = true,
            options = listOf(
                ChatOption("Home", "What it is, in one screen.", null),
                ChatOption("Pricing", "The plans and what each includes.", null),
                ChatOption("Docs", "Setup and the first steps.", null),
                ChatOption("Blog", "Release notes and longer posts.", null),
            ),
        )
        capture("multi", one(ask)) { ChatState().apply { card.toggle(1); card.toggle(2) } }
    }

    // Something else is offered on a question without previews (owner, 2026-11-25).
    @Test
    fun somethingElseOpen() = capture("other", one(database.copy(options = database.options.map { it.copy(preview = null) }))) {
        ChatState().apply {
            card.otherOpen = true
            card.otherText = "Use Postgres, but through Docker"
        }
    }

    // Short cards on the tallest fixture screen (412 x 915 dp): the card sits on the bottom, 12 dp above the edge.
    @Test
    fun shortSingleChoice() = capture("short", one(login))

    @Test
    fun shortMultiSelect() {
        val ask = ChatAsk(
            text = "Which checks should run first?", header = "Checks", multi = true, answerable = true,
            options = listOf(ChatOption("Unit tests", "", null), ChatOption("Lint", "", null), ChatOption("Type check", "", null)),
        )
        capture("short-multi", one(ask)) { ChatState().apply { card.toggle(0) } }
    }

    // A typed answer joins the ticks on multi-select (owner, 2026-11-25): a ticked row with its text.
    @Test
    fun multiSelectTyped() {
        val ask = ChatAsk(
            text = "Which fruits would you put in a fruit salad?", header = "Fruit", multi = true, answerable = true,
            options = listOf(
                ChatOption("Apple", "Crisp, and keeps its shape.", null),
                ChatOption("Banana", "Soft and sweet; goes brown quickly.", null),
                ChatOption("Mango", "Juicy; takes some work to cut.", null),
            ),
        )
        capture("multi-typed", one(ask)) { ChatState().apply { card.toggle(0); card.toggle(2); card.otherText = "Berries" } }
    }

    // The preview sheet, look A (owner, 2026-11-25): the sample unwrapped, a note, "Choose this".
    private val cards = ChatOption(
        "Cards", "Roomier blocks per item, easier to scan.",
        "+----------+ +----------+ +----------+\n| Buy milk | | Call Ann | | Fix bike |\n| today    | | Mon      | | Fri      |\n| ( )      | | (x)      | | ( )      |\n+----------+ +----------+ +----------+",
    )
    private val compact = ChatOption(
        "Compact", "Dense single-line rows, more items at once.",
        "+------------------------+\n| ( ) Buy milk     today |\n| (x) Call Ann       Mon |\n+------------------------+",
    )
    private val layout = ChatAsk(
        text = "Which list layout should the todo app use?", header = "Layout", multi = false, answerable = true,
        options = listOf(compact, cards),
    )
    private val postgres = ChatOption(
        "Postgres", "Needs a running server; ready for several users.",
        buildString {
            append("services:\n  db:\n    image: postgres:16\n    restart: unless-stopped\n    environment:\n")
            append("      POSTGRES_USER: todo\n      POSTGRES_PASSWORD: change-me\n      POSTGRES_DB: todo\n")
            append("    ports:\n      - \"5432:5432\"\n    volumes:\n      - db-data:/var/lib/postgresql/data\n\n")
            append("-- schema.sql\ncreate table lists (\n  id serial primary key,\n  name text not null,\n  created_at timestamptz default now()\n);\n\n")
            append("create table items (\n  id serial primary key,\n  list_id int references lists(id) on delete cascade,\n")
            append("  title text not null,\n  due date,\n  done boolean not null default false,\n  position int not null\n);\n\n")
            append("create index items_list on items (list_id, position);\n\nvolumes:\n  db-data:\n")
        },
    )

    @Test
    fun previewShort() = capture("preview-short", one(layout)) { ChatState().apply { card.openPreview(cards) } }

    @Test
    fun previewLong() {
        val ask = layout.copy(options = listOf(compact, postgres))
        capture("preview-long", one(ask)) { ChatState().apply { card.openPreview(postgres) } }
    }

    @Test
    fun previewNote() = capture("preview-note", one(layout)) {
        ChatState().apply {
            card.openPreview(cards)
            card.note = "Bigger titles please, and the due date on the right"
        }
    }

    @Test
    fun previewLargeText() = capture("preview-large-text", one(layout), fontScale = 1.3f) {
        ChatState().apply {
            card.openPreview(cards)
            card.note = "Bigger titles please"
        }
    }
}

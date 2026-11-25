package dev.kudde.android.ui.chat

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.clickable
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.testing.unmergedSemanticsNodes
import dev.kudde.android.ui.agents.ProgramIcon
import dev.kudde.android.ui.terminal.TerminalActions
import dev.kudde.android.ui.terminal.TerminalHeader
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatAnswer
import uniffi.kudde_mobile.ChatAsk
import uniffi.kudde_mobile.ChatCall
import uniffi.kudde_mobile.ChatChoice
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatOption
import uniffi.kudde_mobile.ChatPoll
import uniffi.kudde_mobile.ChatQuestion
import uniffi.kudde_mobile.ChatRow

// chat-questions tasks 4.1 to 4.3: the question card (chat-view delta "A
// pending question shows as a card" and the requirements after it) and how
// TalkBack reads it (accessibility delta "A question card is read in
// order"), read from the merged semantics tree TalkBack is given.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatQuestionTest {
    private val database = ChatAsk(
        text = "Which database should the todo app use?", header = "Database", multi = false, answerable = true,
        options = listOf(
            ChatOption("SQLite (Recommended)", "One file, no server.", null),
            ChatOption("Postgres", "Needs a running server.", "services:\n  db:\n    image: postgres"),
        ),
    )
    /** The same question without previews: the dialog then has its free-text row ("Something else"). */
    private val plainDb = database.copy(options = database.options.map { it.copy(preview = null) })
    private val login = ChatAsk(
        text = "Which login should it have?", header = "Login", multi = false, answerable = true,
        options = listOf(ChatOption("Password", "A form.", null), ChatOption("Passkey", "The phone signs in.", null)),
    )
    private val tests = ChatAsk(
        text = "Which tests first?", header = "Tests", multi = false, answerable = true,
        options = listOf(ChatOption("Unit", "", null), ChatOption("End to end", "", null)),
    )

    private fun question(vararg asks: ChatAsk, step: UInt = 0u, review: Boolean = false, id: String = "toolu_01") =
        ChatQuestion(id = id, readable = true, questions = asks.toList(), texts = emptyList(), step = step, review = review)

    private fun poll(q: ChatQuestion?, rows: List<ChatRow> = emptyList()) = ChatPoll(
        found = ChatFound.FOUND, answered = true, agent = "claude", title = "Todo app", version = "2.1.330", status = "blocked",
        health = ChatHealth.Ok, problem = "", exited = false, canSend = false, cut = false, rows = rows,
        gaugeUsed = null, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "", question = q,
    )

    private val header = TerminalHeader("todo app", ProgramIcon.Claude, "website · workstation")
    private val noImages = object : ChatImageSource {
        override fun cached(key: String) = null
        override suspend fun load(key: String) = null
    }
    private val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
    private val rows = listOf(ChatRow("10:0", "said", "Let me ask first.", "", emptyList(), emptyList()))

    private var controller: ActivityController<ComponentActivity>? = null
    private val answers = mutableListOf<ChatChoice>()
    private var terminalOpened = 0

    @After
    fun close() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The chat with [p] shown; [state] can change it afterwards. */
    /** The poll on screen; set it to change what the chat shows. */
    private var shown by mutableStateOf<ChatPoll?>(null)

    private fun show(p: ChatPoll, state: ChatState = ChatState()): ChatState {
        shown = p
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        c.get().setContent {
            KuddeTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    ChatScreen(
                        header, actions, shown, state, noImages, { 0L },
                        onTerminal = { terminalOpened++ }, onPick = {}, onSend = {}, animate = false,
                        onAnswer = { answers += it },
                    )
                }
            }
        }
        idle()
        return state
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun focused() = semanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun spoken(n: SemanticsNode): String = (n.descriptions() + n.texts()).joinToString(" | ")

    /** What a swipe through the chat reads, in order, from the question on. */
    private fun reading(from: String): List<String> {
        val all = semanticsNodes().map(::spoken).filter { it.isNotEmpty() }
        val at = all.indexOfFirst { from in it }
        assertTrue("\"$from\" is not read: $all", at >= 0)
        return all.drop(at)
    }

    private fun node(text: String): SemanticsNode? =
        semanticsNodes().firstOrNull { text in it.descriptions() || text in it.texts() }

    private fun tap(text: String) {
        val n = node(text)
        assertNotNull("nothing reads \"$text\"", n)
        assertTrue("\"$text\" cannot be tapped", n!!.click())
        idle()
    }

    /** Drawn on screen: the reader's tree and the raw one (an option's row reads as one stop). */
    private fun shows(text: String) = (semanticsNodes() + unmergedSemanticsNodes())
        .any { n ->
            n.descriptions().any { text in it } || n.texts().any { text in it } ||
                n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(text) == true
        }

    // ---- 4.1 state

    @Test
    fun theCardFollowsItsStepAndForgetsWhatWasTapped() {
        val card = QuestionCard()
        val q = question(database, login)
        card.follow(q)
        assertTrue(card.start(ChatChoice.Pick(1u)))
        assertEquals(1, card.chosen)
        assertTrue(card.sending)
        assertFalse("a second tap while sending", card.start(ChatChoice.Pick(0u)))
        card.finished(ChatAnswer.Done)
        assertTrue("disabled until the step ends", card.busy)
        card.follow(q.copy(step = 1u))
        assertNull(card.chosen)
        assertFalse(card.busy)
        card.follow(null)
        assertNull(card.chosen)
    }

    // 5.4 review (Codex): a tap carries the card it was made on.
    @Test
    fun aTapMadeOnAStepThatMovedOnIsDropped() {
        val card = QuestionCard()
        val q = question(database, login)
        card.follow(q)
        card.follow(q.copy(step = 1u))
        assertFalse("the old frame's option must not answer the new step", card.start(ChatChoice.Pick(1u), q))
        assertFalse(card.sending)
        assertTrue(card.start(ChatChoice.Pick(0u), q.copy(step = 1u)))
        assertEquals("toolu_01" to 1u, card.sentFor)
    }

    @Test
    fun anOldAnswersResultLeavesTheNewStepAlone() {
        val card = QuestionCard()
        val q = question(database, login)
        card.follow(q)
        assertTrue(card.start(ChatChoice.Pick(1u), q))
        val key = card.sentFor
        card.follow(q.copy(step = 1u))
        card.finished(ChatAnswer.Changed, key)
        assertNull("the new step shows no refusal of the old one", card.problem)
        assertFalse(card.busy)
    }

    @Test
    fun whatAnAnswerSaysWhenNothingWasSent() {
        assertEquals(CardProblem(ALREADY_ANSWERED, terminal = false), problemOf(ChatAnswer.AlreadyAnswered))
        assertEquals(CardProblem(QUESTION_CHANGED, terminal = false), problemOf(ChatAnswer.Changed))
        assertEquals(CardProblem(NOT_CONFIRMED, terminal = true), problemOf(ChatAnswer.Unconfirmed))
        assertNull(problemOf(ChatAnswer.Done))
        // Owner, 2026-11-24: no connection says so; any other failure points at the terminal.
        assertEquals(CardProblem(NOT_CONNECTED, terminal = false), problemOf(ChatAnswer.NotConnected))
        assertEquals("Not connected. Nothing was sent.", NOT_CONNECTED)
        assertEquals(CardProblem(NOT_CONFIRMED, terminal = true), problemOf(ChatAnswer.Failed("The desk did not answer")))
        assertEquals("Already answered at the desk. Nothing was sent.", ALREADY_ANSWERED)
        assertEquals("The question changed. Nothing was sent.", QUESTION_CHANGED)
        assertEquals("Could not confirm the answer arrived. Check the terminal.", NOT_CONFIRMED)
        val card = QuestionCard()
        card.follow(question(database))
        card.start(ChatChoice.Skip)
        card.finished(ChatAnswer.AlreadyAnswered)
        assertEquals(ALREADY_ANSWERED, card.problem?.text)
        assertFalse(card.sending)
    }

    @Test
    fun ownWordsAreSentUpTo2000Characters() {
        assertFalse(canSendOther(""))
        assertFalse(canSendOther("   "))
        assertTrue(canSendOther("Use Postgres, but through Docker"))
        assertTrue(canSendOther("x".repeat(2000)))
        assertFalse(canSendOther("x".repeat(2001)))
        assertNull(otherProblem("x".repeat(2000)))
        assertEquals("Too long: 2000 characters at most", otherProblem("x".repeat(2001)))
    }

    // chat-view delta "The message box while a question card shows" (owner, 2026-11-25).
    @Test
    fun theMessageBoxIsHiddenWhileACardShows() {
        show(poll(question(database), rows))
        assertNull(node("Attach photos"))
        assertFalse(shows("Message"))
        assertNotNull(node(SKIP))
    }

    @Test
    fun theMessageBoxComesBackWithItsDraftWhenTheCardEnds() {
        val s = ChatState()
        s.draft = "Use SQLite"
        show(poll(question(database), rows), s)
        assertNull(node("Attach photos"))
        shown = poll(null, rows)
        idle()
        assertNotNull(node("Attach photos"))
        assertTrue(shows("Use SQLite"))
        assertNull(node(SKIP))
    }

    @Test
    fun aCardClosesAKeyboardAlreadyOpen() {
        show(poll(null, rows))
        val box = semanticsNodes().first { it.config.contains(SemanticsProperties.EditableText) }
        box.config[SemanticsActions.RequestFocus].action!!.invoke()
        idle()
        assertTrue(focused())
        shown = poll(question(database), rows)
        idle()
        assertFalse("nothing holds the focus, so no keyboard", focused())
    }

    @Test
    fun somethingElseTakesTheFocusAndSendGivesItBack() {
        val s = show(poll(question(plainDb), rows))
        s.card.follow(s.poll?.question)
        tap(SOMETHING_ELSE)
        val field = semanticsNodes().first { it.config.contains(SemanticsProperties.EditableText) }
        assertEquals("the keyboard opens for the field", true, field.config.getOrNull(SemanticsProperties.Focused))
        s.card.otherText = "Use Postgres, but through Docker"
        idle()
        tap(SEND)
        assertFalse(focused())
        assertEquals(listOf<ChatChoice>(ChatChoice.Other("Use Postgres, but through Docker")), answers)
    }

    @Test
    fun backDismissesTheFieldAndKeepsTheCard() {
        show(poll(question(plainDb), rows))
        tap(SOMETHING_ELSE)
        assertTrue(focused())
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(focused())
        assertFalse(shows(TYPE_YOUR_ANSWER))
        assertNotNull(node(SKIP))
        assertNotNull(node(SOMETHING_ELSE))
    }

    // Owner, 2026-11-25: on multi-select a typed answer joins the ticks and goes with the footer's one Send.
    private val fruit = ChatAsk(
        text = "Which fruits would you put in a fruit salad?", header = "Fruit", multi = true, answerable = true,
        options = listOf(ChatOption("Apple", "Crisp.", null), ChatOption("Banana", "Soft.", null), ChatOption("Mango", "Juicy.", null)),
    )

    @Test
    fun aTypedAnswerJoinsTheTicksAndGoesWithTheOneSend() {
        val s = show(poll(question(fruit), rows))
        tap("Option 1 of 3, Apple, Crisp., not checked")
        tap(SOMETHING_ELSE)
        assertEquals("the field has no send of its own", 1, semanticsNodes().count { SEND in it.descriptions() })
        s.card.otherText = "Berries"
        idle()
        assertTrue(shows("2 selected"))
        tap(SEND)
        assertEquals(listOf<ChatChoice>(ChatChoice.Multi(listOf(0u), "Berries")), answers)
    }

    @Test
    fun aTypedAnswerAloneCanBeSentAndStaysTickedAfterBack() {
        val s = show(poll(question(fruit), rows))
        tap(SOMETHING_ELSE)
        s.card.otherText = "Berries"
        idle()
        assertTrue(shows("1 selected"))
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(focused())
        assertNotNull("the typed row reads as ticked", node("Something else, Berries, checked"))
        assertTrue(shows("1 selected"))
        tap(SEND)
        assertEquals(listOf<ChatChoice>(ChatChoice.Multi(emptyList(), "Berries")), answers)
    }

    @Test
    fun aTooLongTypedAnswerHoldsTheOneSend() {
        val s = show(poll(question(fruit), rows))
        tap("Option 1 of 3, Apple, Crisp., not checked")
        tap(SOMETHING_ELSE)
        s.card.otherText = "x".repeat(2001)
        idle()
        assertTrue(shows(TOO_LONG))
        assertTrue(node(SEND)!!.config.contains(SemanticsProperties.Disabled))
    }

    // Coordinator, after the phone round (2026-11-25): the keyboard stayed up for the ~7 s multi-select send.
    @Test
    fun theMultiSelectSendClosesTheKeyboardForTheWholeSend() {
        val s = show(poll(question(fruit), rows))
        tap("Option 1 of 3, Apple, Crisp., not checked")
        tap(SOMETHING_ELSE)
        assertTrue("the keyboard opens for the field", focused())
        s.card.otherText = "Berries"
        idle()
        tap(SEND)
        assertEquals(listOf<ChatChoice>(ChatChoice.Multi(listOf(0u), "Berries")), answers)
        assertFalse("no keyboard at the tap", focused())
        val field = semanticsNodes().firstOrNull { it.config.contains(SemanticsProperties.EditableText) }
        field?.config?.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        idle()
        assertFalse("nothing takes the focus while the answer is on its way", focused())
    }

    @Test
    fun anAnswerOnItsWayClosesAKeyboardWhateverSentIt() {
        val s = show(poll(question(fruit), rows))
        tap(SOMETHING_ELSE)
        assertTrue(focused())
        // TalkBack or any other path that starts an answer without the button's own hide.
        s.card.start(ChatChoice.Skip)
        idle()
        assertFalse(focused())
    }

    @Test
    fun chooseThisWithANoteLeavesNoKeyboard() {
        val s = show(poll(question(database), rows))
        s.card.follow(s.poll?.question)
        tap(PREVIEW)
        val note = semanticsNodes().first { it.config.contains(SemanticsProperties.EditableText) }
        note.config[SemanticsActions.RequestFocus].action!!.invoke()
        idle()
        assertTrue(focused())
        s.card.note = "Through Docker, please"
        idle()
        tap(CHOOSE_THIS)
        assertFalse(focused())
    }

    @Test
    fun theRunningQuestionLineHidesWhileItsCardShows() {
        val waiting = ChatRow(
            "11:0", "tools", "", "", emptyList(),
            listOf(ChatCall("AskUserQuestion", "AskUserQuestion", "{}", "", done = false, failed = false, images = emptyList())),
        )
        show(poll(question(database), rows + waiting))
        assertFalse(shows("AskUserQuestion · running"))
        assertTrue(shows("Let me ask first."))
        shown = poll(null, rows + waiting.copy(calls = listOf(waiting.calls[0].copy(title = "Answered: Database", output = "x", done = true))))
        idle()
        assertTrue(shows("Answered: Database"))
    }

    @Test
    fun aKeycapSitsInTheMiddleOfItsRow() {
        val ask = database.copy(options = listOf(ChatOption("SQLite", "One file.\nNo server.\nEnough for one desk.", null), ChatOption("Postgres", "", null)))
        show(poll(question(ask), rows))
        for ((n, spoken) in listOf("1" to "Option 1 of 2, SQLite", "2" to "Option 2 of 2, Postgres")) {
            val row = semanticsNodes().first { it.descriptions().any { d -> d.startsWith(spoken) } }.boundsInRoot
            val cap = unmergedSemanticsNodes().first { it.texts() == listOf(n) }.boundsInRoot
            assertEquals("keycap $n centred on its row", row.center.y, cap.center.y, 1.5f)
        }
    }

    @Test
    fun aMultiSelectQuestionTicksAndSendsOnceConfirmed() {
        val ask = database.copy(
            multi = true,
            options = database.options.map { it.copy(preview = null) } + ChatOption("Plain JSON files", "", null),
        )
        show(poll(question(ask), rows))
        assertTrue(shows(CHOOSE_ANY))
        assertTrue(shows("0 selected"))
        assertTrue("Send waits for a tick", node(SEND)!!.config.contains(SemanticsProperties.Disabled))
        tap("Option 1 of 3, SQLite (Recommended), One file, no server., not checked")
        tap("Option 3 of 3, Plain JSON files, not checked")
        assertTrue(shows("2 selected"))
        assertNotNull(node("Option 1 of 3, SQLite (Recommended), One file, no server., checked"))
        tap(SEND)
        assertEquals(listOf<ChatChoice>(ChatChoice.Multi(listOf(0u, 2u), null)), answers)
    }

    @Test
    fun withoutACardTheBlockedRefusalStays() {
        assertEquals("The agent is waiting for you in the terminal", refusalOf(uniffi.kudde_mobile.ChatRefusal.Waiting).text)
    }

    @Test
    fun theAnsweredCallIsAQuietLine() {
        val call = { title: String, failed: Boolean ->
            ChatCall("AskUserQuestion", title, "{}", "x", done = true, failed = failed, images = emptyList())
        }
        assertEquals("Answered: Database", toolLine(listOf(call("Answered: Database", false))))
        assertEquals("Skipped: Database", toolLine(listOf(call("Skipped: Database", true))))
    }

    // ---- 4.2 the card

    @Test
    fun aSingleQuestionShowsItsOptionsSomethingElseAndSkip() {
        show(poll(question(plainDb), rows))
        assertTrue(shows("Which database should the todo app use?"))
        // The numbers sit in keycaps, without a dot (direction A).
        assertTrue(unmergedSemanticsNodes().any { it.texts() == listOf("1") })
        assertTrue(shows("SQLite (Recommended)"))
        assertTrue(shows("One file, no server."))
        assertTrue(unmergedSemanticsNodes().any { it.texts() == listOf("2") })
        assertTrue(shows("Needs a running server."))
        assertNotNull(node(SOMETHING_ELSE))
        assertNotNull(node(SKIP))
        assertFalse("one question has no step line", shows("Question 1 of 1"))
    }

    @Test
    fun aTapAnswersAndMarksTheOptionChosen() {
        val s = show(poll(question(database), rows))
        s.card.follow(s.poll?.question)
        tap("Option 2 of 2, Postgres, Needs a running server., Preview available")
        assertEquals(listOf<ChatChoice>(ChatChoice.Pick(1u)), answers)
        s.card.start(ChatChoice.Pick(1u))
        idle()
        val chosen = node("Option 2 of 2, Postgres, Needs a running server., Preview available")!!
        assertEquals(true, chosen.config.getOrNull(SemanticsProperties.Selected))
        val skip = node(SKIP)!!
        assertTrue("disabled while sending", skip.config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun aLongDescriptionIsShownWholeAndTheCardScrolls() {
        val long = "d".repeat(700)
        val ask = database.copy(options = listOf(ChatOption("SQLite", long, null), ChatOption("Postgres", "A server.", null)))
        show(poll(question(ask), rows))
        assertTrue(semanticsNodes().any { n -> n.descriptions().any { long in it } })
        fun SemanticsNode.holds(text: String): Boolean = descriptions().any { text in it } || children.any { it.holds(text) }
        val card = semanticsNodes().filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.holds("Option 2 of 2") }
        assertTrue("the card scrolls inside its own area", card.any { !it.holds("Let me ask first.") })
    }

    @Test
    fun anUnreadableCallShowsItsTextAndTheTerminalOnly() {
        val q = ChatQuestion("toolu_01", readable = false, questions = emptyList(), texts = listOf("Which database?"), step = 0u, review = false)
        show(poll(q, rows))
        assertTrue(shows("Which database?"))
        tap(OPEN_TERMINAL)
        assertEquals(1, terminalOpened)
        assertNull(node(SKIP))
        assertNull(node(SOMETHING_ELSE))
        assertFalse(semanticsNodes().any { n -> n.descriptions().any { it.startsWith("Option ") } })
        assertFalse("a record exists: no hook advice", shows(NO_HOOK))
    }

    // Owner, 2026-11-25: with no hook record the card says how to answer from the phone.
    @Test
    fun withoutTheHookTheCardSaysHowToAnswerHere() {
        val q = ChatQuestion("waiting", readable = false, questions = emptyList(), texts = emptyList(), step = 0u, review = false, hookMissing = true)
        show(poll(q, rows))
        assertTrue(shows(NO_HOOK))
        assertNotNull(node(OPEN_TERMINAL))
        assertNull(node(SKIP))
        val order = reading(QUESTION_FROM_AGENT.takeIf { shows(it) } ?: NO_HOOK)
        assertTrue("the advice comes before the button: $order", order.indexOfFirst { NO_HOOK in it } < order.indexOfFirst { OPEN_TERMINAL in it })
    }

    @Test
    fun aMultiSelectQuestionOpensTheTerminalUntilConfirmed() {
        val ask = database.copy(multi = true, answerable = false)
        show(poll(question(ask), rows))
        assertTrue(shows("SQLite (Recommended)"))
        assertNotNull(node(OPEN_TERMINAL))
        assertFalse(semanticsNodes().any { n -> n.clickable() && n.descriptions().any { it.startsWith("Option ") } })
    }

    @Test
    fun severalQuestionsShowTheirStep() {
        show(poll(question(database, login, tests, step = 1u), rows))
        assertTrue(shows("Question 2 of 3"))
        assertTrue(shows("Which login should it have?"))
        assertFalse(shows("Which database should the todo app use?"))
    }

    @Test
    fun theReviewStepShowsSendAnswersAndSkipOnly() {
        show(poll(question(database, login, step = 2u, review = true), rows))
        tap(SEND_ANSWERS)
        assertEquals(listOf<ChatChoice>(ChatChoice.Submit), answers)
        assertNotNull(node(SKIP))
        assertNull(node(SOMETHING_ELSE))
        assertFalse(shows("Question "))
        // Owner, 2026-11-25: the review step says what is left to do.
        assertTrue(shows(ALL_ANSWERED))
    }

    @Test
    fun somethingElseOpensAFieldAndSendsTheText() {
        val s = show(poll(question(plainDb), rows))
        tap(SOMETHING_ELSE)
        assertTrue(shows(TYPE_YOUR_ANSWER))
        val send = node(SEND)!!
        assertTrue("Send stays disabled while blank", send.config.contains(SemanticsProperties.Disabled))
        s.card.otherText = "Use Postgres, but through Docker"
        idle()
        tap(SEND)
        assertEquals(listOf<ChatChoice>(ChatChoice.Other("Use Postgres, but through Docker")), answers)
    }

    @Test
    fun tooLongOwnWordsSayWhyAndCannotBeSent() {
        val s = show(poll(question(plainDb), rows))
        tap(SOMETHING_ELSE)
        s.card.otherText = "x".repeat(2001)
        idle()
        assertTrue(shows(TOO_LONG))
        assertTrue(node(SEND)!!.config.contains(SemanticsProperties.Disabled))
    }

    // Owner, 2026-11-25: a question with previews has no free-text row in the dialog.
    @Test
    fun aQuestionWithPreviewsHasNoSomethingElse() {
        show(poll(question(database), rows))
        assertNull(node(SOMETHING_ELSE))
        assertNotNull(node(SKIP))
        assertNotNull(node(PREVIEW))
    }

    // Look A (owner, 2026-11-25): the sheet holds the sample, a note and "Choose this".
    @Test
    fun chooseThisAnswersTheOptionAndClosesTheSheet() {
        val s = show(poll(question(database), rows))
        s.card.follow(s.poll?.question)
        tap(PREVIEW)
        assertNotNull(node(NOTE_HINT))
        tap(CHOOSE_THIS)
        assertEquals(listOf<ChatChoice>(ChatChoice.Pick(1u)), answers)
        assertFalse(shows("Preview: Postgres"))
    }

    @Test
    fun aNoteGoesWithChooseThis() {
        val s = show(poll(question(database), rows))
        s.card.follow(s.poll?.question)
        tap(PREVIEW)
        s.card.note = "Through Docker, please"
        idle()
        tap(CHOOSE_THIS)
        assertEquals(listOf<ChatChoice>(ChatChoice.Note(1u, "Through Docker, please")), answers)
    }

    @Test
    fun aTooLongNoteSaysWhyAndHoldsChooseThis() {
        val s = show(poll(question(database), rows))
        tap(PREVIEW)
        s.card.note = "x".repeat(2001)
        idle()
        assertTrue(shows(TOO_LONG))
        assertTrue(node(CHOOSE_THIS)!!.config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun theSampleScrollsSidewaysInsteadOfWrapping() {
        show(poll(question(database), rows))
        tap(PREVIEW)
        assertTrue(
            "a wide sample scrolls sideways",
            unmergedSemanticsNodes().any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) },
        )
    }

    @Test
    fun aPreviewOpensInASheetAndClosingLeavesTheCard() {
        val s = show(poll(question(database), rows))
        tap(PREVIEW)
        assertTrue(shows("Preview: Postgres"))
        assertTrue(semanticsNodes().any { n -> n.texts().any { "image: postgres" in it } })
        s.card.preview = null
        idle()
        assertFalse(shows("Preview: Postgres"))
        assertNotNull(node(SKIP))
        assertTrue(answers.isEmpty())
    }

    @Test
    fun skipIsAButton() {
        show(poll(question(database), rows))
        tap(SKIP)
        assertEquals(listOf<ChatChoice>(ChatChoice.Skip), answers)
    }

    @Test
    fun aRefusalShowsInTheCardWithTheTerminalWhereItHelps() {
        val s = ChatState()
        s.card.follow(question(database))
        s.card.start(ChatChoice.Pick(0u))
        s.card.finished(ChatAnswer.Unconfirmed)
        show(poll(question(database), rows), s)
        assertTrue(shows(NOT_CONFIRMED))
        tap(SHOW_TERMINAL)
        assertEquals(1, terminalOpened)
    }

    @Test
    fun noCardNoCardTexts() {
        show(poll(null, rows))
        assertNull(node(SKIP))
        assertNull(node(SOMETHING_ELSE))
    }

    // ---- 4.3 TalkBack

    @Test
    fun talkBackReadsTheCardInOrder() {
        show(poll(question(database.copy(options = database.options.map { it.copy(preview = null) })), rows))
        val read = reading("Which database should the todo app use?")
        assertEquals(
            listOf(
                "Which database should the todo app use?",
                "Option 1 of 2, SQLite (Recommended), One file, no server.",
                "Option 2 of 2, Postgres, Needs a running server.",
                SOMETHING_ELSE,
                SKIP,
            ),
            read.take(5),
        )
    }

    @Test
    fun anOptionIsOneStopAndAPreviewIsSaid() {
        show(poll(question(database), rows))
        val option = node("Option 2 of 2, Postgres, Needs a running server., Preview available")!!
        assertTrue(option.clickable())
        assertEquals("its texts are not read again", emptyList<String>(), option.texts())
        assertNotNull("the preview is its own button", node(PREVIEW))
    }

    @Test
    fun talkBacksDoubleTapAnswersLikeATap() {
        show(poll(question(database), rows))
        tap("Option 1 of 2, SQLite (Recommended), One file, no server.")
        assertEquals(listOf<ChatChoice>(ChatChoice.Pick(0u)), answers)
        assertFalse("nothing took the focus", semanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true })
    }

    @Test
    fun theCardIsAnnouncedOnceAndEachStepAndRefusalOnce() {
        val heard = CardHeard()
        val q = question(database, login, tests)
        assertEquals(listOf("Question from the agent. Which database should the todo app use?"), heard.next(q, null))
        assertEquals(emptyList<String>(), heard.next(q, null))
        assertEquals(listOf("Question 2 of 3"), heard.next(q.copy(step = 1u), null))
        assertEquals(emptyList<String>(), heard.next(q.copy(step = 1u), null))
        assertEquals(listOf(ALREADY_ANSWERED), heard.next(q.copy(step = 1u), ALREADY_ANSWERED))
        assertEquals(emptyList<String>(), heard.next(q.copy(step = 1u), ALREADY_ANSWERED))
        // The last step is heard as its button (owner, 2026-11-24), once.
        assertEquals(listOf(SEND_ANSWERS), heard.next(q.copy(step = 3u, review = true), ALREADY_ANSWERED))
        assertEquals(emptyList<String>(), heard.next(q.copy(step = 3u, review = true), ALREADY_ANSWERED))
        assertEquals(emptyList<String>(), heard.next(null, null))
        assertEquals(
            "a new call is a new card",
            listOf("Question from the agent. Which login should it have?"),
            heard.next(question(login, id = "toolu_02"), null),
        )
    }

    @Test
    fun aCardWithoutTheHookIsHeardWithItsAdvice() {
        val q = ChatQuestion("waiting", readable = false, questions = emptyList(), texts = emptyList(), step = 0u, review = false, hookMissing = true)
        assertEquals(listOf("Question from the agent. $NO_HOOK"), CardHeard().next(q, null))
    }

    @Test
    fun theQuietLineReadsLikeItsText() {
        val row = ChatRow(
            "20:0", "tools", "", "", emptyList(),
            listOf(ChatCall("AskUserQuestion", "Answered: Database", "{}", "x", done = true, failed = false, images = emptyList())),
        )
        show(poll(null, rows + row))
        assertTrue(semanticsNodes().any { n -> n.texts() == listOf("Answered: Database") || n.descriptions() == listOf("Answered: Database") })
    }

    @Test
    fun everyFixedTextIsTheApprovedOne() {
        assertEquals("Something else", SOMETHING_ELSE)
        assertEquals("Type your answer", TYPE_YOUR_ANSWER)
        assertEquals("Send", SEND)
        assertEquals("Too long: 2000 characters at most", TOO_LONG)
        assertEquals("Skip", SKIP)
        assertEquals("Question 2 of 3", stepLine(2, 3))
        assertEquals("Send answers", SEND_ANSWERS)
        assertEquals("Choose any number", CHOOSE_ANY)
        assertEquals("Preview", PREVIEW)
        assertEquals("Preview: Postgres", previewTitle("Postgres"))
        assertEquals("Open the terminal", OPEN_TERMINAL)
        assertEquals("2 selected", RuntimeEnvironment.getApplication().getString(dev.kudde.android.R.string.chat_question_selected, 2))
        assertEquals("0 selected", RuntimeEnvironment.getApplication().getString(dev.kudde.android.R.string.chat_question_selected, 0))
        assertEquals("Question from the agent", QUESTION_FROM_AGENT)
        assertEquals("Note (optional)", NOTE_HINT)
        assertEquals("Choose this", CHOOSE_THIS)
        assertEquals("All questions answered. Send your answers, or skip them all.", ALL_ANSWERED)
        assertEquals("The agent asked a question in the terminal. To answer it here, add the question hook on the desk.", NO_HOOK)
    }
}

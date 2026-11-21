# Design: screen-stack

Line numbers are the repository's at 1ae6a3c (branch `spec/spaces-navigation`).
Paths under `android/app/src/main/kotlin/dev/kudde/android/` are written
from `ui/` on.

## D0. The stuck test (task 0)

**What the results show.** The last full run in the spaces worktree
(`build/test-results/testDebugUnitTest/TEST-...TitleBarMenuTest.xml`,
2026-11-21) is not an endless hang but a crawl that ends in a crash:

| Test | Seconds |
|---|---|
| `theButtonIsReadAsMoreOptions...`, `noChangesButtonOutsideTheMenu` (no popup opened) | 0.05, 0.03 |
| `choosingChanges...`, `theChatsBarHasTheSameMenu`, `aTapShowsChanges...`, `aTapOutside...` (each opens the popup) | 788, 814, 816, 788 |
| `backClosesTheMenuAndSendsNothing` | 216, then skipped |

Its `system-err` holds `OutOfMemoryError: Java heap space` on the SDK 35
main thread, thrown in `org.robolectric.shadows.ShadowTrace.endSection`
(`ArrayDeque.addLast`), called from `androidx.compose.runtime.internal.Trace.endSection`
in `Recomposer.runRecomposeAndApplyChanges`, driven by
`AndroidUiDispatcher.performFrameDispatch`. Robolectric's `ShadowTrace`
keeps every ended section of a test in a queue it only clears between
tests. The earlier full run of groups 5 and 6 took 1 h 8 min, which these
five cases (about 67 min) account for almost entirely.

**Suspected cause** (confidence: moderate). While a Compose `Popup` window
is attached in this Robolectric (4.17, SDK 35, `GraphicsMode.NATIVE`), the
main looper runs frames back to back at one clock time, so
`idleFor(20 ms)` (`TitleBarMenuTest.kt:82`) never gets past its first
instant: each frame asks for the next. Every frame adds trace sections
until the heap is gone. The build report already noted that "removing any
Compose Popup in a frame runs out of heap (a bare Popup with one text does
it too)", which points away from the menu's content and towards the popup
window itself (its own `ViewRootImpl`, re-laid-out or redrawn each frame;
possibly the position provider's size feeding back). Not proven: the frame
count was never measured.

**How task 0 finds it**, cheapest first, each a single test class run with
a `Timeout` of 60 s so a loop fails instead of hanging:

1. A probe test: a bare `Popup` with one `BasicText`, a frame counter
   (`withFrameNanos` in a `LaunchedEffect`, and a `Choreographer` callback
   counter), `idleFor(20 ms)`. Expected: a handful of frames. Thousands
   confirm the loop.
2. Bisect on the probe: `GraphicsMode.LEGACY`; `focusable = false`; a
   fixed-size popup (`Modifier.size`) instead of wrap-content; a position
   provider that returns a constant; `ShadowLooper.idle()` plus
   `runOneTask` steps instead of `idleFor`.
3. Fix at the cause the bisect names. In order of preference: a change in
   how the app's popups are built if one of their properties triggers it
   (the change keeps behaviour on the phone, checked at 9.2); else the
   popup tests run in the graphics mode or with the clock stepping that
   does not loop, written once in `testing/` and used by every popup test;
   else, as a last resort, popup tests drive frames by hand with a bounded
   count. Only the SDK 35 android-all jar is available offline
   (`robolectric.properties`), so "run on SDK 34" is not an option.
4. Whatever the fix, a JUnit `Timeout` rule (or the runner, `KuddeTestRunner`)
   gives every Robolectric UI test a ceiling, so a future loop fails in
   seconds.

Done: `TitleBarMenuTest` passes as a class in under 30 s, including
`backClosesTheMenuAndSendsNothing`, and the full suite passes. This also
gates D6: the gauge's detail becomes a popup tested the same way.

## D1. Current structure

```
MainActivity.Root                 one Screen at a time (MainActivity.kt:88-109)   fine
└─ AgentsRoute                    one of: open agent / overview / Spaces / Agents
                                  (agents/AgentsRoute.kt:89-111)                    fine
   └─ TerminalRoute(key)          everything remember(key)   (terminal/TerminalRoute.kt:54)
      ├─ session, screen, chat visit, opener, reopen, phone, heldGrid   (:58-100)
      ├─ cache load / store, background idle, lifecycle effects         (:104-144)
      ├─ BackHandler: compose sheet, selection, else home.close()       (:168-177)
      ├─ ChatPolling (poll while chat, watch while terminal)            (:195-198)
      └─ Box                                                            (:199-258)
         ├─ TerminalKeyboard(key)   invisible input view                (:200)
         ├─ TerminalScreen          semantics cleared under chat/covers,
         │                          not drawn under chat, still laid out (:218-232)
         ├─ ChatRoute               if switch.chat; semantics cleared under covers (:233-240)
         ├─ ChangesRoute            if changesOpen                      (:241-248)
         └─ TabOverviewRoute        if overview != null                 (:249-257)
```

What goes wrong, verified in the code:

- **Touch.** A lower layer's `pointerInput` gets every event the top layer
  does not consume. `TabOverviewScreen` has no catch-all, so the terminal's
  title-bar drag (`terminal/TerminalScreen.kt:391-418`) still steps under
  it (point 9). The chat has `coversBelow()` (`chat/ChatScreen.kt:119-120`,
  added after a tap pressed a key under it): each overlay needs its own
  patch.
- **Focus and keyboard.** The terminal's keyboard is an Android view
  (`TerminalKeyboard`, `terminal/TerminalInputView.kt:31-44`); each opener
  hides it by hand (`TerminalRoute.kt:203`, `:209`, `:223`). The chat's
  `BasicTextField` (`ChatScreen.kt:586-592`) stays composed and focused
  under Changes, so the system shows the keyboard again for it and its
  cursor draws through (point 13).
- **Size.** While a cover shows, the terminal under it is still laid out,
  and the keyboard going down grows its grid; `heldGrid` (`:71`, `:187`,
  `:245-246`, `:252-253`) holds that size back so the desk keeps its size,
  then applies it on Back.
- **Screen reader.** Lower layers are hidden by `clearAndSetSemantics`
  (`:218`, `:234`); a missed wrapper would expose them.

Why it was built this way: the terminal stays attached at its size while
Changes or the overview shows, and the chat over a live terminal makes the
switch back instant. Both are properties of the session, not of the
terminal being composed; the session and its effects already live in
`TerminalRoute`, outside `TerminalScreen`.

## D2. Target structure

```
AgentsRoute
└─ AgentHost(key)                        was TerminalRoute; composed while the agent is open
   ├─ keeper = remember(key) { AgentKeeper(...) }    plain class, D3
   ├─ effects: opener tick, cache, idle, lifecycle, report toast, ChatPolling
   ├─ BackHandler (lowest): home.close()
   └─ ScreenStackHost(keeper.top)        composes ONLY the top destination, D4/D5
        Terminal  -> TerminalKeyboard + TerminalScreen   (+ its own BackHandler)
        Chat      -> ChatRoute                           (+ its own BackHandler)
        Changes   -> ChangesRoute                        (+ its own BackHandler)
        Overview  -> TabOverviewRoute                    (+ its own BackHandler)
```

**Destinations** (`ui/agent/AgentStack.kt`):

```kotlin
sealed interface AgentScreen {
    data object Terminal : AgentScreen
    data object Chat : AgentScreen
    data object Changes : AgentScreen
    data class Overview(val spaceId: String, val tabId: String) : AgentScreen
}

/** Covers over a base; the base itself is the chat switch's (ChatSwitch.chat), never stored here. */
class AgentStack {
    val covers: List<AgentScreen>                 // Changes / Overview only
    fun top(chat: Boolean): AgentScreen           // covers.last() ?: (Chat if chat else Terminal)
    fun push(cover: AgentScreen)                  // ignored when equal to the top cover; Terminal/Chat rejected
    fun pop(): Boolean                            // false when no cover: the caller leaves the agent
}
```

- **The base stays the switch's.** `ChatSwitch` (`chat/ChatSwitch.kt:35-67`)
  already decides terminal or chat, including the first answered poll that
  may settle it after the agent opened. Storing the base in the stack too
  would be a second source of truth. The switch is not a push: Back from
  the chat leaves the agent, as today (`TerminalRoute.kt:175`).
- **Covers** are Changes and the overview. Today they never stack on each
  other (Changes has no map, the overview no menu), so the list holds at
  most one; it is a list so a later cover costs nothing. A double tap that
  pushes the same cover twice is ignored.
- **A tile of another terminal** in the overview calls `home.open`, which
  changes the key: the old host and keeper go, as today. Its own tile pops.
- **Hand-rolled, no library.** Four destinations, one level of covers,
  state that already lives in plain classes and in `remember(key)`. androidx
  Navigation Compose would add a dependency (the owner's call), a route
  type system and its own back stack beside `AgentsHome.openKey`, and
  would still need the keeper for the session. A list and a `when` match
  `MachineFlows` and `AgentsHome` (confidence: high).

## D3. The keeper

`AgentKeeper` (`ui/agent/AgentKeeper.kt`), a plain class created by
`remember(key)` in `AgentHost` and released in that remember's
`DisposableEffect`. It is the existing `remember(key)` state of
`TerminalRoute`, gathered in one object with names, so its rules can be
tested without a composition.

| Holds | Today at | Survives |
|---|---|---|
| `reopen: TerminalReopen`, `session` and `opener` per generation | `TerminalRoute.kt:58-82` | every destination change |
| `onInputWithoutView` hook to `opener.openNow` (D5 of spaces) | `:84-87` | " |
| `screen: TerminalScreenState` (compose text, sheet, selection, zoom, keyboard asked for) | `:61` | " |
| `chat: ChatVisit` (hub chat, `ChatState` from `app.chatStates`, `ChatSwitch`) | `:63` | " |
| `stack: AgentStack` | `:66`, `:68` | " |
| `phone: GridSize?`, the last phone grid measured | `:93` | " |
| `renderer: TerminalRenderer` (its recorded row pictures) | `TerminalScreen.kt:716-721` | " (so a return draws without re-recording) |
| `actions: TerminalActions` | `:180-192` | " |

Not held, because they already live longer: `ChatState` (draft, attached
photos, pending, scroll place via `keep`/`startAt`, `chat/ChatState.kt`),
the zoom per terminal (`app.terminalZoom`), the chosen view
(`app.chatView`), the disk cache. Changes and the overview start fresh on
each open, as today (`TerminalRoute.kt:242`, `TabOverviewRoute`'s
`rememberSaveable(machine, spaceId)`), so they keep nothing in the keeper.

**Lifetime.** Created when `AgentsRoute` composes the open agent; released
when it stops composing it: Back from the base, a step or tap to another
agent (a new key), the agent gone from the hub (`AgentsHome.onSnapshot`),
leaving the Agents screen for Machines. Release is today's dispose,
unchanged: `session.close()` detaches (the desk takes its size back) and
the last frame goes to the disk cache (`:115-119`); `opener.dispose()` so a
disposed opener never opens. Rotation and process death behave as today
(only `AgentsHome`'s saver survives them).

**Size rules (spaces spec, design D5 there), unchanged:**

- The session's mode follows the **base**, not the top: terminal base,
  phone size (`lead`); chat base, desk size (`watch`)
  (`LaunchedEffect(session, switch.chat)`, `:194`). A cover does not change
  it, so Changes over the terminal keeps the phone size, over the chat the
  desk size, as today.
- The 400 ms wait for a step is the opener's and starts when the keeper is
  created; destinations do not affect it. Input during the wait still opens
  first (`TerminalSession.send`).
- **Only the terminal destination measures.** Under a cover or the chat no
  grid arrives, so nothing is sent: `heldGrid` goes. Back to the terminal
  measures again; the settler (`SizeSettler`, `SETTLE_MS`) sends the new
  size once the keyboard has settled, exactly what "Back applies the last
  one" did.
- **A grid when the terminal was never shown.** Today the terminal is laid
  out under the chat, so the opener always gets a grid. With only the top
  composed, an agent that opens on its chat has none, yet must attach
  (watching at desk size) because `ChatPolling` reads the terminal's text
  (Codex's queued messages, the second Enter, `ChatRoute.kt:97-100`). The
  keeper seeds the opener from, in order: its own last grid; the last grid
  any terminal measured in this process (`app.lastPhoneGrid`, the phone's
  terminal area is the same for every agent but for the queue strip and the
  keyboard); else an estimate from the host's constraints with the same
  `phoneGrid` function, the title bar's minimum height and the keys rows
  subtracted. The seed only matters while watching: the core stores a phone
  size while it observes (`crates/kudde-mobile/src/term.rs:257`, `:280`)
  and applies it only when the phone leads. When the terminal destination
  first measures, the keeper sends that size with `phoneSize` before it
  calls `lead` (order guaranteed by `AgentKeeper.onTerminalGrid`), so the
  desk is never taken over at the estimate. Confidence: moderate that
  `resize_phone` returns false while observing; task 3.3 adds a Kotlin test
  of the order and 3.4 reads `term.rs` to confirm, no Rust change planned.

**Tests.** `AgentStackTest` (plain): push, pop, top for each base, the
double push, Terminal/Chat rejected as covers. `AgentKeeperTest` (plain,
`FakeTerminalView`, the opener's clock arguments as in
`TerminalOpenerTest`): created by a step it opens at 400 ms whatever the
top; pushing and popping covers never detaches nor sends a size; a grid
reported while the terminal is not the top is impossible by API (only
`onTerminalGrid`); chat base opens watching with the seed; the first
terminal grid sends `phoneSize` then `lead`; release detaches once and
returns the frame; a released keeper never opens.

## D4. Only the top is composed

`ScreenStackHost` (`ui/agent/ScreenStackHost.kt`):

```kotlin
@Composable
fun <D : Any> ScreenStackHost(top: D, content: @Composable (D) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    key(top) {
        // One place for the focus rule (D5): a destination that leaves takes focus and the keyboard with it.
        DisposableEffect(Unit) { onDispose { focus.clearFocus(force = true); keyboard?.hide() } }
        content(top)
    }
}
```

(Sketch; the rule and its tests are the contract, not this code.) `key(top)` disposes the old
destination's whole composition: its `pointerInput`s, its focused text
field, its `TerminalKeyboard` (whose dispose already retires the input view
and puts its keyboard down, `TerminalInputView.kt:118-125`), its
`BackHandler`s and its semantics. Nothing of it can take a touch, focus or
a screen-reader visit, because it does not exist.

Consequences, each a deletion:

- `clearAndSetSemantics` wrappers (`TerminalRoute.kt:218`, `:234`) go.
- `undrawnWhen` and the terminal's `covered` flag go (the terminal is not
  composed under the chat). `Modifier.undrawnWhen` (`ui/components/Loop.kt:94`)
  and its `LoopTest` case go with its last caller.
- `coversBelow()` (`ChatScreen.kt:119-120`, `:157`) goes: nothing is below
  the chat any more. Its test, if any, becomes the D5 leak test.
- `input.hide()` and `screen.inputOpen = false` in the openers
  (`:203-204`, `:209-210`, `:223-224`) go; the host's rule and the input
  view's retire do it. `screen.inputOpen` is cleared when the terminal
  destination leaves (`DisposableEffect` in the terminal destination), so
  the keys row does not come back expecting a keyboard.

**Back.** Compose gives the most recently composed enabled `BackHandler`
priority. The host registers "leave the agent" first; the top destination
registers its own inner handlers after it:

| Top | Back, in order |
|---|---|
| Terminal | compose sheet closes (text dropped, as today); selection ends; leave the agent |
| Chat | image viewer; tool detail; tool sheet; leave the agent |
| Changes | a diff goes to the list; the list pops |
| Overview | pops |

Menus and the gauge's detail are popups with their own Back (D6). The
terminal's compose and selection handlers move from the host
(`TerminalRoute.kt:168-177`) into the terminal destination, so they cannot
run while the terminal is not shown.

**Transitions.** None in this change: the destination swaps in one frame,
as the layers do today (owner question 1). A later transition wraps the
`key(top)` in an animated content container; during it two destinations
are composed for its length, so that pass must keep the outgoing one free
of input (`Modifier.pointerInput` gate and semantics) and is reviewed
against D5.

## D5. Focus and keyboard ownership

**Rule: only the top destination may hold focus or the keyboard.** A
destination change clears focus (forced) and hides the soft keyboard before
the next destination composes. A destination takes focus on arrival only
when the person asked for it in that destination: the compose sheet when
it opens (`composeFocus`), the terminal keyboard on the keyboard key or the
chip. The chat's message field does not take focus on arrival (as today).

- Changes and the overview have no text field, so after opening either no
  node has focus and the keyboard is down; it stays down because nothing
  asks for it (point 13).
- Back to the chat does not bring its keyboard back; a tap in the field
  does (as today after Changes was closed by hand).
- Switching terminal to chat or back is a destination change: the
  keyboard goes down (today's `onChat` hide, now by the rule).
- A step to another agent changes the key, so the old host goes and the
  keyboard goes down (accepted, point 14); `TerminalSwitchTest` keeps
  holding that input never crosses terminals.

**Screen reader.** Only the top destination's nodes exist. No pane title
or new announcement is added in this change (no new spoken text); whether
TalkBack should name the screen on each change is a follow-up for the
owner's ears.

## D6. Popups that block what is below

`ModalPopup` (`ui/components/ModalPopup.kt`): a focusable Compose `Popup`
whose window fills the screen. Its root is a transparent catcher that
consumes every pointer event and closes the popup on a tap or a drag that
starts outside the panel; the panel is placed inside it at the anchor by
the existing position rule (`MenuPosition`, `TitleBarMenu.kt:161-171`).
Back closes it (`onDismissRequest`). Whatever `PopupProperties` do with
outside touches on a given Android version, nothing outside the panel can
reach the window below, because the popup's own window covers it.

- `TitleBarMenu` (`TitleBarMenu.kt:72-103`) moves onto it; its `ShadowRoom`
  tap-to-close padding becomes part of the catcher. Look unchanged.
- The gauge's detail (`ChatScreen.kt:169`, `:206-217`, `ChatState.gaugeOpen`)
  moves from a `Strip` in the column into a `ModalPopup` anchored under the
  title bar, full width, drawn as the strip is today (owner question 2).
  The chat no longer re-lays out when it opens (point 10). Its goldens
  (`ChatScreenshotTest` `gauge-claude`, `gauge-codex`) draw the panel where
  the popup places it, as the menu goldens do, because the capture leaves
  popup windows out.
- `usage-gauge` replaces the panel's content with its sheet later.

Tests read touches in the popup's window (`windowRoots().last()`, as
`TitleBarMenuTest` does): a drag across the title bar's area dispatched to
the popup window steps nothing and closes the popup; a tap there sends
nothing to the terminal. Needs D0.

## D7. What the keeper makes easy later (not built here)

- **(A) An in-memory last screen per terminal.** The disk cache is
  encrypted (`KeystoreFrameCipher`) and readable only within 30 minutes of
  unlocking, so a step often shows the background. The keeper is the one
  place a session ends (release) and starts (creation), so an app-scoped
  `LastScreens` map (key to frame, a few MB cap, cleared with the process)
  is written at release and read at creation before the disk cache, the
  way `TerminalReopen.takeSeed` already seeds a reopen from memory.
- **(B) The tab's other panes watched live without resizing the desk.**
  The keeper can own extra watch-only sessions for the sibling panes
  (`openTerminal(..., at_phone_size = false)`, never `lead`), released
  with it, so stepping inside the tab shows a live frame at once. The 400
  ms rule still governs taking a pane over at phone size.

Both are changes of their own with their own spec deltas.

## D8. Risks

- **The terminal is recomposed on each return** (from the chat, Changes or
  the overview). Its grid and renderer live in the keeper, so the cost is
  composing one screen; the first frame after a return records all rows
  only if the renderer was not kept. Measure at 9.2 with the perf build
  (confidence: moderate that it is not felt).
- **The chat is recomposed on each return.** Its scroll place is already
  kept in `ChatState` (`keep`/`startAt`, design D12 of the chat), which
  today serves the switch back from the terminal; Back from Changes now
  uses the same path. A row that changed meanwhile may shift the place
  slightly; characterization test 2.2 holds the place for an unchanged list.
- **Chat-first agents need a grid** (D3). Mitigated by the seed and the
  `phoneSize`-before-`lead` order; risk is a short desk reflow on the first
  switch to the terminal if the seed was wrong while leading, which the
  order prevents.
- **`ChatPolling` stays host-level**, keyed on the base, so the chat keeps
  polling under Changes (today's behaviour). A destination-level poll would
  stop it and change what the chat shows on return.
- **D0 may not be cheap.** If the popup loop is a Robolectric defect with
  no app-side cause, the fallback test approach is bounded frames by hand;
  the phone check covers what the tests then cannot (confidence: low on the
  root cause, high that it is the popup window).
- **A transition later reintroduces two composed destinations** for its
  length (D4). Written down so that pass is reviewed against D5.

## D9. Migration order (the app works after each step)

1. Task 0 (D0): the popup test fixed; the suite runs.
2. Tests: characterization tests on today's code (pass), leak tests (fail).
3. `AgentStack` and `AgentScreen`, plain, with tests; nothing wired.
4. Extract `AgentKeeper` from `TerminalRoute`, wiring unchanged: the `Box`
   still stacks, the keeper only holds what `remember(key)` held. All
   tests as before.
5. `ModalPopup`; `TitleBarMenu` on it; the gauge's detail into it.
   Independent of the stack.
6. `ScreenStackHost` replaces the `Box`; covers through `AgentStack`; the
   focus rule; Back handlers moved into destinations; the grid seed;
   renderer into the keeper. Leak tests pass.
7. Deletions: `heldGrid`, semantics wrappers, `covered`, `undrawnWhen`,
   `coversBelow`, opener hides. Rename `TerminalRoute` to `AgentHost`.
8. Full suite, goldens, Codex review, phone check.

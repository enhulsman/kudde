# Design: screen-stack

Revision 2: answers the second-model critique of revision 1 (twelve
findings, all checked against the code; finding 12 holds only in part, see
D2). Line numbers are the repository's at 1ae6a3c (branch
`spec/spaces-navigation`). Paths under `android/app/src/main/kotlin/dev/kudde/android/`
are written from `ui/` on. "F1" to "F12" name the critique's findings.

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
tests (supported by its source, F9). The earlier full run of groups 5 and 6
took 1 h 8 min, which these five cases (about 67 min) account for almost
entirely.

**Suspected cause** (confidence: low to moderate; F9: plausible, not
diagnosed). Trace sections accumulate, which is why the heap goes. Why so
many frames run is not known. The guess is that while a Compose `Popup` is
attached, the main looper runs frames back to back at one clock time, so
`idleFor(20 ms)` (`TitleBarMenuTest.kt:82`) never gets past its first
instant. That "same-instant starvation" is unproven. Note also what the
existing test file says about where it bites: the comment at
`TitleBarMenuTest.kt:46-50` implicates **removal** ("removing any Compose
Popup in a frame runs out of heap"), not opening; the results above show
the opening cases crawling for 13 minutes while `backCloses...` dies after
216 s, so both phases may loop. A probe that only opens a bare popup
(revision 1's probe) mainly tests opening. The probe below therefore covers
open, dismiss and teardown as three separate phases.

**Why a method timeout is not enough (F10).** A JUnit `Timeout` on the test
method does not bound the dangerous part: `@After`
(`controller.pause().stop().destroy()` then `idle()`, `TitleBarMenuTest.kt:73-82`)
and `KuddeTestRunner.afterTest` (`testing/HerdrTestRunner.kt:13-20`, which
calls `MainThreadDrain.run`, `testing/MainThreadDrain.kt:19-20`, an unbounded
`looper.idle()`) both drain the looper after the test body, so a looping
popup can still spin there. `Timeout` also runs the body on another thread,
which Robolectric's main looper does not like. So the bound has three
layers, none of them a method timeout:

1. **No unbounded drain.** Every popup test, `@After` and the runner's
   `MainThreadDrain` drive frames through one bounded helper
   (`testing/BoundedFrames.kt`): at most N frames (default 200), each one
   `idleFor(16 ms)`, failing with the frame count when N is hit ("looped:
   200 frames"). `MainThreadDrain.run` uses it too.
2. **The worker dies fast.** The Gradle unit-test task gets
   `-XX:+ExitOnOutOfMemoryError` and a smaller `-Xmx` for the worker, so a
   loop that does fill the heap ends the process in seconds with a clear
   message instead of a fifteen-minute GC crawl; plus a Gradle task
   `timeout` (20 minutes for `testDebugUnitTest`) so nothing runs for an
   hour again.
3. **A per-test watchdog in `KuddeTestRunner`** (a daemon thread started in
   `beforeTest`, 60 s): on expiry it prints the main thread's stack and
   interrupts it, so a looping test fails with a stack trace.

**How task 0 finds the cause**, cheapest first (F9: the cheap experiment is
Choreographer control, not a bisect of popup properties). One test class,
`testing/PopupFrameProbeTest.kt`, run alone, driven only by the bounded
helper so a loop fails fast:

1. **Control, then pause.** Count the frames a plain activity with no popup
   runs over 20 ms of looper time (the control; it must be small). Then the
   same with `ShadowChoreographer.setPaused(true)` and frames advanced by
   hand in bounded steps through three phases: **open** (a bare `Popup`
   with one `BasicText`), **dismiss** (the `Popup` leaves the composition),
   **teardown** (`controller.pause().stop().destroy()`). Record frames per
   phase and which phase first exceeds a few dozen. If bounded stepping with
   the Choreographer paused is finite in all three phases, that is the
   fix: a shared "popup-safe clock" in `testing/` that every popup test
   uses.
2. **Tell the probe's frames from the popup's.** The probe counts with a
   `Choreographer` frame callback that does **not** re-post itself (read
   around each bounded step), so any frame beyond the stepped ones is the
   popup's or Compose's. A probe that re-posted its own callback would
   itself produce the recurring frame requests it is looking for (F10).
3. **Only if 1 fails, bisect the popup**: `GraphicsMode.LEGACY`;
   `focusable = false`; a fixed-size popup instead of wrap-content; a
   constant position provider. Only the SDK 35 android-all jar is available
   offline (`robolectric.properties`), so "run on SDK 34" is not an option.
4. **Fix at the cause**, in order of preference: the popup-safe clock (1);
   a change to how the app's popups are built if one property triggers it
   (behaviour on the phone unchanged, checked at 7.1); else bounded manual
   frames. The fix counts as verified only by the class passing, not by the
   probe's reading: the critique's Choreographer idea is an experiment, not
   a known fix.

**The workaround that does not wait for the diagnosis.** New tests must not
lean on a Popup window, so task 0 never blocks them. `ModalPopup` (D6) is
built as two parts: `ModalPopupLayer(onDismiss, anchor, content)`, a plain
composable (the transparent full-size catcher and the placed panel, no
window), and `ModalPopup`, a thin `Popup { ModalPopupLayer(...) }`.
Everything the spec promises about touches (an outside tap closes, an
outside drag closes and steps nothing, Back closes, inner taps work) is
tested on the layer, composed straight into the activity's content: no
Popup window, so none of the Robolectric popup trouble. The thin wrapper
gets one test under the bounded clock (a window opens and its content is
the layer) and the phone check 7.1 covers its window flags. If task 0 is
not green after two builder runs, the existing popup cases of
`TitleBarMenuTest` are moved onto the layer plus that one wrapper test and
the rest proceeds; the report says so. Either way the new tests rely on
popups only through the layer.

Done: `TitleBarMenuTest` passes as a class in under 30 s, including
`backClosesTheMenuAndSendsNothing`, with the bounded helper in `@After` and
the runner; a unit test of the helper shows a deliberately looping fake
failing in seconds; the full suite passes.

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

- **Touch (F8: the revision-1 diagnosis was too broad).** Revision 1 said a
  lower layer "gets every event the top layer does not consume". That is not
  how Compose dispatches between siblings: hit testing walks the children of
  the `Box` from the top one down, and a lower sibling is reached **only at
  positions where the sibling above has no pointer-input handler that took
  the hit**; where one has, the lower one is not tried. What is true, and
  what the bugs show, is narrower: a top layer with an **inert region** (no
  `pointerInput` or `clickable` there) lets the touch fall through to
  whatever is below at that spot. `TabOverviewScreen` is a `Column` with a
  background and no pointer handler in its title area and gaps
  (`spaces/TabOverviewScreen.kt:90-98`), so the terminal's title-bar drag
  (`terminal/TerminalScreen.kt:391-418`) still stepped under it (point 9).
  The chat's `coversBelow()` (`chat/ChatScreen.kt:119-120`, used at `:157`)
  is exactly a handler that is present everywhere and consumes nothing: it
  blocks by being the hit, not by consuming. So the claim now reads "an
  inert region of a layer lets touches reach the layer below"; each overlay
  needs its own patch because each has its own inert regions. Tests cover
  inert and interactive regions of each cover separately (D10).
- **Focus and keyboard.** The terminal's keyboard is an Android view
  (`TerminalKeyboard`, `terminal/TerminalInputView.kt:31-44`); each opener
  hides it by hand (`TerminalRoute.kt:203`, `:209`, `:223`). The chat's
  `BasicTextField` (`ChatScreen.kt:586-592`) stays composed and focused
  under Changes, so the system shows the keyboard again for it and its
  cursor draws through (point 13).
- **Size.** While a cover shows, the terminal under it is still laid out,
  and the keyboard going down grows its grid; `heldGrid` (`:71`, `:187`,
  `:245-246`, `:252-253`) holds that size back so the desk keeps its size,
  then applies it on Back. The held grid only gates `phone`, which feeds
  `opener.onPhoneGrid` (`:94-100`); the `SizeSettler`'s own pending send
  (`:350`, `session::phoneSize`) is driven by the host's `LaunchedEffect`
  tick and is **not** held by `heldGrid` (D3, size rules).
- **Screen reader.** Lower layers are hidden by `clearAndSetSemantics`
  (`:218`, `:234`); a missed wrapper would expose them. No test covers
  those wrappers today (`src/test` mentions `clearAndSetSemantics`,
  `coversBelow` and `covered` only in `LoopTest`'s `undrawnWhen`), so there
  is no existing leak coverage that already passes (F11: the leak tests
  below fail on today's routes, for the reasons stated, checked at 2.4).

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
   ├─ effects: opener tick, cache, idle, lifecycle, report toast, ChatPolling (+ announcements, D5)
   ├─ BackHandler (lowest): keeper.back() -> leave
   └─ ScreenStackHost(keeper.top)        composes ONLY the top destination, D4
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

/** At most one cover over a base; the base itself is the chat switch's (ChatSwitch.chat), never stored here. */
class AgentStack {
    val cover: AgentScreen?                       // Changes / Overview, or null
    fun top(chat: Boolean): AgentScreen           // cover ?: (Chat if chat else Terminal)
    fun push(cover: AgentScreen)                  // ignored when a cover is shown; Terminal/Chat rejected
    fun pop(): Boolean                            // false when no cover: the caller leaves the agent
}
```

- **The base stays the switch's.** `ChatSwitch` (`chat/ChatSwitch.kt:35-67`)
  already decides terminal or chat, including the first answered poll that
  may settle it after the agent opened. Storing the base in the stack too
  would be a second source of truth. The switch is not a push: Back from
  the chat leaves the agent, as today (`TerminalRoute.kt:175`). The base
  can change **while a cover is on top** (the first answered poll arrives
  under Changes, F1); D3 says what that does and does not do to the session.
- **One cover, not a list (F12).** Revision 1 held a list "so a later cover
  costs nothing". That is misleading: a second level brings restoration and
  lifecycle obligations (what each cover keeps, what Back restores, what
  survives recreation) that nothing here designs. Changes and the overview
  never stack on each other today (Changes has no map, the overview no menu).
  So the stack is at most one cover, a `push` while one is shown is
  **ignored**, and a change that needs two levels designs those. A double
  tap that pushes the same cover twice is ignored for the same reason.
- **A tile of another terminal** in the overview calls `home.open`, which
  changes the key: the old host and keeper go, as today. Its own tile pops.
- **Hand-rolled, no library (F12: reasonable, with conditions).** Navigation
  Compose's real strengths are an established destination lifecycle, saved
  state and predictive Back. Here there is one independent base plus at most
  one cover, the state that must persist already lives in plain classes and
  `remember(key)`, and Navigation would add a dependency (the owner's call),
  a route type system and its own back stack beside `AgentsHome.openKey`,
  and would still need the keeper for the session. The condition is that the
  contracts a library would give for free are tested instead: Back and the
  gesture (D4), recreation (D3), focus (D5), and destination lifecycle
  (a left destination's composition is gone, 5.1). Confidence: moderate that
  hand-rolled is the smaller total cost here.

## D3. The keeper

`AgentKeeper` (`ui/agent/AgentKeeper.kt`), a plain class created by
`remember(key)` in `AgentHost` and released by that remember's
`DisposableEffect`. It is the existing `remember(key)` state of
`TerminalRoute`, gathered in one object with names, so its rules can be
tested without a composition. It takes its collaborators through `AgentPorts`
(D10), so the real host can be tested with fakes.

| Holds | Today at | Survives |
|---|---|---|
| `reopen: TerminalReopen`; per **generation**: `session`, `opener`, the `onInputWithoutView` hook | `TerminalRoute.kt:58-82`, `:84-87` | every destination change; replaced as one set when the generation changes |
| `screen: TerminalScreenState` (compose text, sheet, selection flag, zoom/pan, keyboard asked for) | `:61` | destination changes (not a recreation, see Lifetime) |
| `chat: ChatVisit` (hub chat, `ChatState` from `app.chatStates`, `ChatSwitch`) | `:63` | " |
| `stack: AgentStack` | `:66`, `:68` | " |
| `phone: GridSize?` the last phone grid this keeper measured; `measured` (a real grid arrived this generation) | `:93` | " |
| `renderer: TerminalRenderer` (its recorded row pictures) | `TerminalScreen.kt:716-721` | " |
| `actions: TerminalActions` | `:180-192` | " |

Not held, because they already live longer: `ChatState` (draft, attached
photos, pending, scroll place via `keep`/`startAt`, `chat/ChatState.kt`),
the zoom per terminal (`app.terminalZoom`), the chosen view (`app.chatView`),
the disk cache. Changes and the overview start fresh on each open, as today
(`TerminalRoute.kt:242`, `TabOverviewRoute`'s `rememberSaveable(machine, spaceId)`).

### Lifetime (F2: each event stated, each with a test)

| Event | What happens to the keeper and what is seen | Today |
|---|---|---|
| **Created** | When `AgentsRoute` first composes the open agent for `key` (a row, tile, strip, alert or step). It reads `home.arrivedByStep` once, for the first generation's dwell (400 ms for a step, else 0). A reopen after background never waits. | the same `remember(key)` |
| **Destination change** (push, pop, base switch) | Nothing is created or released: no detach, no size, no opener change. Only the terminal destination may send a size (below). | layers stay composed |
| **Leaving the agent** (Back from a base, a step or tile to another agent, Agents leaves for Machines) | Release: `session.close()` detaches (the desk takes its size back), the last frame goes to the disk cache, `opener.dispose()` so a pending open never fires, the hook is cleared. | `DisposableEffect` `:115-119`, `:84-87` |
| **The agent leaves the hub** (`AgentsHome.onSnapshot` calls `close()` when the key is no longer listed, `agents/AgentsHome.kt:195-203`) | `openKey` becomes null and the host leaves the composition **wherever the stack is**, also under Changes, so the same release runs once; no cover, popup or dwell outlives it. | same path |
| **Background idle** (Stay connected, 60 s stopped; `reopen.onIdle`) | Host-level effect, runs whatever is on top, also under a cover or a popup: detaches and keeps the last frame. The stack and screen state stay. | same (`:121-131`) |
| **Return from idle** (`reopen.onStart`, generation + 1) | The keeper swaps the **whole generation set** together: new `session`, new `opener` (dwell 0), new `onInputWithoutView` hook bound to the new opener, the old opener disposed. The seed frame (`takeSeed`) shows. A generation never mixes the previous one's session, opener or hook. Under a cover the new session opens **watching** at the last grid (it has not led, see below); it leads when the terminal is on top and measures. | today the terminal beneath leads at once, even under Changes; a deliberate small change |
| **Configuration change** (rotation, dark mode, font scale; the manifest has no `configChanges`, only `windowSoftInputMode` at `AndroidManifest.xml:29`) | The activity is recreated, `remember(key)` is gone: the keeper is released (detach) and a **new keeper** is made for the same key from `AgentsHome.openKey`. The stack is empty (a cover that was open is lost, as `changesOpen` and `overview` were); `screen` is fresh: **terminal compose text and the sheet are lost** (as today), pan is lost, zoom returns (`app.terminalZoom`); the **chat's draft, photos, scroll place and expanded history return** (`app.chatStates` is application-held); the chosen view returns. First generation: dwell 0, disk cache seed only. | identical (characterised first, 2.1) |
| **Process death** | Only `AgentsHome`'s `listSaver` (`:239-243`) comes back: the open key. A new keeper as at "Created", base from `app.chatView`; nothing of `screen`, the stack or `app.chatStates` survives. | identical |

Revision 1 said "only `AgentsHome`'s saver survives rotation and process
death", which conflated two cases: application-held chat state survives a
rotation and not a death, and the terminal's compose text survives neither.
The table is the contract.

The one deliberate difference from today is the reopen under a cover (row
"Return from idle"): it opens watching and takes over when the person comes
back to the terminal. It is the same rule as for the first open, it keeps the
desk's size untouched while the person looks at something else, and it is
listed in the proposal's "decided by me" so it can be reversed.

### Size rules (spaces spec, design D5 there) and the seed (F1, F11)

The rule that closes revision 1's hole (a seed becoming a takeover size
because the base switched under an open cover): **a size that is not a real
measurement of the terminal on screen is never sent to a leading
session.** The session's mode no longer "follows the base":

- `lead` is sent only when **the terminal destination is on top and has
  measured a real grid in this generation**. The `TerminalSession.follow` /
  `lead` calls move into `AgentKeeper.onTerminalGrid` and
  `AgentKeeper.terminalShown`; no other path leads.
- `watch` is sent when the chat becomes the top (today's `follow(true)`).
  An open that happens when the terminal is not on top or has not measured
  opens **watching**: `atPhoneSizeOnOpen` reads `terminalTop && measured`,
  not `!switch.chat`.
- A cover over a led terminal changes nothing (it stays leading, no size); a
  cover over the chat likewise. A **base change under a cover** (F1: the
  first answered poll turns chat into terminal while Changes is open) changes
  nothing either: the session stays watching and nothing is sent until the
  person goes back and the terminal measures. Test: chat base, Changes open,
  a poll that answers `offered` with the chosen view terminal: zero `lead`
  and zero `phoneSize` calls, checked before and after the 400 ms mark; then
  Back and one measured grid: `phoneSize` once, then `lead` once.
- `phoneSize` is safe while watching (both the plan's author and the critique
  read it in Rust): while watching it is only stored (`crates/kudde-mobile/src/term.rs:257`,
  `:280` without a connection; `crates/herdr-proto/src/viewer.rs:190-203`,
  `resize_phone` returns false when the mode is not Typing). Confidence:
  high. Sending the real size just before `lead` is therefore the right
  order, and the seed can never become the desk's size.

**The seed for an agent that opens on its chat.** With only the top composed,
a chat-first agent has no grid, yet must attach (watching at the desk's
size) because `ChatPolling` reads the terminal's text (`ChatRoute.kt:97-100`).
The keeper seeds the opener **at creation, and only when the base at creation
is the chat**, from, in order: its own last grid; the last grid any terminal
measured in this process (`app.lastPhoneGrid`; the phone's terminal area is
the same for every agent but for the queue strip and the keyboard); else an
estimate from the host's constraints with `phoneGrid`, minus the title bar's
minimum height and the keys rows. A terminal-base agent gets no seed and
waits for its first real measurement, exactly as today, so it opens leading
at the real size and is not a watch-then-lead.

**"No size while covered", also mid-settle (F11).** The `SizeSettler` (`:350`)
is ticked by the host's `LaunchedEffect(phone)` (`:94-100`), so a size
measured just before a cover opened can still go out after it. The rule: the
keeper's tick **does nothing while the terminal is not on top** (a pending
offer is dropped, not delayed), and on return the terminal measures again and
offers its current size once. The tests start from an initially settled grid
(clock past `SETTLE_MS`, settler idle) for the plain case, and from a pending
settle for the second case.

- The 400 ms wait for a step is the opener's and starts when the keeper is
  created; destinations do not affect it. Input during the wait still opens
  first (`TerminalSession.send` calls the hook, which calls `opener.openNow`).
- **Only the terminal destination measures.** Under a cover or the chat no
  grid arrives, so `heldGrid` goes.

## D4. Only the top is composed

`ScreenStackHost` (`ui/agent/ScreenStackHost.kt`):

```kotlin
@Composable
fun <D : Any> ScreenStackHost(top: D, content: @Composable (D) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    key(top) {
        // Backstop for a change nobody asked for (a poll turning chat into terminal); see D5. With transitions (below) the host
        // keeps the outgoing destination composed, input-gated, for the motion's length.
        DisposableEffect(Unit) { onDispose { focus.clearFocus(force = true); keyboard?.hide() } }
        content(top)
    }
}
```

(Sketch; the rule and its tests are the contract, not this code.) `key(top)`
disposes the old destination's whole composition: its `pointerInput`s, its
focused text field, its `TerminalKeyboard` (whose dispose already retires
the input view and puts its keyboard down, `TerminalInputView.kt:118-125`),
its `BackHandler`s and its semantics. Nothing of it can take a touch, focus
or a screen-reader visit, because it does not exist (at rest; during a transition the outgoing one
exists but is input- and semantics-gated, see Transitions).

Consequences, each a deletion:

- `clearAndSetSemantics` wrappers (`TerminalRoute.kt:218`, `:234`) go.
- `undrawnWhen` and the terminal's `covered` flag go (the terminal is not
  composed under the chat). `Modifier.undrawnWhen` (`ui/components/Loop.kt:94`)
  and its `LoopTest` case go with its last caller.
- `coversBelow()` (`ChatScreen.kt:119-120`, `:157`) goes: nothing is below
  the chat any more.
- `input.hide()` and `screen.inputOpen = false` in the openers
  (`:203-204`, `:209-210`, `:223-224`) go; D5's rule and the input
  view's retire do it.

### State a destination takes with it (F4)

Keeping the keeper's state classes does not keep everything the person sees:
some of it is `remember`ed inside the composition that goes. The inventory,
each with its decision (the owner's question 4 covers the first two rows):

| Visible state | Where it lives today | Over a cover or after a switch |
|---|---|---|
| Terminal selection (frozen text, handles, the layer's two scroll positions) | `screen.selecting` flag only; `grid.text()` frozen by `remember(grid)` (`TerminalScreen.kt:707-710`), scroll states by `rememberScrollState` (`:857-863`) | **Ends** when the terminal destination leaves (`selecting = false`). Keeping it would mean holding the frozen text and two scroll positions in the keeper, for a state a few seconds old |
| Chat expanded card (`AgentCard.open`) | `remember(row.key)` (`ChatScreen.kt:487`) | **Resets** (cards fold). Cheap to keep (a key set in `ChatState`), but a folded card is the default |
| Chat list place | `ChatState.keep/startAt` (`:262-272`) | **Kept**, tested also with a pending bubble and with history that changed while covered (below) |
| Chat draft, photos, pending | `ChatState` (application-held) | Kept. The tool sheet, tool detail and image viewer are screen-local and close, as on any leave |
| Terminal zoom, pan | `screen.watch` (`WatchView`) | Kept |
| Terminal scrollback offset and the "live" chip | `remember` in `TerminalScreen` (`:628`) | Returns to live. Today it is kept under the chat and covers (still composed), so this is a visible difference; named in the proposal so it is not a surprise |
| Compose sheet text and open flag | `screen` | Kept (focus is not, D5) |
| Title bar menu open | `TitleBarMenuState` `remember` (`:217`) | Closes |
| Spoken-screen cache | `remember(session)` (`:594`) | Rebuilt |

Chat list restoration cases: unchanged rows: same item and offset; **rows
appended at the bottom while covered**: the place is kept, unless the person
was following the bottom, in which case they follow; **history that changed
above**: whatever `startAt` does today, recorded by the characterization test
first (the point is that Back from Changes uses the same path as the switch
from the terminal), so a pending bubble and a changed history are in the
test, not only unchanged rows.

### Back (F6)

Compose gives the most recently composed enabled `BackHandler` priority. The
host registers "leave the agent" first; the top destination registers its own
inner handlers after it:

| Top | Back, in order |
|---|---|
| Terminal | keyboard (system, first); compose sheet closes (text dropped, as today); selection ends; leave the agent |
| Chat | keyboard (system, first); image viewer; tool detail; tool sheet; leave the agent |
| Changes | a diff goes to the list; the list pops |
| Overview | pops |

- **IME first.** With the keyboard up, the system takes the first Back to put
  it down (with predictive Back the IME gets the callback before the app);
  the app's handlers run only for the next one. No IME-aware handler is
  added, so dismissing the keyboard cannot leave the agent. Robolectric
  cannot show the IME consuming a Back, so this is a **phone check** (7.1).
- **Predictive Back.** The handlers are `androidx.activity.compose.BackHandler`s,
  which take part in the gesture's progress callbacks. A started-then-
  cancelled gesture delivers `onBackStarted` and `onBackCancelled` and no
  `handleOnBackPressed`: it must neither pop a cover, nor leave, nor detach.
  Tests dispatch the committed and the cancelled sequence on the activity's
  `OnBackPressedDispatcher` (`dispatchOnBackStarted`, `Progressed`, then
  `Pressed` or `Cancelled`), not only legacy key events (the existing popup
  test, `TitleBarMenuTest.kt:190-201`, dispatches key events only; the menu
  case adds the dispatcher path).
- **Repeated Back.** The handlers call `keeper.back()`, which reads the
  **current** stack (not a value captured at composition) and returns
  "popped" or "left". A second Back before the recomposition that removes
  the old handler therefore cannot pop twice or leave twice from one stale
  handler: two Backs from Changes are pop then leave, as two real Backs are.
  Test: two dispatches with no frame between; `home.close` called once and
  the base never shown twice.

### Transitions (owner answer 1, Ezra, 2026-11-21: in scope, designed motion)

The owner wants premium, situational motion: each screen change carries its
own enter and exit (a slide in from a direction, or a fade, depending on the
change), and Back is an interruptible, seekable predictive-Back gesture. He
calls this detail crucial. This plan does **not** pick the motion: the
directions, durations, springs and the predictive-Back look come from a
separate research and motion-prototype step that he signs off (task 1.4). The
structure below only has to be able to carry whatever is signed off.

**The approved directions, per transition** (owner, 2026-11-21; only the
direction and kind are decided, every number comes from the prototype):

| Transition (key in `AgentMotion`) | Direction approved |
|---|---|
| `toChat`, `toTerminal` | Fade-through with a slight scale. No slide: a sideways swipe already means "next agent", so a sideways motion here would read as a step. |
| `pushChanges` | Changes slides in from the right; the screen behind shifts left a little and dims. |
| `popChanges` | Predictive Back: the screen follows the thumb, shrinks to a card, the base shows behind it; release finishes, pulling back cancels. Also the unseekable run for a Back button or key. |
| `pushOverview` | The current screen shrinks into its tile in the overview (as Android recents does). |
| `popOverview` | Choosing a tab grows that tile to full screen; Back from the overview is the reverse, predictive. A tile of another agent's terminal changes the key (a new host), so it is the keeper-level open, not a motion of this host. |
| `menu` | The title bar's menu grows from the button it came from (scale and fade from the button's position; this is the popup layer's enter and exit, D6). |
| `leave` | Predictive Back out of the agent to the home screen (the home screen's own transition is not redesigned here). |

All run on spring motion in the style of Material 3 Expressive (its
`MotionScheme`: a spatial spring for movement and scale, an effects spring
for fade and dim). **Dependency note:** the app has no Material 3 library
(`gradle/libs.versions.toml` lists `compose-bom` and `activity-compose`
only; the theme is the app's own), so `MotionScheme` itself would be a new
dependency, which is the owner's call. The plan avoids it: the prototype
records the Expressive scheme's spring numbers (stiffness and damping ratio,
spatial and effects) into `motion.md`, and `AgentMotion.kt` holds them as
plain `spring(...)` specs. If the owner prefers the library, task 1.4 records
that decision and adds the dependency then.

**Reduced motion.** With the system's "remove animations" on
(`Settings.Global.ANIMATOR_DURATION_SCALE` = 0, which also gives
`LocalMotionDurationScale` 0), every transition is an **instant cut**: the
destination swaps in one frame, the predictive-Back gesture still commits or
cancels (a cancelled gesture changes nothing) but without a seekable
animation, and the input gate of the next paragraph is the same. `AgentMotion`
reads the scale once per change and picks the cut table when it is 0.

**Structure.**

- `ScreenStackHost(top, transitionFor, content)` is built on a seekable
  transition (`SeekableTransitionState` with `rememberTransition`, or an
  `AnimatedContent` whose transition can be driven by progress; the exact
  primitive is chosen at 5.1 from the prototype's needs, both are in the
  Compose version in use). `transitionFor(from, to, reason)` returns the
  enter and exit for **that pair and reason** (push a cover, pop a cover,
  base switch terminal to chat, chat to terminal, leave the agent), so each
  screen change has its own motion rather than one global animation.
- **All motion values live in one place**, `ui/agent/AgentMotion.kt`, marked
  "values from the signed-off motion prototype": one table keyed by the
  change (`push Changes`, `pop Changes`, `push Overview`, `pop Overview`,
  `to chat`, `to terminal`, `leave`), each entry its direction, enter, exit,
  duration or spring, and the predictive-Back scale and shift. Until task 1.4
  signs off, the file holds a clearly named placeholder table (instant, no
  motion) so the structure and the behaviour tests can be built and pass;
  the values change in that one file and nothing else. No value is invented
  in this plan.
- **Predictive Back is the pop path.** Back for a pop or a leave uses
  `PredictiveBackHandler` (progress as a flow of `BackEventCompat`), which
  drives the seekable transition: progress seeks it, a committed gesture
  runs it to the end and then pops or leaves, a **cancelled** gesture
  animates it back and changes nothing (no pop, no detach, no focus change).
  A tap on a visible Back button runs the same transition unseekably.
  Interruptible: a new push or pop, or a second Back, while one runs takes
  over from the current progress (the transition retargets), it is never
  queued behind the old one and never restarts from zero.
- **While two destinations are composed, only one may take input.** During
  an enter or exit both are in the composition for the length of the motion.
  The outgoing destination gets no input and no semantics from the moment the
  change starts: its `pointerInput` is gated off (a wrapper that consumes
  nothing and passes nothing, `Modifier.inputGate(enabled = false)`, its
  semantics cleared with `invisibleToUser`), and its focus is already gone
  (D5, the clearing runs before the motion starts). So D1's leaks cannot
  recur during the motion; the leak tests also run **mid-transition** (clock
  stopped halfway) and assert the same things as at rest. The incoming
  destination takes input once the motion ends (touches during the motion
  are ignored, the same gate), except that Back during the motion is
  accepted (interruptible).
- **Size, keeper and the 400 ms wait are untouched by motion.** The session
  rules of D3 key off the destination that is *on top* by state, not off the
  animation: `terminalShown` and the first measurement happen when the
  terminal is the target, so the terminal may measure while it is entering;
  nothing is sent for a destination that is only exiting.
- **Cost.** A transition composes two screens for its length, the terminal
  being the heavy one; the perf script of D10 therefore includes the motion,
  and the prototype step records frame times of its candidates on the test
  phone so the owner signs off on something that runs well.
- The golden tests capture screens at rest; motion is verified by
  deterministic frame-stepping tests (progress 0, 0.5, 1) and by eye on the phone.

## D5. Focus, keyboard and the screen reader

**Rule: only the top destination may hold focus or the keyboard.** A
destination change clears focus (forced) and hides the soft keyboard. A
destination takes focus on arrival only when the person asked for it **in
that visit** to the destination.

**Where the clearing happens (F5: disposal alone does not order it).** The
leaving destination's `onDispose` and the arriving one's first effects run in
the same apply step, and an IME hide is asynchronous, so "before the next
destination composes" is not something disposal guarantees (confidence:
moderate that the runtime forgets removed observers before it remembers new
ones; the IME's own callbacks come later in any case). So there are two
places, in order:

1. **On every person-driven change** (push, pop, base switch by a tap,
   leave), `keeper.navigate { ... }` first runs `focus.clearFocus(force =
   true)`, `keyboard.hide()` and the terminal input's `hide()`, **then**
   changes the stack or the switch. This is the ordering guarantee; it is
   tested with rapid swaps and a delayed IME callback.
2. **`ScreenStackHost`'s disposal** is the backstop for a change nobody
   asked for (a poll flipping the base while a view is on top).

**Re-entry to the terminal with an open compose sheet (F5).** Keeping
`composeOpen` means returning to the terminal recreates `ComposeSheet`, whose
`LaunchedEffect(Unit)` calls `requestFocus` again (`ComposeSheet.kt:71-72`,
fed by `composeFocus`, `TerminalScreen.kt:263-277`). That would bring the
keyboard back on a plain return, after the rule said it stays down.
Decision: **a return is not a fresh focus request.** `screen.composeFocusAsked`
is true from the moment the person opens the sheet until the terminal
destination leaves; the sheet gets `focus = screen.composeFocusAsked` when it
is composed; leaving clears it. After Changes the sheet shows with its text,
unfocused, keyboard down, and a tap in it asks. The keyboard key and chip
are likewise a request of the visit that made them: `inputOpen` is cleared on
leave so the keys row does not return expecting a keyboard. The host's
`LaunchedEffect(imeVisible)` (`:149`) stays.

**What the person sees.**

- Changes and the overview have no text field, so after opening either no
  node has focus and the keyboard is down; it stays down because nothing
  asks for it (point 13).
- Back to the chat does not bring its keyboard back; a tap in the field does
  (as today after Changes was closed by hand).
- Switching terminal to chat or back is a destination change: the keyboard
  goes down (today's `onChat` hide, now by the rule).
- A step to another agent changes the key, so the old host goes and the
  keyboard goes down (accepted, point 14); `TerminalSwitchTest` keeps
  holding that input never crosses terminals.

**Screen reader (F7).** Three things, each decided:

- **Which nodes exist.** Only the top destination's, tested on the real host.
- **Announcements of live answers keep their timing.** Today the chat is
  composed under Changes, so its effect (`ChatScreen.kt:149-155`, turning
  newly live "said" rows into `announceForAccessibility` once) runs while
  Changes shows, and the person hears answers that arrive meanwhile. Without
  the chat composed that effect stops, while `ChatPolling` (host-level,
  `ChatRoute.kt:94-109`) keeps counting rows live, so on return
  `Announced.next` would read everything that arrived while covered **at
  once, late**. The announcement therefore **moves from `ChatScreen` to the
  host-level `ChatPolling`**, beside `state.live.saw`: each live answer is
  announced once, when it arrives, covered or not, and not again on return.
  (Confidence: high that this keeps today's timing. `Announced` still counts
  either way, so turning a reader on later does not read old answers.) Test:
  reader on, Changes over the chat, an answer arrives: announced once now;
  Back: not announced again.
- **Where the reader's focus lands after a change.** No new spoken text is
  added (no `paneTitle`; the owner has not seen new texts, see "Nothing else
  changes"): the system places accessibility focus in the new content, as it
  does today when a destination appears. The ears check 7.3 records where it
  lands for: to Changes, back to the chat, to the overview, back to the
  terminal. The fallback if it lands badly (on a hidden or last element): one
  `paneTitle` per destination root and a focus request on the title bar, with
  the text shown to the owner verbatim first. Not built unless 7.3 asks.

## D6. The menu's popup blocks what is below

`ModalPopup` (`ui/components/ModalPopup.kt`) is two parts (D0): the plain
composable `ModalPopupLayer` and the window `ModalPopup { ModalPopupLayer }`.
The layer fills the window with a transparent catcher that consumes every
pointer event and closes the popup on a tap or a drag that **starts** outside
the panel (a drag that starts inside belongs to the panel); the panel is
placed inside it at the anchor by the existing position rule (`MenuPosition`,
`TitleBarMenu.kt:161-171`). Back closes it (`onDismissRequest`; the gesture
path of D4 applies). Whatever `PopupProperties` do with outside touches on a
given Android version, nothing outside the panel can reach the window
below, because the popup's own window covers it. The catcher is an
**interactive** region (it takes the hit), unlike the inert region of D1.

- `TitleBarMenu` (`TitleBarMenu.kt:72-103`) moves onto it; its `ShadowRoom`
  tap-to-close padding becomes part of the catcher. Look unchanged.
- **The gauge's detail is not part of this change** (owner decision,
  2026-11-21). It stays in `usage-gauge` (ring plus a bottom sheet with bars,
  sliding up), the next change; the chat's gauge strip and its `gaugeOpen`
  are left as they are, including the jump of point 10, which `usage-gauge`
  removes. This change provides only the popup layer that sheet can build on,
  and neither designs nor copies the sheet's content. The gauge goldens do
  not change here.

Tests read touches on the **layer** composed in the activity (no window), plus
one on the window wrapper under the bounded clock: a drag across the title
bar's area outside the menu's panel steps nothing and closes the popup; a tap
there sends nothing to the terminal; a tap inside the panel does not close
it; Back (dispatcher path) closes only it.

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

- **The terminal is recomposed on each return.** Its grid and renderer live
  in the keeper, so the cost is composing one screen. Measured at 7.1 with
  the script and pass line of D10 (confidence: moderate that it is not felt).
- **The chat is recomposed on each return.** Its place is kept in `ChatState`;
  D4 lists what else resets.
- **Chat-first agents need a grid** (D3). Mitigated by the seed only ever
  feeding a watching open, and `lead` never being sent without a real
  measurement of the shown terminal.
- **`ChatPolling` stays host-level**, keyed on the base, so the chat keeps
  polling under Changes (today's behaviour), and its announcements move there.
- **Back and the keyboard cannot be fully tested in Robolectric** (the IME
  consuming a Back, the predictive animation); covered by the phone check.
- **D0 may not be cheap.** The layer/wrapper split means the rest does not
  depend on it.
- **Motion composes two screens for its length** (D4): input gating and the mid-transition leak tests are the mitigation; the motion's frame cost is measured at 7.1.
- **The motion values are not known yet.** They come from the signed-off prototype (1.4); the build proceeds with a placeholder table in `AgentMotion.kt`, so a late or changed sign-off touches one file.
- **A second cover level is not designed** (D2); pushing one is ignored.

## D9. Migration order (the app works after each step)

0. The motion prototype and the owner's sign-off (task 1.4) run beside 1 to 6; the build proceeds on the placeholder table, the signed-off values land in `AgentMotion.kt` before step 9.
1. Task 0 (D0): bounded frames, the diagnosis, the suite runs.
2. The production seam (D10): `AgentPorts` extracted from `TerminalRoute`, behaviour unchanged.
3. Tests on the real route: characterization (pass), leak (fail).
4. `AgentStack` and `AgentScreen`, plain, with tests; nothing wired.
5. Extract `AgentKeeper` from `TerminalRoute`, wiring unchanged: the `Box`
   still stacks, the keeper only holds what `remember(key)` held.
6. `ModalPopup` (layer and wrapper); `TitleBarMenu` on it; (the gauge's detail stays out, it is `usage-gauge`'s).
7. `ScreenStackHost` (with its seekable transition, the placeholder motion table and the input gate) replaces the `Box`; covers through `AgentStack`; the
   focus rule; Back handlers into destinations; the size rules and seed;
   announcements to the host; renderer into the keeper. Leak tests pass.
8. Deletions: `heldGrid`, semantics wrappers, `covered`, `undrawnWhen`,
   `coversBelow`, opener hides. Rename `TerminalRoute` to `AgentHost`.
9. Full suite, goldens, Codex review, phone check.

## D10. How the tests exercise the real routes (F3, F11)

Revision 1's group 2 built a harness that recomposed the screens and
re-implemented what `TerminalRoute` does around them. But the behaviour at
stake lives in the **routes**: Back (`TerminalRoute.kt:168-177`), the
attach/detach effects (`:104-144`), the held grid and its release (`:187`,
`:245-253`), the opener, the focus hides. A test of a copy of those proves the
copy. (Existing tests already do this: `TerminalTouchTest.kt:78` and
`TitleBarActionsTest.kt:93` say "built as TerminalRoute builds them".)

**The production seam, built first (task 2.0).** Behaviour unchanged, no new
screen. The route's external dependencies are `KuddeApp` (hub, cache, chat
states, zoom, executors) and `AgentsHome`. Robolectric tests already have a
real `KuddeApp` (`RuntimeEnvironment.getApplication() as KuddeApp`,
`HerdrAppTest.kt:22-27`). What cannot be faked through it is the hub, a
generated class. So:

- `AgentPorts` (`ui/agent/AgentPorts.kt`): `openTerminal(key, cols, rows,
  lead, listener): TerminalViewInterface?`, `openChat(key): ChatInterface?`,
  `openChanges(key): ChangesInterface?`, `now(): Long` (the opener's
  `SystemClock.uptimeMillis` source). The production implementation wraps
  `app.hubHost.hub`; the fakes implement the generated interfaces
  (`FakeTerminalView` exists).
- `TerminalRoute(app, home, key, ports = HubPorts(app))` keeps its call
  shape; tests pass `FakeAgentPorts`. The tests compose the **real**
  `TerminalRoute` (later `AgentHost`), `ChatRoute`, `ChangesRoute`,
  `TabOverviewRoute`, and the real `AgentsRoute` where leave and remove cases
  need `AgentsHome` (`AgentsRoute.kt:89-111`), with the real `KuddeApp`.
- `ui/agent/AgentRouteRig.kt` (test source) only builds fakes and drives the
  clock. It composes no screen of its own and holds no route logic: it may
  not mention `heldGrid`, `inputOpen`, or Back handling.

The same classes run before and after the restructure: the characterization
group (pass today) and the leak group (fail today) point at one entry point.

**Real-host cases F3 requires**, each a named test in tasks: detach of the old
session before the next attach on a step; leaving at 399 ms (no attach, no
detach, the desk untouched) and at 400 ms (attached); input during the dwell
opens first, then sends; disposal cancels an unopened destination (the opener
never opens after the host leaves, also under a cover and with a pending
dwell). They come from the spaces spec's step scenarios
(`openspec/changes/spaces-navigation/specs/spaces/spec.md:448-455`).

**Acceptance boundaries (F11).**

- "No size while covered": from an initially settled grid (clock past
  `SETTLE_MS`, settler idle), open a cover, advance 2 x `SETTLE_MS`: zero
  `phoneSize` calls. A second case opens the cover while a settle is pending:
  zero too.
- "At once": with the placeholder (no-motion) table, **at most one
  recomposition frame** after the Back dispatch (no `delay` on the path; no
  `phoneSize`, attach or detach call). With real motion: the screen is
  reachable and interactive within the signed-off duration plus one frame.
- "Anything that should not have changed": the existing goldens (verify, not
  record), the named existing tests kept at 5.3, and the new characterization
  classes. No free-text "anything".
- Performance: a fixed script (`scripts/phone.sh` round: open an agent on its
  terminal; 30 cycles of terminal, Changes, Back, and 30 of chat, Changes,
  Back, one second apart), `dumpsys gfxinfo` reset before and read after, on
  the same phone, the build before this change first (baseline) and then the
  build after. **Pass: the janky-frame percentage not above the baseline's
  plus 2 points, and the 90th-percentile frame time not above the baseline's
  plus 2 ms.**
- The leak tests do not lean on the existing wrappers: those are untested
  today (D1), so the cases fail on today's routes because the covered chat's
  and terminal's nodes exist.

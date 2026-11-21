# Tasks: screen-stack

Marks: **[owner]** needs Ezra's decision, eyes or ears; **[phone]** needs a
phone. A task without a mark is one unattended builder run. Android checks
run from `android/`. Every test is written before the code it covers, and a
failing test is fixed in the code, never weakened. The order follows design
D9: the app works after every task.

## 0. The stuck popup test (before anything else)

- [ ] 0.1 Probe, as design D0 steps 1 and 2: a new `testing/PopupFrameProbeTest.kt` with a 60 s `Timeout` rule: a bare `Popup` with one `BasicText`, counting frames (`withFrameNanos` and a `Choreographer` callback) over one `idleFor(20 ms)`; then the bisect variants (`GraphicsMode.LEGACY`, `focusable = false`, a fixed-size popup, a constant position provider, `idle()` with single steps). Record the frame counts per variant in the report. Check: `./gradlew :app:testDebugUnitTest --tests '*PopupFrameProbeTest'` ends within 2 minutes
- [ ] 0.2 Fix at the cause 0.1 names (design D0 step 3, in its order of preference), in one shared place (`testing/` or the app's popup code). Keep or remove the probe as the fix makes sense. Give Robolectric UI tests a ceiling (D0 step 4): a `Timeout` rule or `KuddeTestRunner`. Check: `./gradlew :app:testDebugUnitTest --tests '*TitleBarMenuTest'` passes all seven cases, `backClosesTheMenuAndSendsNothing` included, in under 30 s
- [ ] 0.3 The full suite on the unchanged app, and the time it took (the last full run took 1 h 8 min). Check: `./gradlew :app:testDebugUnitTest --rerun --max-workers=2 && ./gradlew :app:verifyRoborazziDebug`; `./gradlew --stop` afterwards

## 1. Paperwork

- [ ] 1.1 **[owner]** Answer the proposal's three questions (transition, the gauge's detail, the issue). Adjust `specs/agent-screens/spec.md`, design D4 and D6 where an answer differs from the pick. Check: `openspec validate screen-stack --strict`
- [ ] 1.2 **[owner]** OK to open the GitHub issue "One screen at a time inside an agent" in `v1.0.0` linking this change (text shown verbatim first; owner question 3), move it to In Progress, and close it from the PR with `Closes #n`.
- [ ] 1.3 Record the answers in `decisions.md` (one line, dated). Check: `openspec validate screen-stack --strict`

## 2. Tests first: what must hold, and the leaks

A test harness `ui/agent/AgentScreensHarness.kt` (test source) builds an
open agent as `TerminalRoute` does, without `KuddeApp`: a `TerminalSession`
on `FakeTerminalView`, `TerminalScreenState`, a `ChatState` and
`ChatSwitch`, fake Changes (`ChangesInterface`) and an overview fed by
`SpacesFixtures`, and the real `TerminalScreen`, `ChatScreen`,
`ChangesScreen` and `TabOverviewScreen`. Until task 5 it stacks them as the
`Box` of `TerminalRoute.kt:199-258` does; task 5 switches it to the host.

- [ ] 2.1 Characterization tests (pass on today's code), `ui/agent/AgentScreensCharacterizationTest.kt`: Back from Changes returns to the view it came from (terminal; chat), and from the overview; Back from the terminal and from the chat calls `home.close` once; the compose sheet and the selection close before leaving; the compose text survives Changes and the overview; `FakeTerminalView.calls` shows no `detach` and no `phoneSize` while a cover shows, and no `lead` or `watch` from opening or closing a cover; the terminal over the chat base is `watch`, over the terminal base `lead`; a tile of another terminal calls `home.open`, its own tile closes the overview. Check: `./gradlew :app:testDebugUnitTest --tests '*AgentScreensCharacterizationTest'` passes
- [ ] 2.2 Characterization of the chat's place: after Changes over the chat and Back, the list starts at the row it was left at (`ChatState.keep`/`startAt`) with the draft and attached photos unchanged. Check: as 2.1, passes
- [ ] 2.3 Leak tests (fail on today's code), `ui/agent/AgentScreensLeakTest.kt`, one per scenario of "One screen of an open agent at a time" and "Only the top screen holds the keyboard": a sideways drag at the title bar's height with the overview open, and with Changes open, calls neither `onTab` nor `onSwipe` and plays no haptic; with Changes over the chat no semantics node of the chat or the terminal exists (read with `testing/Semantics.kt`); with the chat's field focused, opening Changes leaves no focused node and `ShadowInputMethodManager` reports the keyboard hidden, also after the frames that follow; Back to the chat leaves the keyboard hidden; the terminal's keyboard is hidden after opening the overview and a key sent to the old input view reaches no sink. Check: the class compiles and its tests fail for the reasons named, not by crashing
- [ ] 2.4 Popup leak tests (fail on today's code), in `TitleBarMenuTest` and `ui/chat/ChatGaugeTest.kt`, as design D6: a sideways drag dispatched to the menu's popup window across the title bar's area steps nothing, sends nothing to the terminal and closes the menu; the gauge's detail opens in a popup window (`windowRoots()` grows by one) and the chat's message list keeps its bounds; a tap in the gauge popup's window outside the panel closes it and reaches no row; Back closes it and not the chat. Needs 0.2. Check: the new cases fail for the reasons named

## 3. The stack and the keeper as plain Kotlin

- [ ] 3.1 Tests first, `ui/agent/AgentStackTest.kt` (design D2): `top` for each base with no cover; push Changes, push Overview; a second push of the top cover is ignored; Terminal or Chat as a cover is rejected; `pop` returns false with no cover and true otherwise. Then `ui/agent/AgentStack.kt`. Check: `./gradlew :app:testDebugUnitTest --tests '*AgentStackTest'`
- [ ] 3.2 Extract `AgentKeeper` (design D3) from `TerminalRoute`: the reopen, session, opener and hook, `TerminalScreenState`, the chat visit, the stack (covers replace `changesOpen` and `overview`), the last phone grid, the actions. Effects stay in the route. The `Box` still stacks; `heldGrid` stays for now. Every existing test passes unchanged, and 2.1 and 2.2 still pass. Check: `./gradlew :app:testDebugUnitTest --tests '*Terminal*' --tests '*Chat*' --tests '*TitleBar*' --tests '*AgentScreens*Characterization*' --tests '*AgentsHomeTest'`
- [ ] 3.3 Tests first, `ui/agent/AgentKeeperTest.kt` with `FakeTerminalView` and clock arguments (design D3 "Tests"): a keeper created by a step opens at 400 ms whatever the covers do; push and pop never detach nor send a size; on a chat base it opens watching with the seed (own grid, else `lastPhoneGrid`, else the estimate); the first terminal grid sends `phoneSize` and then `lead`, in that order; release detaches once and returns the last frame; a released keeper never opens. Then the seed, `onTerminalGrid` and `app.lastPhoneGrid`. Check: `./gradlew :app:testDebugUnitTest --tests '*AgentKeeperTest' --tests '*TerminalOpenerTest' --tests '*TerminalReopenTest'`
- [ ] 3.4 Read `crates/kudde-mobile/src/term.rs` (`Cmd::PhoneSize` while observing, `resize_phone`) and state in the report whether a phone size sent while watching can resize the desk. If it can, stop and report (design D3 relies on it). No Rust change in this task.

## 4. Popups that block (independent of the stack)

- [ ] 4.1 `ui/components/ModalPopup.kt` (design D6) and `TitleBarMenu` on it, look unchanged. 2.4's menu cases pass; the other `TitleBarMenuTest` cases and the `terminal-menu-open-*`, `chat-menu-open-*` goldens unchanged. Check: `./gradlew :app:testDebugUnitTest --tests '*TitleBarMenuTest' && ./gradlew :app:verifyRoborazziDebug`
- [ ] 4.2 The gauge's detail into a `ModalPopup` under the title bar, the strip's look (owner answer 1.1). 2.4's gauge cases pass. Re-record `gauge-claude` and `gauge-codex` drawing the panel where the popup places it, as the menu goldens do; no other golden changes. Check: `./gradlew :app:testDebugUnitTest --tests '*ChatGaugeTest' --tests '*ChatScreenshotTest' -Proborazzi.test.verify=true`

## 5. One screen at a time

- [ ] 5.1 `ui/agent/ScreenStackHost.kt` (design D4, D5) with its own tests, `ScreenStackHostTest.kt`, on fake destinations: only the top's content is composed; a focused text field in a destination that leaves is unfocused and the keyboard hidden; a `pointerInput` in a left destination receives nothing; `BackHandler`s of a left destination do not run. Check: `./gradlew :app:testDebugUnitTest --tests '*ScreenStackHostTest'`
- [ ] 5.2 Replace the `Box` in `TerminalRoute` with `ScreenStackHost(keeper.top)`; the terminal's compose and selection Back handlers move into the terminal destination; `screen.inputOpen` cleared when the terminal destination leaves; `ChatPolling` stays at the route, keyed on the base; the renderer moves into the keeper; the seed from 3.3 feeds the opener. Switch the harness of group 2 to the host. All of 2.1 to 2.3 pass. Check: `./gradlew :app:testDebugUnitTest --tests '*AgentScreens*' --tests '*Terminal*' --tests '*Chat*' --tests '*TitleBar*' --tests '*AgentsHomeTest' --tests '*ReadOnceTest'`
- [ ] 5.3 Deletions (design D4): `heldGrid`; the `clearAndSetSemantics` wrappers; `covered` in `TerminalScreen`/`TerminalArea`; `undrawnWhen` and its `LoopTest` case once unused; `coversBelow`; the openers' `input.hide()` and `inputOpen` lines. Rename `TerminalRoute` to `AgentHost` (`ui/agent/`). Tests that asserted "under the chat" behaviour of the composed terminal (`TerminalTouchTest.aStepIsRefusedWhileTheComposeTextIsNotEmptyUnderTheChat`, `TitleBarActionsTest.theActionsWorkUnderTheChatToo`) keep their assertions, built through the host; name each such test in the report. Check: `./gradlew :app:testDebugUnitTest --tests '*Terminal*' --tests '*Chat*' --tests '*TitleBar*' --tests '*Loop*' --tests '*AgentScreens*'`

## 6. Whole checks

- [ ] 6.1 The whole suite as CI runs it, and no golden changed but the two gauge goldens of 4.2. Check: `cargo fmt --all --check && cargo clippy --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings && cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike && (cd android && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug)`
- [ ] 6.2 A second-model adversarial review of the branch against `spec/spaces-navigation` and this spec, read-only, focused on focus and touch ownership between destinations, the keeper's lifetime against the desk's sizes and the 400 ms wait, the grid seed for agents opened on their chat, and the popups' touch blocking. Fix what holds; record what did not hold and why in the report.

## 7. On the phone

- [ ] 7.1 **[phone]** Perf build on the test phone (`./gradlew :app:assembleDebug -Pkudde.perf`, installed with `--user 0` as `AGENTS.md` says), staged agents (`docs/phone-testing.md`): the overview open, a sideways swipe across the title bar's area steps nothing; Changes from the chat with the keyboard up, the keyboard goes down and stays down, no cursor; the menu and the gauge's detail open, a swipe outside only closes them; Back from Changes and the overview shows the view it came from at once; an agent opened on its chat (Codex) attaches watching and the desk's size does not change, read from the desk before and after; the first switch to its terminal takes over once, at the right size; steps and the 400 ms wait as before. `dumpsys gfxinfo` before and after a scripted round of switches, compared with the build before this change. Record what was tried.
- [ ] 7.2 **[owner] [phone]** On Ezra's daily phone, perf build, a short try-out of his points 9, 10 and 13 and of anything that should not have changed.
- [ ] 7.3 **[owner] [phone]** With TalkBack on: going to Changes, the overview and back, only the shown screen is heard, and nothing heard before is missing.

## 8. Follow-ups made easy by the keeper (issues only, not built here)

- [ ] 8.1 **[owner]** Drafts, shown verbatim before filing, for (A) an in-memory last screen per terminal shown at once on a step or tap, and (B) the tab's other panes watched live without resizing the desk (design D7), and the designed transition between destinations (owner question 1). Search open and closed issues first; link existing ones instead of new ones.

## 9. Wrap-up

- [ ] 9.1 `decisions.md`: the structure decision (one screen at a time, keeper per agent) in one line.
- [ ] 9.2 Release notes line for the leaks fixed, shown to Ezra first. **[owner]**
- [ ] 9.3 `openspec validate screen-stack --strict`, then archive with the PR.

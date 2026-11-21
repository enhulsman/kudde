# Proposal: one screen at a time inside an agent

## Plain summary (for Ezra)

When you open an agent, the app can show four screens: the terminal, the
chat, Changes and the tab overview. Today the app draws them on top of each
other, and the screens underneath keep listening. That is why a swipe on the
tab overview still moved to the next pane behind it, and why the keyboard
came back over Changes with the chat's cursor blinking through it.

This change makes the app show exactly one of those screens at a time. The
others are not just hidden, they are gone until you go back to them. What
must not be lost while you look at another screen (the live connection to
the terminal, its size on the desk, your chat draft, where you had
scrolled) is kept by one helper per agent that lives beside the screens, so
coming back needs no reconnect.

### Decided (Ezra, 2026-11-21)

- **Switching between these screens is animated, not a cut.** Designed,
  premium motion that depends on the situation (a slide in from a
  direction, or a fade, per screen change), with a Back gesture you can
  follow with your thumb, let go of and cancel (Android's predictive Back).
  You called this detail crucial. The exact motion (directions, speeds,
  springs) is **not decided here**: a separate research and prototype step
  produces it and you sign it off before it ships (task 1.4). The structure
  is built so every screen change can carry its own motion and the values
  sit in one place.
- **The gauge's detail is not in this change.** It stays in the separate
  `usage-gauge` change (the ring and a Usage sheet with bars sliding up from
  the bottom), which comes next; that change also removes the chat's jump
  when the gauge opens (point 10). Here the gauge's line in the chat stays as
  it is today.
- **Motion direction approved** (the values still come from the signed-off
  prototype): terminal and chat fade through each other with a slight scale
  (no slide, since a sideways swipe already means "next agent"); Changes
  slides in from the right while the screen behind shifts left a little and
  dims; Back from Changes is the predictive Back (the screen follows your
  thumb, shrinks to a card with the terminal showing behind, release
  finishes it, pulling back cancels); the tab overview is the current screen
  shrinking into its tile (like Android recents) and choosing a tab grows
  that tile to full screen; the title bar's menu grows from the button it
  came from; all on spring motion in the style of Material 3 Expressive; with
  the system's "remove animations" on, every change is an instant cut.
- **A GitHub issue is opened** in the `v1.0.0` milestone, "One screen at a
  time inside an agent", linking this change. No existing issue covers it.
  The text is shown to you first (task 1.2).

### Your questions (my pick first)

1. **What resets when you leave a screen and come back.** *Pick: a
   terminal text selection ends, and an expanded chat card folds again;
   everything else you listed stays (draft, scroll place, zoom, compose
   text).* Also: the terminal's scroll-back position returns to the live
   end (today it stays where you left it). Other option: (b) keep the
   selection, the cards and the scroll-back too, which means storing more
   per agent. Why (a): these are a few seconds old, and keeping them is
   extra machinery for little.

Decided by me (tell me if you want one reversed): no new library (a small
list of screens written in the app's own style is enough; Google's
navigation library would add a dependency for four screens), only one
screen can sit over the base (Changes or the overview, never both), the tab
overview's swipe between tabs (your point 7) is not in this change, nothing
about the 400 ms wait changes, and when the app comes back from the
background on a covered terminal it reopens watching and takes the desk's
size only when you go back to the terminal (today it takes it at once under
Changes).

### What changes for you on the phone

- With the keyboard up in the chat, opening Changes puts the keyboard down
  and it **stays down**; no chat cursor shows over Changes (point 13).
- A swipe on the tab overview, also where the title bar usually sits,
  **never reaches the terminal behind it**: no pane changes (point 9).
- While the title bar's menu is open, a tap or swipe anywhere outside it
  **only closes it**; nothing underneath reacts. (The gauge's detail, and
  the chat jump of point 10, are `usage-gauge`'s, next.)
- Going back from Changes or the tab overview shows the terminal or chat
  you came from, as it was: same chat scroll, same draft, same zoom, with
  the signed-off motion.
- Changing screen has a designed motion per change, and Back is a gesture
  you can follow, let go of and cancel (after your sign-off, task 1.4).
- The screen reader hears new chat answers when they arrive, covered or not,
  as today.
- **Nothing else should look or behave differently.** Steps between
  panes, the 400 ms wait, the keyboard going down when you switch terminals
  (you said that is fine, point 14), Back's order, and what TalkBack reads
  stay as they are. If anything else feels different on the phone, that is a bug in
  this change.

Not in this change, but made easy by it: showing a terminal's last screen
instantly on a step even when the encrypted cache is empty (points 3, 11
and 12), and watching the other panes of the tab live without resizing the
desk.

### Before the restructure: the stuck test

One test of the title bar's menu takes about 13 minutes per case and then
runs out of memory, so the full test suite cannot finish. It is fixed first
(task 0), because the restructure leans on that suite, and because the menu's new
window is tested the same way.

## Why

Ezra's phone session of 2026-11-21 (his points 9, 10, 13 and 15) found a
family of bugs with one cause: inside an open agent, `TerminalRoute` draws
the terminal, the chat, Changes and the tab overview stacked in one `Box`
(`ui/terminal/TerminalRoute.kt:199-258`). Lower layers stay composed; only
their semantics are cleared. Where the top layer has an inert region, touches reach the layer below; their text fields keep focus, and every opener
must remember to hide the terminal's keyboard by hand. Each new overlay
repeats the risk. He asked for a structure in which this kind of bug cannot
happen again.

## What changes

- Inside an open agent the four screens become **destinations** of a small
  per-agent stack: a base (terminal or chat, chosen by the existing switch)
  and covers pushed over it (Changes, tab overview). Only the top
  destination takes input and is composed at rest (two are composed for the length of a motion); Back pops.
- A per-agent **keeper** (`AgentKeeper`) holds what must outlive a
  destination: the terminal session and its attachment, the opener and its
  400 ms wait, the background reopen, the screen state (compose text, zoom,
  keyboard asked for), the terminal's renderer, the chat visit and switch,
  the last phone grid. It is created when the agent opens and released when
  it is left, as today.
- **Motion**: the screen holder carries a per-change enter and exit and a
  seekable predictive-Back transition; all values in one file
  (`AgentMotion.kt`) filled from the signed-off prototype. While two
  screens are on screen during a motion the outgoing one takes no input.
- One **focus rule**: only the top destination may hold focus or the
  keyboard; a destination change clears focus and puts the keyboard down in
  one place, replacing the hand-written `input.hide()` calls.
- The title bar's menu opens in a **modal popup** whose window
  covers the screen and takes every touch outside the panel (built as a
  plain layer plus a thin window, so it can be tested without the
  Robolectric popup trouble).
- The keeper's size rule: the desk is taken over at the phone's size only
  when the terminal is on screen and has measured; a seed or an estimate is
  only ever used for a watching attach.
- A production seam (`AgentPorts`) so tests drive the real routes.
- Live chat answers are announced from the host, so a cover does not change when they are heard.
- Removed with the stacking: `heldGrid`, the `clearAndSetSemantics`
  wrappers, `undrawnWhen` under the chat, the terminal's `covered` flag and
  the chat's `coversBelow`.
- Task 0: the Robolectric popup test that runs out of heap.

## Impact

- New capability `agent-screens` (behaviour deltas only; see specs).
- Code: `ui/terminal/TerminalRoute.kt` (becomes the host), new
  `ui/agent/AgentKeeper.kt`, `AgentStack.kt`, `ScreenStackHost.kt`,
  `ui/agent/AgentMotion.kt`, `ui/agent/AgentPorts.kt`, `ui/components/ModalPopup.kt`; `TitleBarMenu.kt`, `ChatScreen.kt`,
  `TerminalScreen.kt`, `ui/components/Loop.kt` lose or change small parts.
  No Rust change, no new dependency.
- Depends on `spaces-navigation` (this branch is cut from it); coordinate
  with `usage-gauge`, which lands after this change and builds its sheet on
  the popup layer provided here.

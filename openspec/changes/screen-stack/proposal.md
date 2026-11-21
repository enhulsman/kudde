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
coming back is still instant.

### Your questions (my pick first)

1. **What switching between these screens looks like.**
   *Pick: exactly as today, an instant cut, in this change.* The new
   structure makes a proper transition possible later, but how it should
   look (slide, fade, how fast) is a design pass you sign off, like the
   other pictures. Other option: (b) a plain short fade (about 150 ms) now,
   to be replaced by the designed one later. Why (a): a temporary animation
   is one more thing to judge twice, and the cut is what you see today, so
   any difference you notice on the phone comes from the fix, not from
   motion.
2. **The context gauge's detail in the chat (your point 10).**
   *Pick: it opens as a small window just under the title bar, over the
   chat, with the same words and look as the line today.* It no longer
   pushes the chat down and back up, and a tap anywhere else closes it
   without touching what is below. Other option: (b) leave it as a line in
   the chat until the `usage-gauge` change replaces it with its sheet.
   Why (a): it is the one open "dropdown" left that is not a real window,
   and your jump goes away now. `usage-gauge` later fills the same window
   with its sheet.
3. **A GitHub issue for this work.** *Pick: open one in the `v1.0.0`
   milestone, "One screen at a time inside an agent", linking this change.*
   The text is shown to you first (task 1.2). Other option: (b) no issue,
   tracked as part of the spaces work. Why (a): it is a separate piece of
   work with its own phone check.

Decided by me (tell me if you want one reversed): no new library (a small
list of screens written in the app's own style is enough; Google's
navigation library would add a dependency for four screens), the tab
overview's swipe between tabs (your point 7) is not in this change, and
nothing about the 400 ms wait changes.

### What changes for you on the phone

- With the keyboard up in the chat, opening Changes puts the keyboard down
  and it **stays down**; no chat cursor shows over Changes (point 13).
- A swipe on the tab overview, also where the title bar usually sits,
  **never reaches the terminal behind it**: no pane changes (point 9).
- While the title bar's menu or the gauge's detail is open, a tap or swipe
  anywhere outside it **only closes it**; nothing underneath reacts.
- The gauge's detail no longer makes the chat jump (point 10, if you pick
  1a above).
- Going back from Changes or the tab overview shows the terminal or chat
  you came from, as it was, at once: same scroll, same draft, same zoom.
- **Nothing else should look or behave differently yet.** Steps between
  panes, the 400 ms wait, the keyboard going down when you switch terminals
  (you said that is fine, point 14), Back, and what TalkBack reads stay as
  they are. If anything else feels different on the phone, that is a bug in
  this change.

Not in this change, but made easy by it: showing a terminal's last screen
instantly on a step even when the encrypted cache is empty (points 3, 11
and 12), and watching the other panes of the tab live without resizing the
desk.

### Before the restructure: the stuck test

One test of the title bar's menu takes about 13 minutes per case and then
runs out of memory, so the full test suite cannot finish. It is fixed first
(task 0), because the restructure leans on that suite, and because the new
gauge window is tested the same way as the menu.

## Why

Ezra's phone session of 2026-11-21 (his points 9, 10, 13 and 15) found a
family of bugs with one cause: inside an open agent, `TerminalRoute` draws
the terminal, the chat, Changes and the tab overview stacked in one `Box`
(`ui/terminal/TerminalRoute.kt:199-258`). Lower layers stay composed; only
their semantics are cleared. Their touch handlers still receive what the
top layer does not consume, their text fields keep focus, and every opener
must remember to hide the terminal's keyboard by hand. Each new overlay
repeats the risk. He asked for a structure in which this kind of bug cannot
happen again.

## What changes

- Inside an open agent the four screens become **destinations** of a small
  per-agent stack: a base (terminal or chat, chosen by the existing switch)
  and covers pushed over it (Changes, tab overview). Only the top
  destination is composed; Back pops.
- A per-agent **keeper** (`AgentKeeper`) holds what must outlive a
  destination: the terminal session and its attachment, the opener and its
  400 ms wait, the background reopen, the screen state (compose text, zoom,
  keyboard asked for), the terminal's renderer, the chat visit and switch,
  the last phone grid. It is created when the agent opens and released when
  it is left, as today.
- One **focus rule**: only the top destination may hold focus or the
  keyboard; a destination change clears focus and puts the keyboard down in
  one place, replacing the hand-written `input.hide()` calls.
- Menus and the gauge's detail open in a **modal popup** whose window
  covers the screen and takes every touch outside the panel.
- Removed with the stacking: `heldGrid`, the `clearAndSetSemantics`
  wrappers, `undrawnWhen` under the chat, the terminal's `covered` flag and
  the chat's `coversBelow`.
- Task 0: the Robolectric popup test that runs out of heap.

## Impact

- New capability `agent-screens` (behaviour deltas only; see specs).
- Code: `ui/terminal/TerminalRoute.kt` (becomes the host), new
  `ui/agent/AgentKeeper.kt`, `AgentStack.kt`, `ScreenStackHost.kt`,
  `ui/components/ModalPopup.kt`; `TitleBarMenu.kt`, `ChatScreen.kt`,
  `TerminalScreen.kt`, `ui/components/Loop.kt` lose or change small parts.
  No Rust change, no new dependency.
- Depends on `spaces-navigation` (this branch is cut from it); coordinate
  with `usage-gauge`, whichever lands second puts its gauge content into
  the popup of the other.

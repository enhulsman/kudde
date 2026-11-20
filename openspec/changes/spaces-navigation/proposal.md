# Proposal: the desk's order on the phone

## Plain summary

**Answered (Ezra, 2026-11-20):** 2 to 6 as picked. 1 after a short
brainstorm the same day: option (b), not the pick; the sideways walk stays
inside the tab. Also answered that day: 7 (the map, no pane dots) and 8
(a quieter title bar, in this change). Decisions 9 to 11 (found in review
on 2026-11-20) answered the same day: 9 and 11 as picked, 10 the other
option. The texts wait for your approval in task 1.2, the pictures in task
4.2; task 1.1 records the answers.

**Decisions that are yours** (my pick first; every text on screen and every
new picture waits for your approval, see "Texts" and "New on screen"):

1. **What a sideways swipe on the title bar walks.**
   **Answered: (b), not the pick.** A sideways swipe walks only the panes
   of the open terminal's tab, in the desk's order, and stops at the tab's
   ends. At an end it opens nothing: the title bar bounces a little, with
   the end haptic. It does not wrap, and never goes into another tab,
   space or machine. Up and down stay as they are. Why (Ezra): going
   further than a few terminals is quicker through the Agents or Spaces
   view than by swiping, so a bounded walk makes sense. Accepted: in a tab
   of one pane a sideways swipe now only bounces, where today it opens the
   next agent of the Agents list.
   Offered: (a) the desk's order across tabs, spaces and machines (my
   pick, not chosen); (b) inside the tab, chosen; (c) today's walk by state.
2. **Where Spaces lives.**
   *Pick: a second view of the home screen*, chosen with a switch in the top
   bar (Agents / Spaces); the app opens on the one used last.
   Other options: (b) the drawer from the left edge, as the old spec says;
   (c) a bottom bar with two tabs.
   Why (a): on phones with gesture navigation a swipe from the left edge is
   Android's Back, so a drawer needs a button anyway, and the home screen
   has no bottom bar to add one tab to.
3. **What the Spaces view lists.**
   *Pick: every terminal as a row*, under its space and its tab, in the
   desk's order, with a small map of the tab's splits on the tab's line; a
   tap on that line opens the tab as tiles. This is #46 (the list grouped by
   space), so #46 closes with this change.
   Other options: (b) only spaces with their tab maps, a tap opening the
   tiles, as Herdr's app; (c) rows only, no tiles.
   Why (a): names are what you look for on a phone, and the rows are the
   ones the Agents view already has.
4. **No pinch for the tab overview.**
   *Pick: a tap on the tab's map in the title bar opens it.* The old spec
   gave it to a pinch on the terminal, but the pinch already zooms there.
   Other option: pinch in once the terminal is at its smallest.
5. **No swipe on the terminal itself.**
   *Pick: the title bar only.* The terminal already uses a drag to scroll
   and pan, a tap to click and a long press to select, and the chat scrolls
   wide tables sideways.
   Other option, later if the title bar is too far to reach: a two-finger
   sideways swipe on the terminal.
6. **Sub-agent peek stays out.**
   *Pick: its own small change later.* It needs a second live terminal in a
   sheet and has nothing to do with order. It stays specified in
   `android-client-v1`, and #27 stays open for it alone.
   Other option: build it here, after everything else.
7. **Map or pane dots in the title bar.**
   **Answered: the map.** The title bar keeps the tab's map (as in 3 and
   "New on screen" 4): the open terminal's cell marked, a tap opens the tab
   overview, no map in a tab of one pane. No pane dots, as Herdr's app
   has: map or dots, never both.
8. **A quieter title bar.**
   **Answered: in this change**, because it decides whether the map fits.
   Changes moves into a menu behind a three-dot button at the end of the
   title bar, where later options can go too. The Terminal and Chat
   switch shows an icon instead of a word; a screen reader still says
   "Terminal" or "Chat". The title bar keeps a place for the context
   gauge, whose look and sheet belong to the `usage-gauge` change (moved
   there the same day: the ring and the usage sheet are their own change,
   closing #37). Both are new pictures: they come in the render round
   (task 4.1) and wait for your sign-off (4.2). The icons and the menu's
   texts are not decided here.
9. **What TalkBack says when a step goes nowhere.** Answered by Ezra,
   2026-11-20: as picked (task 1.2).
   With TalkBack the haptic is not reliable feedback, and two cases are
   silent today: "Next/Previous agent that needs you" when nobody else
   needs you, and any of the four actions while the compose sheet holds
   text (the step is refused to keep your text).
   *Pick: two texts*, "No more agents need you" and "Not moved: the
   compose text is kept".
   Other option: one short text for both, "Not moved".
   The spec, design D9 and task 3.6 carry the pick.
10. **What gives way when the title bar is too narrow.** Answered by
    Ezra, 2026-11-20: not the pick; the gauge hides first and the map
    stays; after that the name shrinks to eight characters with "…" (task
    4.2, at the `terminal-map` render). At the largest font size (2x),
    or on a 360 dp phone, the chat's bar cannot hold the name, the map,
    the gauge, the switch and the menu button in full. The switch and the
    menu button always show.
    The gauge's value is one tap away in the usage sheet once
    `usage-gauge` lands. The pick was: the name shrinks first, then the
    map hides, the gauge always shows.
    The spec ("The title bar fits its controls"), design D10 and task 5.8
    carry the answer.
11. **What a screen reader says for the menu's Changes item.** Answered
    by Ezra, 2026-11-20: as picked (task 1.2). Today the Changes button is
    read as "Show the files this agent changed".
    *Pick: "Changes"*, the item's own word, as menu items read best; the
    longer description goes.
    Other option: keep "Show the files this agent changed" as the item's
    spoken name.
    The spec (`spaces` and the amended `changes-view` texts table) and
    design D10 carry the pick.

**What changes for you:**

- The home screen gets a switch: **Agents** (as today, by who needs you) and
  **Spaces** (your machines' spaces, tabs and terminals in the desk's
  order). Each tab has a small map of its splits; a terminal that needs you
  shows its card in its place.
- In a terminal, a sideways swipe on the title bar takes you to the next
  or previous pane of the same tab, not to the next agent of the list by
  state. At the tab's first or last pane it opens nothing: the title bar
  bounces and the phone gives the end tick. In a tab of one pane that is
  all it does. To reach another tab, space or machine, go through Spaces,
  or the tab's map for another tab of this space. Up and down still walk
  the agents that need you.
- When a tab has more than one pane, the title bar shows the tab's map with
  your pane marked. A tap on it shows the tab as tiles; a tap on a tile
  opens that pane.
- The title bar gets quieter: Changes moves into a menu behind three dots,
  and the Terminal and Chat switch becomes an icon. The chat's context
  gauge keeps its place; its new look comes with `usage-gauge`.
- Swiping past terminals, sideways or up and down, no longer resizes them
  on the desk: a terminal is taken over only once you stay on it for 0.4
  seconds, or at once when you type, tap or send text there.
- When the desk reorders, renames, adds or closes something, the phone
  follows within two seconds. The terminal you have open never changes
  under you.
- A machine out of reach keeps its spaces as last seen, marked "as of".
- With TalkBack, the title bar has the actions "Next terminal" and
  "Previous terminal" (inside the tab, like the swipe), and everything can
  be reached by taps.
- Nothing is ever changed on the desk: no renaming, moving or closing.

**Found wrong on the way** (details in
`research/spaces-navigation-2026-11.md`, section 1):

- Triage called #27 ready against the existing spec. It is not: the left
  edge is Android's Back, the pinch is already the zoom, and no signed-off
  render shows a drawer, a tile or a peek sheet.
- The existing spec does not contain your idea. It has no swipe in the
  desk's order and nothing that shows where the open terminal sits.
- Today's sideways swipe does not follow the desk at all. It follows the
  Agents list by state.

## Why

On the desk, terminals have a place: this space, that tab, left of that
pane. The phone shows them as one list by state, and its sideways swipe
walks that list. So the neighbour of the terminal you are in is unknown,
and a build pane or a shell beside an agent cannot be reached by swiping at
all. Herdr's own Android app shows the desk's structure (tabs as small
maps, panes as dots in the header, a swipe to the next pane), and kudde
should learn from it. kudde already reads the layout document, but keeps
only the spaces' names.

## What Changes

- `herdr-proto` reads the layout document in order: spaces, tabs, and each
  tab's panes with their place among the splits. It stays lenient and never
  writes.
- The hub keeps each machine's layout with its document version, follows
  `doc` events, drops late answers, keeps the last layout while a machine
  is not connected, and puts it in the snapshot.
- The home screen gains the Spaces view and a switch; the app remembers the
  view.
- A tab map (a small drawing of a tab's splits) on each tab's line and in
  the title bar.
- **BREAKING for habit, not for data:** the sideways swipe on the title bar
  walks the panes of the open tab in the desk's order instead of the list
  by state, and bounces at the tab's ends. Plain shells in a pane become
  stops. It never crosses into another tab, space or machine.
- A terminal reached by a step (sideways, up or down, or a screen-reader
  action) is attached only after 400 ms on screen, or at once on input; a compose text is cleared only when it was handed to
  an attached terminal.
- The title bar gets four screen-reader actions; a step, an end and a
  refused step are each announced.
- A quieter title bar: Changes goes into an overflow menu, the view switch
  becomes an icon with the same spoken names, and the title bar keeps a
  place for the context gauge, whose look and sheet belong to the
  `usage-gauge` change.
- A tab overview with tiles, opened from a tab's line and from the title
  bar's map.
- The `spaces` requirements for the drawer, lenient reading and the tab
  overview leave `android-client-v1` for this change, rewritten; the peek
  requirement stays there. The sideways sentence of `agent-list`, "Jump to
  the next agent that needs you", is amended in place to point here.
- `changes-view` and `chat-view` (in `openspec/specs/`) are modified: the
  Changes button becomes the menu's item, and the switch is no longer a
  labelled pill.

## Capabilities

### New Capabilities
- `spaces`: the desk's order on the phone: reading the layout in order,
  following it, the Spaces view, tab maps, the sideways walk and its rules,
  what happens when the desk changes or a machine is out of reach, the tab
  overview, the ways without a swipe, and the title bar's controls (the
  overflow menu, the view switch, and the place kept for the context
  gauge).

### Modified Capabilities
- `changes-view`: "A Changes button opens the agent's changes" is renamed
  "Changes in the title bar's menu opens the agent's changes" and rewritten
  for the menu; "The screen's texts are fixed" gets the menu item's row in
  place of the button's (its spoken name is "Changes", decision 11).
- `chat-view`: "The switch shows only while the chat can follow" describes
  the switch as an icon button with the words as its spoken names.
  `usage-gauge` also modifies `chat-view`, but another requirement ("A
  context gauge shows in the top bar"); the two deltas do not touch the
  same requirement, so either can archive first. `usage-gauge`'s proposal
  should say the same when it is next edited (it lives on its own branch).
- Not in `openspec/specs/`: two requirements of the open change
  `android-client-v1` are amended in place (task 1.1), as `session-names`
  and `changes-view` did before.

## Texts (approved by Ezra, 2026-11-20, task 1.2)

| Where | Text |
|---|---|
| Home switch | "Agents" · "Spaces" |
| Group of terminals in no pane | "Not in a space" |
| A space with unreadable tabs | "1 tab could not be read" · "%d tabs could not be read" |
| A machine whose document is not a layout | "Layout could not be read" |
| Spaces view, nothing to list: title | "No spaces yet" |
| Spaces view, nothing to list: body | "Spaces you make in the herdr app on your machines show up here, in the desk's order." |
| Placeholder tile | "Desk only" |
| Compose text sent while the terminal cannot be opened | "Not sent: the terminal is not connected yet" |
| Spoken: a space header | "website, 1 needs you, 2 working" (name, then each state that has agents, in the order needs you, done, working, idle) |
| Spoken: a tab map | "Tab main, 3 terminals, 1 needs you" (the last part only when someone needs you) |
| Spoken: the title bar's map | "Tab overview, terminal 2 of 3" |
| Spoken: the switch | "Agents, selected" · "Spaces, selected" |
| Screen-reader actions | "Next terminal" · "Previous terminal" · "Next agent that needs you" · "Previous agent that needs you" |
| Spoken after a step | "<name>, <place>" as the title bar shows them (for example "Pricing page copy, website / 2 · workstation") |
| Spoken at the tab's end | "No more terminals in this tab" |
| Spoken when no other agent needs you (an action up or down opened nothing) | "No more agents need you" |
| Spoken when a step is refused because the compose sheet holds text | "Not moved: the compose text is kept" |
| Title bar: the overflow button, spoken | "More options" |
| Title bar menu: its item, shown and spoken | "Changes" (spoken the same) |
| Title bar: the view switch, spoken | "Chat" (in the terminal) · "Terminal" (in the chat), the words shown today |

## New on screen (each needs the owner's sign-off against a render)

All of them use only colours and type that exist in `design-tokens.json`;
new icons follow the existing line icons. None has a render yet (task 4.1
makes them, 4.2 is the sign-off).

1. **The home switch.** The top bar's title becomes two words in the screen
   title's type, "Agents" and "Spaces"; the chosen one in the text colour,
   the other dim. While Spaces shows and an agent needs you, "Agents"
   carries the count in the needs-you colour, as a section header does.
2. **The tab map.** A drawing about 28 by 20 dp of the tab's panes as
   cells with a 1 dp border and 2 dp corners. A cell is filled in the
   needs-you colour when its agent needs you, in the done colour when it
   finished unseen, outlined in the working colour while it works, and
   outlined in the border colour otherwise. A placeholder cell is dashed. In
   the title bar the open terminal's cell is filled in the text colour.
3. **The Spaces list.** Machine name as a section header (when more than
   one machine); a space header (name 16 sp, the state mark at the right in
   the row's 18 dp slot); a tab line (map, then the tab's name in the
   secondary type); under it the existing needs-you card, agent row and
   other-terminal row.
4. **The title bar with the map**, as a 48 dp button, in the terminal and
   in the chat, before the overflow button. The chat's bar also holds the
   gauge (as today, text, in the place kept for it) and the view switch.
   The render shows it at 412 dp and 130% font size, and at 360 dp and 2x
   (the accessibility spec's largest size), where the gauge gives
   way first (decision 10).
5. **The tab overview.** A sub-screen with the back bar the Changes screen
   has, titled with the space's name; the space's tabs as a row of tab maps
   with names; the chosen tab's tiles in popover colour with the program
   mark, name and state mark.
6. **The overflow menu.** A 48 dp three-dot button, last in the title bar,
   and the menu it opens, holding "Changes".
7. **The view switch as an icon.** A 48 dp icon button in place of the
   "Terminal" / "Chat" pill.
8. **No new gauge here.** The title bar keeps a place for the context
   gauge; its ring and its sheet are `usage-gauge`'s pictures. The render
   here shows the gauge as today, as text, in that place, unless
   `usage-gauge`'s render is signed off first.
9. **The bounce at a tab's end.** New motion: the title bar's content
   moves about 12 dp in the swipe's direction and springs back in about
   200 ms; none with the system's animations off. The design brief says
   only the spinner moves, so the bounce gets its own token entry
   (`animations.titleBarBounce`) and a line in the brief's Motion section
   in task 4.1, signed off in 4.2 and judged on the phone at 7.4.

## Impact

- Rust: `crates/herdr-proto/src/layout.rs` (ordered reading, geometry),
  `crates/kudde-mobile/src/hub/` (layout per machine, versions, snapshot,
  the uniffi types).
- Android: a new `ui/spaces/` package; `ui/agents/AgentsHome.kt` (the
  walk), `AgentsRoute.kt` and `AgentsScreen.kt` (the switch),
  `ui/terminal/TerminalScreen.kt` and `TerminalRoute.kt` (map, actions,
  dwell, bounce, the overflow menu, the Changes button leaves),
  a new `ui/terminal/TitleBarStep.kt` (what a drag did),
  `ui/chat/ChatScreen.kt` (map in the shared bar, the icon switch, the
  gauge's place, the Changes button leaves); `ui/changes/ChangesRoute.kt`
  keeps the Changes screen, and `ChangesButton` there goes unused.
- Design: four renders under ids without numbers (`spaces-dark`,
  `spaces-light`, `terminal-map`, `tab-overview`; the title bar render
  also shows the menu and the icon switch, with the gauge as today in its
  place, and the bar at 360 dp and 2x), three new icons, seven component
  entries and `animations.titleBarBounce` in `design-tokens.json`, the
  updated `terminalTitleBar.parts`, the Changes button render
  (`13-changes-button`) marked superseded, checkpoints, goldens.
- Specs: `android-client-v1` amended in place (two requirements);
  `changes-view` (two requirements, one renamed) and `chat-view` (one
  requirement) modified by delta.
- Shares files with #28 (wide layout: `AgentsRoute.kt`, `TerminalRoute.kt`)
  and `desktop-chat` (the hub's chat code moves; this change does not touch
  it), and the chat's title bar with `usage-gauge` (the ring and the usage
  sheet). Build them one after the other.
- No herdr-core feature is needed; the tested range stays as it is.
- Not in this change: sub-agent peek, live pictures in tiles, renaming or
  closing anything, a swipe on the terminal itself, the wide layout, and
  showing which pane the desk has in focus (the document does not say).
  Until the peek change, a sub-agent's terminal that sits in a pane shows
  as an empty cell in Spaces; the Agents view lists what it lists today.
- Estimate: about 24 unattended builder runs, 6 done (2.1 to 2.4, 3.1,
  3.2) and 18 left (3.3 to 3.6, 4.1, 5.1 to 5.8 with three for the title
  bar and one for its fit, 6.1, 6.2, 7.1, 7.2, 7.7); the phone check 7.3;
  and the owner's sessions 1.1, 1.2, 4.2 (the design session), 7.4, 7.5
  and 7.6.
  Confidence: moderate for the Rust and the walk, low for the screens until
  the renders are signed off.

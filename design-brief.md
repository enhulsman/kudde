# Design brief: herdr for Android

Signed off by Ezra on 2026-11-01 (task 0.2). The binding values are in
`design-tokens.json`; this file explains them. The approved renders are the
reference for every screen: `design/renders/screens.html` (open it in a browser;
`?s=<id>` shows one screen) and the screenshots in `design/checkpoints/`.

## Concept: the same herdr, held in one hand

The phone app uses the Mac app's design system as it is: the same colours, the
same type and the same status language. Only the arrangement changes, for one
thumb. The list is ordered by who needs you, and the terminal is the real
terminal, drawn the way the desk draws it.

The mood is graphite chrome a shade darker than the terminal and quiet grey
text. Colour appears only where an agent has a state. There is no decoration:
the agents and their terminals are the content.

The design deliberately avoids:
- **Material You dynamic colour.** The app should look like herdr, not like the wallpaper.
- **A chat composer or message bubbles.** The terminal is shown as it is.
- **The early sketch's marketing-site colours.** It used a blue working state, amber for needs-you and green for done.
- **Approve/Deny buttons, or anything destructive, in v1.**

## Signature element: the needs-you queue

Agents that need you show as cards at the top of the home list, not as rows.
Each card has a 3 dp red left edge, the kind in red ("Permission", "Question")
and the agent's request in terminal type on the canvas colour. You can read the
request without opening the terminal.

Inside a terminal the same red edge continues as the queue strip under the
title, for example "1 more needs you · next: api tests, question". Tapping it
jumps to that agent.

Rules:
- Only blocked agents (blocked or error) get the red edge.
- The strip appears only when another agent needs you.
- The request text (`msg`) never goes into a notification.

## Colours

The values come from the herdr-app presets, run through the app's own
`resolve()`; none were invented.

| Role | graphite (dark) | paper (light) |
|---|---|---|
| Terminal background | `#17191d` | `#ffffff` |
| Chrome (bars, list) | `#101114` | `#f1f1ee` |
| Canvas (insets, beyond the grid) | `#131518` | `#fafaf9` |
| Popover (cards, sheets) | `#25272b` | `#ffffff` |
| Text | `#eeeeef` | `#1b1b1e` |
| Muted text | `#98999b` | `#69696a` |
| Dim text | `#696a6d` | `#969698` |
| Working | `#e5c07b` (spinner) | `#9a6700` |
| Needs you | `#f0705f` (pill, card edge) | `#c93c2c` |
| Done | `#76a8ff` (dot) | `#2f6fe4` |
| Idle | `#696a6d` (ring) | `#a8a8aa` |

Each theme also defines its 16 ANSI colours, selection and accent; the full set
is in the tokens. v1 ships graphite and paper. The other four Mac themes come
in v1.1.

## Type

- **UI: Geist**, at 400, 500 and 600. Screen title 22/600, row name 16, secondary text 13.
- **Terminal and the card's request text: JetBrains Mono**, with ligatures off, so that `===` stays three cells.
- **Terminal padding:** the grid sits inside the Mac pane's padding, 10 dp at the sides and 6 dp at the top, painted in the terminal background (herdr-app `terminal_padding`). Claude Code starts at column 0; the margin is the terminal app's.
- **Terminal size while typing:** 12.2 sp, which gives about 53 columns on a 412 dp phone. With the keyboard hidden the grid grows to the full height; the keys row stays.
- **Terminal size while watching:** the desk's grid is scaled to the phone's width inside the padding. The 96-column example comes out at 6.8 sp.
- Both fonts are OFL and bundled in the app.

## Screens (the approved renders)

| Render | Shows |
|---|---|
| `1-home-dark`, `2-home-light` | Agent list in the order Needs you, Done, Working, Idle, then "Other terminals" (collapsed); an unreachable-machine line |
| `3-terminal-watch` | Watching at desk size: the grid fitted to the width (since 2026-11-03 the area below is terminal background, no dashed line), and the "Watching … tap to type" chip |
| `4-terminal-type` | Typing at phone size: size tag, keys row (`esc tab ctrl alt ← ↓ ↑ →` and compose), ctrl latched |
| `4b-terminal-type-hidden` | Typing with the keyboard hidden: the phone grid at full height, keys row kept |
| `5-compose` | Compose sheet; Paste sends no Enter |
| `6-notification` | Lock screen: "Needs you" (loud) and "Finished" (quiet); agent, kind and machine only |
| `7-machines` | Machine cards: alert status, mute, test alert, untested-version warning, unreachable machine |
| `8-host-key` | First connection: trust the host key by fingerprint |
| `9-new-spaces` | "New terminal" sheet (start-agents, signed off 2026-11-04): machine chips, spaces, where it starts, Terminal and the installed agents |
| `10-new-folders` | The sheet on a machine without spaces: folders, "as a new space", the agent row held while the check runs |
| `11-new-error` | A start the core refused: the reason above Start; agents could not be checked, Terminal only |
| `12-new-off` | Nothing connected: the + dimmed, a tap says "No machine connected" |
| `13-changes-button` | **Superseded by `terminal-map`** (spaces-navigation): the Changes button before the switch in the title bar (changes-view, signed off 2026-11-16) |
| `14-changes-list-dark`, `15-changes-list-light` | The Changes list: file, folder, status word, lines added and removed |
| `16-changes-diff-dark`, `17-changes-diff-light` | A diff with wrapped lines (↪) |
| `18-changes-long` | A long diff: "Show the whole diff (N lines)" |
| `19-changes-none` | "No changes since the last commit" |
| `20-changes-norepo` | "Not a git repository" |
| `21-changes-error` | "Could not read the changes", git's first error line, Retry |
| `22-changes-kinds` | Conflict, mode, new, repository rows and the 1,000-file cap |
| `23-changes-notes` | The texts for the other cases, as a sheet of examples |
| `spaces-dark`, `spaces-light` | The Spaces view (spaces-navigation; awaiting sign-off, task 4.2): the home switch with the Agents count, two machines, a space with a needs-you card, a tab of three panes and a placeholder, "Not in a space", an unreachable machine's rows marked "as of" |
| `terminal-map` | The quieter title bar (awaiting sign-off, task 4.2), graphite and paper: the tab map, the gauge as today ("47%", "283k"), the view switch as an icon, the menu button and its open menu with "Changes"; at 412 dp and 130% font, and at 360 dp and 2x with the gauge hidden and the map kept; the three proposed icons |
| `tab-overview` | The tab overview (awaiting sign-off, task 4.2): the space's tabs as maps, the chosen tab's tiles with a "Desk only" placeholder, and the row fallback for six panes side by side |
| `question-card-paper`, `question-card-graphite` | A question from the agent in the chat (chat-questions, signed off 2026-11-25): one raised panel with flat option rows, numbers in keycaps, a Preview pill and Skip in the footer (no Something else on a question with previews, owner 2026-11-25); the message box hidden while the card waits |
| `question-multi-typed-paper`, `question-multi-typed-graphite` | Multi-select: tick boxes, a typed answer shown as a ticked row, "3 selected", Skip and the one send arrow |
| `question-other-paper` | The Something else field open on a single-choice card, with its own send |
| `question-large-text-paper` | The card at a large font scale |

## First-run screens (signed off 2026-11-25, look in `first-run-walkthrough` D14)

Each ID shows graphite and paper. Open `design/renders/screens.html?only=<id>`;
existing `?s=<id>` links still work. The look: a four-segment step bar (3 dp, the current segment wider) in place of the step text, a 27 sp heading, two stacked choice cards (the first filled, the second plain, no second colour), a live checklist for the password way ("Signing in as dev", "Adding the key", "Signing out") with errors on the step that failed, and a tick disc in a `successHalo` ring on step 3 ("Your first machine is set up." for the first machine only). These screens follow
`openspec/changes/first-run-walkthrough/design.md`, including D13's fourth step.

| Render | Shows |
|---|---|
| `first-run-welcome` | Existing ear-tag mark, WL1–WL7 and WL13, four steps, setup action and Setup guide; no skip |
| `first-run-lock` | Lock notice before step 1, plus its home-notice variant after completion; no bypass |
| `first-run-address` | Step 1: Address, User, Port, optional Name; waiting and F1 no-answer variants |
| `first-run-manual` | Step 2 manual detail: exact D3 design command in a scrollable MonoBox, Copy/Share, restriction notes, both choices and Compare the fingerprint |
| `first-run-key-refused` | Step 2 rejection: K1, both choice cards (password-once first), retry and Change the address |
| `first-run-connected` | Step 3: the success disc, machine, core version, agent count and Continue; the first-machine line and the later-machine variant |
| `first-run-alerts` | Step 4 after Turn on alerts: AL6 test received, plus AL7 no-test variant; both retain Show my agents |
| `first-run-trust` | Existing trust-dialog look with D1/D2 manual wording, fingerprint and unchanged trust actions |
| `first-run-home` | Unfinished setup notice, Continue setup and New terminal; WL11/WL12 empty-home variant after completion |
| `first-run-hint` | The title bar hint over a terminal and a chat, one-pane variant, TalkBack variant (G1 to G6) |
| `first-run-key-choices` | Step 2: the two stacked choice cards, normal and large text |
| `first-run-password-trust` | Trust dialog for the password way (R3 to R5) before any field |
| `first-run-password` | The password field, the working checklist and each outcome (R9 to R16) on the step that failed |
| `machines-key-choices` | A rejected-key machine card with both choice cards; listed, imported and previously connected |

The D3 command keeps the spec's abbreviated example public key. Its complete
text is scrollable at the existing MonoBox type size; it is not reduced to fit.

## Components

Each component below has its full spec in `design-tokens.json` under `components`:
- agent row
- needs-you card
- section header
- machine notice
- terminal title bar
- queue strip
- watching chip
- size tag
- keys row
- compose sheet
- machine card
- host-key dialog
- notification
- button
- toggle
- home switch (`homeSwitch`): "Agents" and "Spaces" as the top bar's title
- tab map (`tabMap`): a tab's splits as cells, by state; the open terminal's cell marked in the title bar
- space header (`spaceHeader`): the space's name and its most urgent state mark
- tab line (`tabLine`): a tab's map and name; a tap opens the tab overview
- pane tile (`paneTile`): the tab overview's tiles, "Desk only" for a placeholder
- title bar menu (`titleBarMenu`): the three-dot button, last in the title bar, and its menu holding "Changes"
- view switch icon (`viewSwitchIcon`): the chat and terminal icons in place of the worded switch

The three new icons (the menu's three dots, the chat and the terminal) are
proposed from lucide (ISC), in the stroke and caps of the set in
`design/renders/icons/`; they wait for sign-off with `terminal-map`.

## Motion

Nothing moves in an orchestrated way. The only motion is the working spinner,
which turns every 900 ms as on the Mac. When an agent moves between sections,
its row slides into place over 200 ms. At a tab's end, a sideways swipe on the
title bar bounces the bar's content about 12 dp in the swipe's direction and
springs it back in about 200 ms, with the end haptic (`animations.titleBarBounce`).

With reduced motion on, the spinner stays still as a quarter arc, rows move
without sliding, and the title bar does not bounce (the haptic stays).

## Accessibility

- The target is WCAG 2.2 AA, with touch targets of at least 44 dp and all text in sp. Layouts must hold at 130% font scale.
- No state relies on colour alone: each one also has a mark (spinner, pill, dot or ring) and a section header in words.
- **Known exception, kept on purpose: dim text does not reach 4.5:1.** It is used for subtitles, times and counts. In graphite it reaches 3.49 on chrome and 2.77 on a card. In paper it reaches 2.61.
- **Also kept:** in paper, the status colours used as section headers are slightly under 4.5:1 (4.1 to 4.45).

## Follow-up: contrast

Ezra's call (2026-11-01): ship the Mac values first and judge them on the
phone, outdoors, at the M1 phone check (task M1.5).

If they turn out too faint:
- **Dim text:** lighten it for text only, to graphite `#8d8e90` and paper `#6d6d70`; dim stays for counts and rings. The cost is that subtitles come close to the muted grey.
- **Paper status headers:** shift them 1-3% toward the text colour: `#956401`, `#c73c2c`, `#2e69d6`.

All of these replacements pass 4.5:1. They are recorded in `design-tokens.json`
under `accessibility.followUp`.

## Implementation notes

- **Tokens are law.** The Compose theme is generated from `design-tokens.json`; colours are never hard-coded on a screen.
- **Screens are checked against the renders.** Roborazzi goldens are recorded once the first screens exist, compared against these renders, and signed off at the M1 phone check.
- **The icons** are herdr-app's (Apache-2.0), copied to `design/renders/icons/`; keep the license notice.
- **The renders use invented agent and machine names only.** Never put real project or client names in screenshots or goldens.

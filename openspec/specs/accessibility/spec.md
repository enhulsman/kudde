# accessibility Specification

## Purpose
TBD - created by archiving change accessibility-basics. Update Purpose after archive.
## Requirements
### Requirement: The terminal screen fits the largest font size
At every system font size up to 2x, the terminal screen's title bar SHALL
show both its lines (the agent's name and its place) without clipping, and
each key of the keys row SHALL show its whole label; a label MAY shrink
to fit its key.

#### Scenario: Title bar at 2x
- **WHEN** the system font size is 2x and Ezra opens a terminal
- **THEN** the title bar shows the name and the place line in full height, and the terminal starts below it

#### Scenario: Keys row at 2x
- **WHEN** the system font size is 2x and the keys row shows
- **THEN** every key shows its whole label ("ctrl", not "ctr")

### Requirement: Every key has a spoken name
Each key of the keys row SHALL have a name a screen reader reads. The
symbol keys SHALL be read as "dash", "slash", "tilde", "backslash" and
"pipe". A key with a spoken name SHALL NOT also have its label read.

#### Scenario: A symbol key with TalkBack
- **WHEN** TalkBack lands on the "~" key
- **THEN** it reads "Tilde", not "Tilde, tilde"

### Requirement: A Needs you card is read once
A screen reader SHALL hear a Needs you card's kind and request once: as
the card's state, not again from the ask box under the name.

#### Scenario: A permission card with TalkBack
- **WHEN** TalkBack lands on the card of an agent waiting for a permission
- **THEN** it reads "Permission" (and the request, if any) once, with the agent's name, place and age

### Requirement: A screen reader can read the terminal
While a screen reader is on, the terminal's accessibility node SHALL carry
the text of the visible grid as lines, with box-drawing characters,
block elements and braille spinner marks removed, runs of spaces folded to
one, and empty lines left out. The text SHALL be refreshed at most once a
second, and not at all while no screen reader is on.

#### Scenario: Reading a permission prompt
- **WHEN** TalkBack is on and lands on a terminal showing an agent's permission question inside a box
- **THEN** it reads the question and its choices as plain lines, without the box's lines

#### Scenario: No screen reader
- **WHEN** no screen reader is on
- **THEN** the terminal's node reads only "Terminal" and no text is gathered

### Requirement: The chat announces new answers
The chat SHALL announce once, in the order they arrive, each agent answer
row (kind "said") that arrives live while a screen reader is on and the
chat is open. Rows from the first read or a read-back SHALL NOT be announced,
nor the person's own messages, tool rows or notes.

#### Scenario: An answer arrives
- **WHEN** TalkBack is on, the chat is open, and the agent writes an answer
- **THEN** TalkBack reads the answer once

#### Scenario: Opening a chat
- **WHEN** TalkBack is on and Ezra opens a chat with earlier answers
- **THEN** nothing is announced until a new answer arrives

### Requirement: A question card is read in order
When a question card shows while a screen reader is on, it SHALL announce
once, politely, "Question from the agent" and the question's text; focus
SHALL NOT move by itself. Reading order SHALL be: the question text, then the
options in the log's order, each as "Option N of M", its label, then its
description (in one stop, with "Preview available" when it has a preview, and
"checked" or "not checked" for a multi-select option), then "Something else",
then "Skip" ("Skip question" in a call with a review step). Every action SHALL be a button reachable without the terminal. While the
card shows the message box is not shown (chat-view), so the card is the
last thing read; the reading order above is unchanged by the card's look.
A step the person takes (previous, next, an answer, a change) SHALL move
the focus at once to what it shows: the new question, read with its step as
one stop ("Question 2 of 3. Which login should it have?"); the review step's
first line; or, after a change, the answer just changed. It is not
announced besides (Ezra's try, 2026-11-25: the focus jumped to the title
bar's Back). The card's arrival SHALL NOT move the focus. The step
line's previous and next controls SHALL be buttons read as "Previous
question" and "Next question". On the review step each answer SHALL be one
stop read as its question, its answer (and its note), then its change
control as a button ("Change the answer to: <question>"), in the call's
order, before "Send answers" and "Skip all". "Skip question" and "Skip all"
SHALL each be one stop read as its text, which says on its own what it does;
"Skip question" moves the focus as an answer does. "Typed N of M answers, then
stopped. Check the terminal." SHALL be announced once when it appears. The text the card
shows when it could not send ("Already answered at the desk. Nothing was
sent.", "The question changed. Nothing was sent.", "Could not confirm the
answer arrived. Check the terminal.", "Not connected. Nothing was sent.")
SHALL be announced once when it appears. The collapsed line SHALL read like its text.
After an answer the card sent arrives, the focus SHALL land on the collapsed
line once it shows (Ezra's third try, 2026-11-25: the focus went back to
the title bar's Back); a card answered at the desk moves no focus.
The preview sheet SHALL read its title, the sample, the note field as "Note
(optional)" and then "Choose this" as a button. On a question with previews
the card has no "Something else" stop.

#### Scenario: TalkBack reads a card
- **WHEN** a card with two options, "Something else" and "Skip" appears with TalkBack on
- **THEN** it announces "Question from the agent" and the question once, and swiping reads "Option 1 of 2, SQLite (Recommended), One file, no server.", then Option 2, "Something else", "Skip"

#### Scenario: A refusal is heard
- **WHEN** a tap sends nothing because the status is no longer blocked
- **THEN** TalkBack announces "Already answered at the desk. Nothing was sent." once

#### Scenario: Choosing with TalkBack
- **WHEN** TalkBack's double tap activates "Option 2 of 2"
- **THEN** the answer is sent as for a tap, and the focus stays in the chat

#### Scenario: The answer list is heard
- **WHEN** the review step of two questions shows with TalkBack on
- **THEN** it announces "Send answers" once, and swiping reads "Which database should the todo app use?, Postgres", "Change the answer to: Which database should the todo app use?", the second answer and its change control, then "Send answers" and "Skip all"


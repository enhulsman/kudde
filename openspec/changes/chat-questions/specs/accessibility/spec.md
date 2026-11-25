## ADDED Requirements

### Requirement: A question card is read in order
When a question card shows while a screen reader is on, it SHALL announce
once, politely, "Question from the agent" and the question's text; focus
SHALL NOT move by itself. Reading order SHALL be: the question text, then the
options in the log's order, each as "Option N of M", its label, then its
description (in one stop, with "Preview available" when it has a preview, and
"checked" or "not checked" for a multi-select option), then "Something else",
then "Skip". Every action SHALL be a button reachable without the terminal. While the
card shows the message box is not shown (chat-view), so the card is the
last thing read; the reading order above is unchanged by the card's look.
A step change ("Question 2 of 3") SHALL be announced once; the review step
SHALL be announced once as "Send answers". The text the card
shows when it could not send ("Already answered at the desk. Nothing was
sent.", "The question changed. Nothing was sent.", "Could not confirm the
answer arrived. Check the terminal.", "Not connected. Nothing was sent.")
SHALL be announced once when it appears. The collapsed line SHALL read like its text.
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

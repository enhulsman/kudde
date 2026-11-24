# spaces Specification

## Purpose
TBD - created by archiving change spaces-navigation. Update Purpose after archive.
## Requirements
### Requirement: The desk's layout is read in its order
The app SHALL read each connected machine's `herdr-app` document and SHALL
keep its order: spaces as the document lists them, each space's tabs as
listed, and each tab's panes depth first, a container's children in the
order listed (left to right in a row, top to bottom in a column). A stack
SHALL be read as a column. A pane's place in its tab SHALL be worked out
from the splits: children of a row share its width and children of a column
its height in proportion to their weights. A child's weight SHALL be its
`size.weight` when that is a number that is finite and greater than zero,
limited to the range 0.01 to 1000, and SHALL be 1 otherwise (no size, a
pixel size, zero, a negative number, a number that is not finite, or a
value that is not a number). Every pane's rectangle SHALL be finite, SHALL
lie inside the tab's area and SHALL have no negative side.

Reading SHALL be lenient. A node of an unknown type SHALL be walked for
children; an unknown node without children and a mod pane SHALL be
placeholders. A tab whose root is not a node, that is nested deeper than 64
levels, or that holds more than 64 panes SHALL be left out and counted as
unreadable; the other tabs SHALL still show. Terminal addresses SHALL be
normalised, so `t7` and `<machine>/t7` name the same terminal. A terminal
that sits in two panes SHALL count once, at its first pane. A pane whose
terminal is not live or is a sub-agent SHALL be vacant. A space without an
id SHALL be left out; tab and space ids SHALL be made unique within their
machine (an empty or repeated id gets its position added), so two tabs
never count as one. A tab without a
name SHALL be called by its position in the space ("2"); a space without a
name SHALL be called after its folder. The app SHALL NOT write the
`herdr-app` document.

#### Scenario: Three panes in two columns
- **GIVEN** a tab whose root is a row of the terminal `t1` and a column of `t2` over `t3`
- **WHEN** the layout is read
- **THEN** the tab's order is `t1`, `t2`, `t3`; `t1` takes the left half at full height, `t2` the top right quarter and `t3` the bottom right quarter

#### Scenario: Weights
- **GIVEN** a row of two panes with weights 2 and 1
- **WHEN** the layout is read
- **THEN** the first pane takes two thirds of the width and the second one third

#### Scenario: Weights that cannot be used
- **GIVEN** a row of three panes whose weights are 0, -3 and the text "wide"
- **WHEN** the layout is read
- **THEN** each pane takes one third of the width

#### Scenario: All weights zero
- **GIVEN** a column of two panes, both with weight 0
- **WHEN** the layout is read
- **THEN** each pane takes half of the height, and no rectangle has a side that is not a number

#### Scenario: A huge weight
- **GIVEN** a row of two panes with weights 1e300 and 1
- **WHEN** the layout is read
- **THEN** the first weight counts as 1000, the first pane takes 1000/1001 of the width, and both rectangles lie inside the tab's area

#### Scenario: A qualified address
- **GIVEN** a pane that names its terminal `workstation/t7`
- **WHEN** the layout is read on the machine workstation
- **THEN** the pane is the terminal `t7`

#### Scenario: A mod pane and an unknown node
- **GIVEN** a tab that holds a terminal, a mod pane and a node of an unknown type without children
- **WHEN** the layout is read
- **THEN** the tab has one terminal and two placeholders, each in its place among the splits

#### Scenario: An unknown container
- **GIVEN** a node of an unknown type with two terminal panes as children
- **WHEN** the layout is read
- **THEN** both terminals are in the tab, in the order listed, sharing the node's area as a row would

#### Scenario: A malformed tab
- **GIVEN** a space with three tabs, the second with a root that is not a node
- **WHEN** the layout is read
- **THEN** the first and third tabs show, called by their names or else "1" and "3", and the space counts one unreadable tab

#### Scenario: The same terminal twice
- **GIVEN** a document that places `t4` in a pane of tab 1 and again in a pane of tab 2
- **WHEN** the layout is read
- **THEN** `t4` is a stop in tab 1 only, and its pane in tab 2 is vacant

#### Scenario: A sub-agent in a pane
- **GIVEN** a pane that names the live terminal `t9`, whose record has `parent` = `t3`, never reported status and has no status now
- **WHEN** the layout is read
- **THEN** the pane is vacant, and `t9` is not listed under "Not in a space" either

#### Scenario: A terminal that has ended
- **GIVEN** a pane that names a terminal that is no longer live
- **WHEN** the layout is read
- **THEN** the pane is vacant

#### Scenario: Two tabs with the same id
- **GIVEN** a space whose two tabs both carry the id `tb1`
- **WHEN** the layout is read
- **THEN** both tabs show, each with its own panes, under different ids

#### Scenario: No names
- **GIVEN** a space with an empty name and the folder `~/src/website`, whose second tab has no name
- **WHEN** the layout is read
- **THEN** the space is called "website" and the tab "2"

#### Scenario: Nothing is written
- **WHEN** the app reads, follows and shows a machine's layout for a whole session
- **THEN** it made no `doc.set` call

### Requirement: The phone follows the desk while it is open
The app SHALL follow the layout through `doc` events and SHALL read it again
after an event, after `missed` and after a reconnect. A change on the desk
SHALL show on the phone within two seconds of the phone receiving the
event. An answer older than the layout already shown (a lower document
version) SHALL be dropped. A document that cannot be read as a layout SHALL
leave the machine without spaces and with the note "Layout could not be
read".

#### Scenario: A tab is renamed on the desk
- **WHEN** a tab is renamed in the herdr app while the Spaces view shows
- **THEN** the view shows the new name within two seconds of the event

#### Scenario: Two panes are swapped on the desk
- **GIVEN** the Spaces view shows a tab with the terminals A then B
- **WHEN** the desk swaps the two panes
- **THEN** the view shows B then A within two seconds, and the tab's map shows the two in their new places

#### Scenario: Two changes in quick succession
- **GIVEN** the desk writes version 8 and then version 9 of the layout
- **WHEN** the answer for version 8 arrives after the answer for version 9
- **THEN** the phone keeps showing version 9

#### Scenario: Back after a gap
- **WHEN** the connection to a machine drops, the desk moves a tab to another space, and the phone reconnects
- **THEN** the phone reads the layout again and shows the tab in its new space

#### Scenario: A document that is not a layout
- **WHEN** a machine's `herdr-app` document turns into something that cannot be read
- **THEN** its terminals show under "Not in a space" with the note "Layout could not be read", and other machines are unchanged

### Requirement: A Spaces view of the home screen
The home screen SHALL offer two views, Agents and Spaces, chosen with a
switch in its top bar; the app SHALL open on the view used last. The Agents
view is the list by state (capability agent-list) and SHALL NOT change.

The Spaces view SHALL list, in the phone's own order of machines, each
machine's spaces in desk order; under a space its tabs in desk order, each
with a tab line (the tab's map and name); under a tab line its terminals in
desk order. A terminal SHALL show as it does in the Agents view: the
needs-you card, the agent row, or the row of a terminal without status. A
machine's name SHALL head its spaces when more than one machine is listed.
A space's header SHALL show its name and the most urgent state among its
terminals, in the order needs you, finished and unseen, working, idle; a
space with no agent SHALL show no state.

Live terminals that sit in no pane SHALL follow the machine's spaces under
"Not in a space", in the core's order; a machine without a layout SHALL list
every terminal there. Sub-agents SHALL NOT be listed in the Spaces view,
in a pane or outside one, with or without status; the Agents view SHALL
keep listing the terminals it lists today. A placeholder and a vacant pane
SHALL show in their tab's map and SHALL have no row. A
space that counts unreadable tabs SHALL say how many. A tap on a row SHALL
open that terminal; Back from it SHALL return to the Spaces view at the
place it was left. Back on the Spaces view SHALL leave the app, as on the
Agents view.

#### Scenario: Two spaces on one machine
- **GIVEN** workstation's layout lists the spaces website (tabs "main" with agents A and B, and "2" with a shell) and api (one tab with agent C)
- **WHEN** the person switches to Spaces
- **THEN** the view shows website, its tab line "main" with A then B, its tab line "2" with the shell, then api with its tab line and C, and no machine name above them

#### Scenario: The list by state is unchanged
- **GIVEN** agent C needs the person and sits in the last space
- **WHEN** the person looks at Agents and then at Spaces
- **THEN** Agents shows C first under Needs you, and Spaces shows C's needs-you card in its place in the last space

#### Scenario: A space's state
- **GIVEN** a space with one working agent and one finished, unseen agent
- **WHEN** the Spaces view shows it
- **THEN** its header shows the finished mark

#### Scenario: A terminal outside every space
- **GIVEN** an agent started on the machine without the herdr app, in no pane
- **WHEN** the Spaces view shows that machine
- **THEN** the agent is listed under "Not in a space", after the machine's spaces

#### Scenario: A sub-agent
- **GIVEN** an agent whose record has a `parent`
- **WHEN** the Spaces view shows its machine
- **THEN** it has no row and its parent's row is unchanged

#### Scenario: A sub-agent's shell in a pane
- **GIVEN** a live terminal with a `parent` that never reported status and sits in a pane, which the Agents view lists under "Other terminals"
- **WHEN** the person looks at Spaces and then at Agents
- **THEN** Spaces shows its pane as a vacant cell in the tab's map and no row for it, and Agents still lists it under "Other terminals"

#### Scenario: Unreadable tabs are counted
- **GIVEN** a space in which one tab could not be read
- **WHEN** the Spaces view shows it
- **THEN** the space shows its other tabs and the line "1 tab could not be read"

#### Scenario: The view is remembered
- **WHEN** the person chooses Spaces, closes the app and opens it again
- **THEN** the home screen opens on Spaces

#### Scenario: Back from a terminal
- **GIVEN** the person scrolled the Spaces view down to the fourth space and opened a terminal there
- **WHEN** the person goes back
- **THEN** the Spaces view shows the fourth space where it was

#### Scenario: Nothing to list
- **GIVEN** machines are connected, none has a layout and none has a live terminal
- **WHEN** the person switches to Spaces
- **THEN** the view shows "No spaces yet"

### Requirement: The Spaces view stays put when the desk changes
The row at the top of the Spaces view SHALL stay where it is when the layout
or a status changes while the view shows, unless that row itself is gone;
rows SHALL move to their new places over 200 ms, or at once with reduced
motion. A status change SHALL NOT reorder the Spaces view.

#### Scenario: A space is added above
- **GIVEN** the person reads the third space, scrolled so its header is at the top
- **WHEN** the desk adds a space above it
- **THEN** the third space's header is still at the top of the screen

#### Scenario: An agent starts needing the person
- **GIVEN** the Spaces view shows agent B as working, second in its tab
- **WHEN** B becomes blocked
- **THEN** B shows its needs-you card, still second in its tab

### Requirement: A machine that is not connected shows its last layout
While a machine is not connected, the Spaces view SHALL keep showing the
layout last read from it in this run of the app, its rows marked "as of" the
time it was last connected, as the Agents view marks them. A machine never
connected in this run SHALL show no spaces. A machine removed from the phone
SHALL take its spaces with it.

#### Scenario: A machine goes out of reach
- **GIVEN** the Spaces view shows server's two spaces
- **WHEN** server becomes unreachable
- **THEN** the two spaces stay, in the same order, each row marked "as of" the time server was last connected

#### Scenario: Not seen in this run
- **GIVEN** the app starts while server is unreachable
- **WHEN** the person opens Spaces
- **THEN** server has no spaces there, and the notice that server is unreachable shows as on the Agents view

#### Scenario: A machine is removed
- **WHEN** the person removes server from the phone
- **THEN** its spaces are gone from the Spaces view

### Requirement: A tab map draws a tab's splits
A tab map SHALL draw one cell for each pane of its tab, placed and sized as
the splits place it. A cell SHALL show its terminal's state: needs you,
finished and unseen, working, or none; a placeholder and a vacant pane SHALL
be drawn apart from terminal cells. A map SHALL have a spoken name that gives the tab's
name, how many terminals it holds and how many need the person. Wherever
this capability counts a tab's terminals (a map's spoken name, "terminal 2
of 3"), it SHALL count the tab's stops only: the terminal cells the phone
lists, in desk order. Placeholders and vacant panes are drawn but SHALL NOT
be counted.

#### Scenario: One pane beside two
- **GIVEN** a tab with one pane on the left and two stacked on the right
- **WHEN** its map is drawn
- **THEN** the map shows one tall cell on the left and two cells, one over the other, on the right

#### Scenario: A pane that needs the person
- **GIVEN** the tab "main" with three terminals, of which the second is blocked
- **WHEN** its map is drawn
- **THEN** the second cell is drawn as needing the person, and the map's spoken name is "Tab main, 3 terminals, 1 needs you"

#### Scenario: A single pane
- **GIVEN** a tab with one pane
- **WHEN** its map is drawn
- **THEN** the map is one cell

#### Scenario: A placeholder is drawn, not counted
- **GIVEN** the tab "main" with terminal A, a mod pane and terminal B, in that order
- **WHEN** its map is drawn
- **THEN** the map shows three cells, the middle one as a placeholder, and its spoken name is "Tab main, 2 terminals"

### Requirement: A sideways swipe on the title bar walks the open tab
From an open terminal or its chat, a sideways swipe on the title bar SHALL
open the next stop (finger moving left) or the previous stop (finger moving
right) of the open terminal's own tab, in desk order. Every live terminal
that is not a sub-agent and sits in a pane of that tab SHALL be a stop, with
or without agent status; placeholders, vacant panes and sub-agents SHALL NOT
be stops. The walk SHALL stay inside the tab: it SHALL NOT open a terminal
of another tab, another space or another machine, nor one under "Not in a
space". The order SHALL be taken from the layout as it is when the finger
lifts. The walk SHALL NOT wrap.

At either end of the tab, in a tab of one pane, and from an open terminal
that is in no tab (in no pane, a sub-agent, or of a machine that is not
connected), a sideways swipe SHALL open nothing, SHALL give the end haptic
and SHALL make the title bar bounce briefly in the swipe's direction. With
the system's animations off, the bounce SHALL be left out and the end
haptic SHALL stay. A step SHALL give a light haptic.

The swipe up and down on the title bar SHALL stay the walk through the
agents that need the person (capability agent-list), across tabs, spaces
and machines as today, and SHALL NOT bounce.

#### Scenario: The next pane
- **GIVEN** a tab with the terminals A, B and a shell, in that order, and A open
- **WHEN** the person swipes left on the title bar twice
- **THEN** B opens, then the shell, each with a light haptic

#### Scenario: The end of the tab
- **GIVEN** the open terminal is the last stop of its tab, and the space has another tab after it
- **WHEN** the person swipes left
- **THEN** nothing opens, the title bar bounces and the end haptic is given; no terminal of the next tab opens

#### Scenario: The start of the tab
- **GIVEN** the open terminal is the first stop of its tab, and the space has another tab before it
- **WHEN** the person swipes right
- **THEN** nothing opens, the title bar bounces and the end haptic is given

#### Scenario: A tab of one pane
- **GIVEN** the open terminal is alone in its tab, and other agents are listed in the Agents view
- **WHEN** the person swipes left or right on the title bar
- **THEN** nothing opens, the title bar bounces and the end haptic is given

#### Scenario: A placeholder is passed over
- **GIVEN** a tab with terminal A, a mod pane, and terminal B, with A open
- **WHEN** the person swipes left once
- **THEN** B opens

#### Scenario: A sub-agent is passed over
- **GIVEN** a tab with terminal A, a pane holding a sub-agent's terminal, and terminal B, with A open
- **WHEN** the person swipes left once
- **THEN** B opens

#### Scenario: From a sub-agent's terminal
- **GIVEN** the person opened a sub-agent's shell from "Other terminals" in the Agents view
- **WHEN** the person swipes left or right on the title bar
- **THEN** nothing opens, the title bar bounces and the end haptic is given

#### Scenario: From a terminal in no pane
- **GIVEN** the person opened an agent that the Spaces view lists under "Not in a space"
- **WHEN** the person swipes left or right on the title bar
- **THEN** nothing opens, the title bar bounces and the end haptic is given

#### Scenario: Opened from the list by state
- **GIVEN** the person opened agent C from the Agents view, where it stood first, and C is the first of three panes in its tab
- **WHEN** the person swipes left on the title bar
- **THEN** the second terminal of C's tab opens, not the second agent of the Agents view

#### Scenario: Animations off
- **GIVEN** the system's animations are off and the open terminal is the last stop of its tab
- **WHEN** the person swipes left
- **THEN** nothing opens and the end haptic is given, without a bounce

#### Scenario: Up and down are unchanged
- **GIVEN** three agents need the person, in different spaces
- **WHEN** the person swipes up on the title bar
- **THEN** the next agent that needs the person opens, as before this change, also when it sits in another tab or space

#### Scenario: The view chosen stays
- **GIVEN** the person chose Chat, and the open agent's tab holds next a shell without a conversation and then an agent with a readable log
- **WHEN** the person swipes left twice, past the shell to the agent
- **THEN** the shell shows its terminal and the agent after it shows its chat

### Requirement: The desk never moves the person
A change of the layout SHALL NOT open, close or switch a terminal on the
phone. When the open terminal's pane moves, also to another tab, the title
bar's map and the next and previous stops SHALL follow the new layout: the
stops are then those of the tab it sits in now. When the open terminal's
pane is closed on the desk while the terminal lives on, the terminal SHALL
stay open, the title bar SHALL show no map, the terminal SHALL count as one
under "Not in a space", and a sideways swipe SHALL open nothing, with the
bounce and the end haptic. When the terminal itself is gone,
the screen SHALL close as it does today.

#### Scenario: The open pane moves to another tab
- **GIVEN** terminal A is open, second of three in the tab "main"
- **WHEN** the desk moves A's pane to the tab "2", where it is alone
- **THEN** A stays open, the title bar shows no map, and a swipe left or right opens nothing, with the bounce and the end haptic

#### Scenario: Panes are reordered around the open one
- **GIVEN** a tab with A, B, C in that order and B open
- **WHEN** the desk reorders the tab to C, B, A, and the person then swipes left
- **THEN** A opens

#### Scenario: The pane is closed, the terminal lives on
- **GIVEN** terminal A is open
- **WHEN** the desk parks A, so it sits in no pane
- **THEN** A stays open without a map, and a swipe left or right opens nothing, with the bounce and the end haptic

#### Scenario: The layout changes during a swipe
- **GIVEN** the person's finger is down on the title bar, moving left, in a tab with A, B, C and A open
- **WHEN** the desk reorders the tab to A, C, B before the finger lifts
- **THEN** C opens, the next stop in the new layout

### Requirement: Steps start only on the title bar
A step SHALL come only from a drag that starts on the title bar, outside
the system's gesture areas, and whose longer direction passes the swipe
distance. The app SHALL NOT keep the system from taking a swipe at the
screen's edges (no gesture exclusion). A drag that starts on the terminal,
the chat, the keys row, the queue strip or a sheet SHALL never step,
whatever its direction. While text is being selected a step SHALL end the
selection. While the compose sheet holds text a step in any direction SHALL
be refused with the end haptic and without the bounce; the bounce means the
end of the tab only.

#### Scenario: A swipe from the screen's edge
- **GIVEN** the phone uses gesture navigation
- **WHEN** the person swipes in from the left edge at the height of the title bar
- **THEN** the system's Back happens and no step is taken

#### Scenario: Panning a zoomed terminal
- **GIVEN** the person watches a terminal at desk size, zoomed in
- **WHEN** the person drags sideways on the terminal
- **THEN** the terminal pans and no step is taken

#### Scenario: A wide table in the chat
- **GIVEN** the chat shows a table wider than the screen
- **WHEN** the person drags the table sideways
- **THEN** the table scrolls and no step is taken

#### Scenario: Scrolling the terminal
- **WHEN** the person flicks up or down on the terminal, with some sideways movement
- **THEN** the terminal scrolls and no step is taken

#### Scenario: Selecting text
- **GIVEN** the person long-pressed the terminal and is selecting text, in a tab with another pane after it
- **WHEN** the person swipes left on the title bar
- **THEN** the next stop opens and nothing is selected there

#### Scenario: A draft in the compose sheet
- **GIVEN** the compose sheet is open with text in it
- **WHEN** the person swipes sideways or up or down on the title bar
- **THEN** no step is taken, the end haptic is given, the title bar does not bounce, and the text is kept

#### Scenario: A short drag
- **WHEN** the person drags 20 dp on the title bar
- **THEN** no step is taken and no haptic is given

### Requirement: Passing through leaves the desk's sizes alone
A terminal reached by a step SHALL show its last screen at once and SHALL be
opened (attached, and at the phone's size where that applies) once it has
been on screen for 400 ms, counted from the step that showed it. A step here
is every step on the title bar: sideways inside the tab, and up or down
through the agents that need the person; and each of the four screen-reader
actions that open a terminal. A terminal left before 400 ms have passed
SHALL NOT be attached or resized. A terminal opened by a tap SHALL open at
once, as today.

#### Scenario: Three quick steps
- **GIVEN** the terminals A, B, C, D in one tab, in that order, and A open
- **WHEN** the person steps to B at 0 ms, to C at 300 ms and to D at 600 ms, and stays on D
- **THEN** D is opened at 1000 ms at the phone's size, and B and C were never attached and kept their desk size

#### Scenario: Three slow steps
- **GIVEN** the terminals A, B, C, D in one tab, in that order, and A open
- **WHEN** the person steps to B at 0 ms, to C at 450 ms and to D at 900 ms
- **THEN** B was opened at 400 ms and C at 850 ms, each is detached when it is left, and D is opened at 1300 ms

#### Scenario: Two quick steps up
- **GIVEN** the agents P, Q and R need the person, in different spaces, and P is open
- **WHEN** the person swipes up on the title bar at 0 ms, to Q, and again at 200 ms, to R, and stays on R
- **THEN** R is opened at 600 ms, and Q was never attached and kept its desk size

#### Scenario: A screen-reader step waits too
- **GIVEN** a screen reader is on and the open terminal has a next stop B in its tab
- **WHEN** the person chooses the action "Next terminal" at 0 ms and stays on B
- **THEN** B shows its last screen at once and is opened at 400 ms

#### Scenario: Left just before the wait ends
- **GIVEN** the person stepped to B at 0 ms
- **WHEN** the person steps on at 399 ms
- **THEN** B was never attached

#### Scenario: Still there when the wait ends
- **GIVEN** the person stepped to B at 0 ms
- **WHEN** 400 ms have passed and B is still on screen
- **THEN** B is opened at 400 ms, and a step at 401 ms leaves a terminal that was attached

#### Scenario: A tap is not delayed
- **WHEN** the person taps a row in the Spaces view
- **THEN** the terminal is opened at once

### Requirement: Input during the wait is never lost
Input to a terminal that is still in its wait SHALL end the wait: the
terminal SHALL be opened at once and the input SHALL then be delivered to
it once, in the order given. This applies to controls available on the
destination. A step to another terminal retires the previous terminal’s
keyboard and hides its keys row. Input here is a typed character, backspace, a
key of the keys row, the interrupt key, a tap or a drag on the terminal, a
tap on the chip above the keys, and sending the compose sheet's text. When
the terminal cannot be opened at that moment (no running connection, or the
phone's grid is not known yet), keys and taps SHALL do nothing, and the
compose sheet SHALL stay open with its text and the message "Not sent: the
terminal is not connected yet"; nothing SHALL report text as sent that was
not handed to an attached terminal. A terminal left after such input was
attached, and is detached on leaving as today.

#### Scenario: Typing during the wait
- **GIVEN** the person stepped to terminal B at 0 ms and B can receive keyboard input
- **WHEN** the person types "y" at 150 ms
- **THEN** B is opened at 150 ms and receives "y" once

#### Scenario: The previous keyboard closes on a step
- **GIVEN** terminal A has its keyboard and keys row open
- **WHEN** the person steps to terminal B
- **THEN** the old keyboard and keys row close, and any late input from A is discarded

#### Scenario: Two keys in a row
- **GIVEN** the person stepped to terminal B at 0 ms and can use B’s keys row
- **WHEN** the person taps esc at 100 ms and the down arrow at 120 ms
- **THEN** B is opened at 100 ms and receives esc and then the down arrow, each once

#### Scenario: A tap on the terminal
- **GIVEN** the person stepped to terminal B at 0 ms
- **WHEN** the person taps the terminal at 200 ms
- **THEN** B is opened at 200 ms, and the tap is not sent as a click

#### Scenario: Sending the compose text during the wait
- **GIVEN** the person stepped to terminal B at 0 ms and opened the compose sheet
- **WHEN** the person sends the text "run the tests" at 250 ms
- **THEN** B is opened at 250 ms, receives the text once, and the sheet closes

#### Scenario: The terminal cannot be opened
- **GIVEN** the person stepped to terminal B and no connection is running
- **WHEN** the person sends the compose text "run the tests"
- **THEN** the sheet stays open with "run the tests" in it, the message "Not sent: the terminal is not connected yet" shows, and the message about line breaks does not

#### Scenario: Input, then leaving
- **GIVEN** the person stepped to terminal B at 0 ms and typed "y" at 150 ms
- **WHEN** the person steps on at 300 ms
- **THEN** B received "y" once and is detached, and no later input reaches B

#### Scenario: Leaving without input
- **GIVEN** the person stepped to terminal B at 0 ms and touched nothing
- **WHEN** the person steps on at 200 ms
- **THEN** B was never attached and received nothing

### Requirement: The title bar shows where the terminal sits
The title bar of the terminal and of its chat SHALL show the tab's map, with
the open terminal's cell marked, while that terminal sits in a tab, a tab
of one pane included (then the map is one marked cell), and the title bar
has room for it ("The title bar fits its controls"). A tap on the map SHALL
open the tab overview, so the space's other tabs are always reachable from
an open terminal. For a terminal in no pane the title bar SHALL show no map.
Every cell of a map, a plain shell's included, SHALL stand out from the
title bar and from the Spaces view at a contrast ratio of at least 3:1 in
both themes, and the open terminal's cell SHALL be the only cell filled in
the text colour. The title bar SHALL
NOT show pane dots, beside the map or in its place.
The line under the name SHALL stay as capability agent-names sets it.

#### Scenario: Second of three
- **GIVEN** the open terminal is the second of three panes in its tab
- **WHEN** the title bar shows
- **THEN** it shows the tab's map with the second cell marked, spoken as "Tab overview, terminal 2 of 3", and no pane dots

#### Scenario: Counted over the stops
- **GIVEN** a tab with terminal A, a mod pane and terminal B, in that order, and B open
- **WHEN** the title bar shows
- **THEN** the map shows three cells with the third marked, and it is spoken as "Tab overview, terminal 2 of 2"

#### Scenario: A step moves the mark
- **GIVEN** the title bar shows the second cell marked
- **WHEN** the person swipes left to the third pane
- **THEN** the third cell is marked

#### Scenario: A single pane
- **GIVEN** the open terminal is alone in its tab, in a space with two tabs
- **WHEN** the title bar shows
- **THEN** it shows a map of one marked cell, spoken as "Tab overview, terminal 1 of 1"
- **AND** a tap on it opens the tab overview, from which the other tab's terminals open

#### Scenario: A plain shell's cell is easy to see
- **GIVEN** the open terminal is the second of three panes and the other two run plain shells, in the Graphite and in the Paper theme
- **WHEN** the title bar shows
- **THEN** the plain shells' cells stand out from the title bar at a contrast ratio of at least 3:1
- **AND** the open terminal's cell is the only one filled in the text colour

### Requirement: A tab overview shows the splits as tiles
The tab overview SHALL show one tab at a time: the space's name, the
space's tabs in desk order to choose from, and the chosen tab's panes as
tiles placed as the splits place them. A tile SHALL show its terminal's
mark, name and state, over a still picture of that terminal's screen, its
lines wrapped to the tile's width and its last lines shown, read once when the overview opens and when the tab shown
changes. The picture SHALL NOT update while the overview is open, and
reading it SHALL NOT mark the terminal seen on the desk. When the desk
gives no picture, the tile SHALL show as it does without one. A placeholder
tile and a vacant tile SHALL read "Desk only". A tap on a tile SHALL open its terminal; a placeholder or vacant
tile SHALL do nothing. When a
tile would be narrower than 96 dp or lower than 64 dp, the overview SHALL
list the tab's terminals as rows in desk order under the tab's map instead.
The overview SHALL open from a tab line in the Spaces view and from the
title bar's map; Back SHALL return to where it was opened. It SHALL follow
the desk like the Spaces view; when its space is gone it SHALL close. The
app SHALL NOT draw live terminals side by side at phone width.

#### Scenario: Three panes
- **GIVEN** a tab with one pane on the left and two stacked on the right
- **WHEN** the person opens its overview on a 412 dp wide phone
- **THEN** three tiles show in that arrangement, and a tap on one opens its terminal

#### Scenario: Another tab of the space
- **GIVEN** the overview shows the tab "main" of a space with three tabs
- **WHEN** the person taps the tab "2" in the overview
- **THEN** the tiles of "2" show, and no terminal was opened

#### Scenario: Too many panes for tiles
- **GIVEN** a tab with six panes side by side
- **WHEN** the person opens its overview on a 412 dp wide phone
- **THEN** the overview shows the tab's map and six rows in desk order

#### Scenario: A placeholder
- **GIVEN** a tab with a terminal and a mod pane
- **WHEN** the person opens its overview and taps the mod pane's tile
- **THEN** the tile reads "Desk only" and nothing opens

#### Scenario: The tab is closed on the desk
- **GIVEN** the overview shows the tab "2"
- **WHEN** the desk closes that tab
- **THEN** the overview shows the space's first tab

#### Scenario: The space is closed on the desk
- **GIVEN** the overview was opened from the Spaces view
- **WHEN** the desk closes its space
- **THEN** the overview closes and the Spaces view shows

#### Scenario: Tiles show their screens
- **GIVEN** a tab of two panes whose terminals show a build log and a shell prompt, neither opened on the phone
- **WHEN** the person opens its overview
- **THEN** each tile shows its own terminal's screen as a still picture under its mark, name and state
- **AND** neither terminal is marked seen on the desk

#### Scenario: A wide screen is re-flowed to the tile
- **GIVEN** a terminal whose screen is a wide build log
- **WHEN** the overview shows it in a narrow tile
- **THEN** the tile shows its last lines wrapped to the tile's width
- **AND** the tile has no empty space above them while lines remain

#### Scenario: A picture stays still
- **GIVEN** the overview is open and a terminal in it prints new lines
- **WHEN** the person waits
- **THEN** its tile's picture does not change until the overview is opened again or another tab is shown

#### Scenario: No picture from the desk
- **GIVEN** the desk gives no picture for a terminal
- **WHEN** the overview opens
- **THEN** that tile shows its mark, name and state as before, and the other tiles show their pictures

### Requirement: Every step has a way without a swipe
Every stop SHALL be reachable by taps alone: through the Spaces view, and
within a space through the title bar's map and the tab overview. A terminal
of another tab, which the sideways swipe does not reach, SHALL be reachable
the same way. With a screen reader on, the title bar SHALL offer the actions
"Next terminal", "Previous terminal", "Next agent that needs you" and
"Previous agent that needs you", which do what the four swipes do (the
first two inside the open tab, as the sideways swipe). After a move, the
app SHALL say once the new terminal's name, place and position in its tab
("zsh, website / build · workstation, terminal 2 of 2"; a terminal in no
tab has no position), and the title SHALL be spoken with that same text, so
a step between two panes of the same name is heard as a move and is not
read a second time when the screen reader's focus returns to the title.
When no move occurs, the app SHALL announce once: "No more terminals in
this tab" at the end of a tab, or "No more agents need you" at the end of
the attention queue. While Compose holds focus, its background
title-bar actions are unavailable. If a title-bar step action reaches the
app while the compose sheet holds text, it SHALL refuse the move and say
"Not moved: the compose text is kept" once. The switch, the tab lines, the
maps and the tiles SHALL be at least 48 dp high and wide to the
touch and SHALL have spoken names; the switch SHALL say which view is
chosen. All of it SHALL hold at the largest font size, with text that is
never clipped in height; a name in one line MAY end in an ellipsis where
the width runs out.

#### Scenario: By taps alone
- **GIVEN** the terminal A is open, in a tab of three in a space of two tabs
- **WHEN** the person taps the title bar's map, taps the other tab, and taps a tile
- **THEN** that terminal opens, and no swipe was needed

#### Scenario: A screen reader steps
- **GIVEN** a screen reader is on and the title bar has its focus
- **WHEN** the person chooses the action "Next terminal"
- **THEN** the next stop of the open tab opens and its name, place and position are heard once

#### Scenario: Two panes of the same name
- **GIVEN** a screen reader is on and both panes of a tab are shells named "zsh"
- **WHEN** the person chooses "Next terminal" from the first, and the screen reader's focus returns to the title
- **THEN** "zsh, website / build · workstation, terminal 2 of 2" is heard once, and the title reads the same text when focused

#### Scenario: A screen reader at the end
- **GIVEN** a screen reader is on and the last stop of its tab is open, with another tab after it
- **WHEN** the person chooses the action "Next terminal"
- **THEN** nothing opens and the app says "No more terminals in this tab"

#### Scenario: A screen reader at the end of the agents that need you
- **GIVEN** a screen reader is on and the open agent is the last that needs the person
- **WHEN** the person chooses the action "Next agent that needs you"
- **THEN** nothing opens and the app says "No more agents need you"

#### Scenario: A step action delivered while a draft waits is refused
- **GIVEN** a screen reader is on and the compose sheet holds text
- **WHEN** any of the four title-bar step actions is delivered to the app
- **THEN** nothing opens, the text is kept, and the app says "Not moved: the compose text is kept"

#### Scenario: A space is read once
- **GIVEN** a screen reader is on
- **WHEN** its focus lands on the header of the space website, which has one agent that needs the person and two working
- **THEN** it reads "website, 1 needs you, 2 working" once

#### Scenario: The largest font size
- **WHEN** the phone's font size is at its largest
- **THEN** the switch, a space header and a tab line with its map show their text uncut, and the title bar shows the name and the line under it in full height, the name ending in an ellipsis where the width runs out

### Requirement: The title bar keeps its options in one menu
The title bar of the terminal and of its chat SHALL end with an overflow
button (three dots), at least 48 dp to the touch, with the spoken name
"More options". A tap on it SHALL open a menu that holds "Changes"; choosing
it SHALL open the Changes view exactly as the Changes button did (capability
changes-view). The item's spoken name SHALL be its word, "Changes". The
title bar SHALL NOT show a separate Changes button. The
menu SHALL be the place for options the title bar gains later. Opening and
closing the menu SHALL NOT send anything to the terminal. A swipe that
starts on the overflow button SHALL be handled as a swipe on the title bar.

#### Scenario: Changes from the menu
- **GIVEN** a terminal is open
- **WHEN** the person taps the overflow button and then "Changes"
- **THEN** the Changes view opens for that agent's repository, as it did from the Changes button

#### Scenario: The same in the chat
- **GIVEN** the chat of an agent is open
- **WHEN** the person taps the overflow button
- **THEN** the menu holds "Changes"

#### Scenario: No Changes button
- **WHEN** the title bar of a terminal or a chat shows
- **THEN** it shows no Changes button outside the menu

#### Scenario: Closing the menu
- **GIVEN** the menu is open
- **WHEN** the person taps outside it or goes back
- **THEN** the menu closes, the terminal stays open, and nothing was sent to it

#### Scenario: Spoken name
- **GIVEN** a screen reader is on
- **WHEN** its focus lands on the overflow button
- **THEN** it reads "More options" and that it is a button

### Requirement: The view switch is an icon
The title bar's switch SHALL show an icon and no word. It is the button
that switches between an agent's terminal and its chat: in the terminal it
shows the icon of the chat, in the chat the icon of the terminal. It SHALL be at least 48 dp to
the touch, SHALL have the role of a button, and SHALL keep the words as its
spoken names: "Chat" in the terminal, "Terminal" in the chat. It SHALL show
and act as today in every other way: it shows only where the chat is
offered, and a tap switches the view. Buttons outside the title bar that
lead to the terminal ("Show the terminal") SHALL keep their words.

#### Scenario: From the terminal
- **GIVEN** a terminal whose agent has a readable conversation
- **WHEN** the title bar shows
- **THEN** the switch shows the chat's icon and no text, and a screen reader reads it as "Chat", a button

#### Scenario: From the chat
- **GIVEN** the chat of an agent is open
- **WHEN** the person taps the switch
- **THEN** the terminal shows, as it did from the "Terminal" button

#### Scenario: No chat offered
- **GIVEN** a terminal whose program has no conversation the app can read
- **WHEN** the title bar shows
- **THEN** there is no switch, as today

#### Scenario: Words outside the title bar
- **GIVEN** the chat shows "Show the terminal" under a message that it cannot show
- **WHEN** the person looks at it
- **THEN** it is still a button with those words

### Requirement: The title bar keeps a place for the context gauge
The chat's title bar SHALL keep a place for the context gauge among its
controls, as it has one today. The gauge's look and what a tap on it does
SHALL be as the `chat-view` capability describes them ("A context gauge
shows in the top bar"); they belong to the `usage-gauge` change, not to
this one. When nothing is known about the context, the place SHALL stay
empty and the other controls SHALL close up, as today. The terminal's
title bar SHALL show no gauge, as today.

#### Scenario: The gauge in its place
- **GIVEN** the chat of a Codex agent in a tab of three panes, with a token count in its log
- **WHEN** the title bar shows
- **THEN** the gauge shows whole beside the map, the view switch and the overflow button, as `chat-view` describes it, and a tap on it does what `chat-view` says

#### Scenario: No token count
- **GIVEN** a chat whose log has no token count yet
- **WHEN** the title bar shows
- **THEN** there is no gauge, and the map, the switch and the overflow button show with no empty slot where the gauge would be; their spacing is the signed-off render's

#### Scenario: The terminal
- **GIVEN** the terminal of an agent whose chat shows a gauge
- **WHEN** the terminal's title bar shows
- **THEN** there is no gauge

### Requirement: The title bar fits its controls
The title bar of the terminal and of the chat SHALL hold Back, the
program's mark, the name and the line under it, and then, each when shown,
the tab's map, the gauge (chat only), the view switch and, last, the
overflow button; the order among the map, the gauge and the switch is the
signed-off render's. From 360 dp wide and at every font size up to 2x the
controls SHALL NOT overlap and SHALL NOT be cut, and the name and the line
under it SHALL show in full height (capability accessibility, "The terminal
screen fits the largest font size"). Where the bar is too narrow to hold
all of them, the gauge SHALL hide first (the chat's bar only; its value
stays one tap away in the usage sheet once `usage-gauge` lands). Where it
is still too narrow, the name SHALL give way, ending in an ellipsis, down
to the width of eight of its characters, and, where even that does not
fit, further, never below its first character and the ellipsis. The tab's
map, the view switch and the overflow button SHALL NOT hide.

#### Scenario: Everything shown at 130%
- **GIVEN** a 412 dp wide phone at 130% font size, and the chat of an agent in a tab of three panes with a known context window
- **WHEN** the title bar shows
- **THEN** Back, the mark, the name (cut with an ellipsis if needed), the map, the gauge, the switch and the overflow button all show whole, none overlapping

#### Scenario: A narrow phone at the largest font size
- **GIVEN** a 360 dp wide phone at 2x font size, and the chat of an agent in a tab of three panes with a known context window
- **WHEN** the title bar shows
- **THEN** Back, the mark, the name, the map, the switch and the overflow button show whole and in full height, none overlapping; the gauge is hidden, and the name ends in an ellipsis once it is narrower than its full text


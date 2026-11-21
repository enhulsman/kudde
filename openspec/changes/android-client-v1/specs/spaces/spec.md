# spaces Specification (delta)

All requirements here belong to version 1.1 (M4).

## ADDED Requirements

The Spaces view, reading the desk's layout and the tab overview are specified
in the change `spaces-navigation`, capability `spaces`.

### Requirement: Peek at sub-agents without a pane
A terminal with child terminals that have no pane SHALL list them; tapping one
SHALL open it in a bottom sheet (half height, draggable to full) with the same
watch-only view.

#### Scenario: Sub-agent peek
- **WHEN** an agent has spawned a sub-agent without a pane
- **THEN** Ezra can open it in a sheet and close the sheet back to the parent

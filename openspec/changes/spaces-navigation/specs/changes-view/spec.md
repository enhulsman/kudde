## RENAMED Requirements

- FROM: `### Requirement: A Changes button opens the agent's changes`
- TO: `### Requirement: Changes in the title bar's menu opens the agent's changes`

## MODIFIED Requirements

### Requirement: Changes in the title bar's menu opens the agent's changes
The terminal screen's title bar SHALL offer Changes as an item of its
overflow menu (capability spaces, "The title bar keeps its options in one
menu"), in both the terminal and the chat view, for every terminal. The
title bar SHALL NOT show a separate Changes button. Choosing the item SHALL
open the Changes screen over the current view; back SHALL return to that
view as it was. The folder read SHALL be the folder of the terminal's
foreground process (the folder the title bar names), else the terminal's
own folder.

#### Scenario: From the terminal
- **WHEN** Ezra opens the title bar's menu in an agent's terminal view, chooses Changes, and then goes back
- **THEN** the Changes screen shows the agent's folder's changes, and back shows the terminal as it was

#### Scenario: From the chat
- **WHEN** Ezra opens the title bar's menu in an agent's chat, chooses Changes, and then goes back
- **THEN** back shows the chat at the scroll position it had, with its draft

### Requirement: The screen's texts are fixed
The Changes screen SHALL use exactly these texts:

| Where | Text |
|---|---|
| Title bar menu item, shown and spoken | Changes |
| Status words | changed, added, deleted, new, conflict, submodule, repository |
| Loading | Reading the changes… |
| Not a repository | Not a git repository |
| Nothing uncommitted | No changes since the last commit / Committed changes do not show here. |
| Failure | Could not read the changes / git's first error line / Retry |
| File cap | Showing the first 1,000 changed files |
| Binary | Binary file, not shown |
| Empty new file | Empty file |
| Mode only | Only the file mode changed (old to new, e.g. 100644 to 100755) |
| No newline at the end | No newline at end of file |
| Submodule | Submodule: old commit to new commit (short ids) |
| Long diff | Show the whole diff (N lines) |
| Too large | Too large to show on the phone |
| Reverted while open | No longer changed |
| Line continues | a hooked arrow (↪) at the start of each wrapped part |
| Carriage return | CR, in a small box at the line's end |
| Line cut at 4,000 characters | … (cut) at the cut |
| A row's description | path, status word, lines added and removed |

#### Scenario: Texts match the table
- **WHEN** each state of the screen is drawn in the screen tests
- **THEN** every text shown is one of the table's

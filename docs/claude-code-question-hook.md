# The Claude Code question hook

kudde answers Claude Code's multiple-choice questions (the `AskUserQuestion`
dialog) from the chat. Claude Code writes a waiting question to its session
log only once it has been answered, so kudde reads the question from a small
hook instead: Claude Code runs it just before it draws the dialog, and the
hook saves the question where kudde can read it over SSH.

Without the hook the chat still shows that a question is waiting: "The agent
asked a question in the terminal. To answer it here, add the question hook on
the desk.", with "Open the terminal", and it answers nothing.

## What the hook does

`contrib/claude-code/kudde-question-hook.sh` reads the call Claude Code hands
it and saves it, one file per session, as

    ${XDG_STATE_HOME:-~/.local/state}/kudde/questions/<session id>.json

The files are readable by you only, and a file older than a day is removed.
The hook writes only into a directory you own that nobody else can write,
and kudde reads no record that is a link or belongs to someone else.
The hook prints nothing and always exits 0, so it never changes what Claude
Code does. kudde still checks the terminal's screen before it sends any key:
a question the screen does not show is never answered from the phone.

## Install it

On the computer where Claude Code runs:

1. Copy the script somewhere on that machine and make it executable, for
   example:

       mkdir -p ~/.local/bin
       cp contrib/claude-code/kudde-question-hook.sh ~/.local/bin/
       chmod +x ~/.local/bin/kudde-question-hook.sh

2. Add it to your Claude Code settings (`~/.claude/settings.json`) as a
   `PreToolUse` hook for `AskUserQuestion`. If the file already has a
   `hooks` section, add the entry to its `PreToolUse` list instead of
   replacing what is there:

       {
         "hooks": {
           "PreToolUse": [
             {
               "matcher": "AskUserQuestion",
               "hooks": [
                 { "type": "command", "command": "~/.local/bin/kudde-question-hook.sh" }
               ]
             }
           ]
         }
       }

   Use the full path if your setup does not expand `~` in hook commands.

3. Start a new Claude Code session. Sessions that were already running may
   keep the settings they started with.

## Check it

Ask Claude Code to ask you something with its question tool. While the
dialog waits, a file for the session appears in
`~/.local/state/kudde/questions/`, and the chat on the phone shows the
question with its options.

## Remove it

Delete the `AskUserQuestion` entry from `~/.claude/settings.json`, the
script, and `~/.local/state/kudde/questions/`.

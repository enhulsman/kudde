#!/bin/sh
# Claude Code PreToolUse hook for AskUserQuestion (kudde, chat-questions).
#
# Claude Code writes a waiting question to its session log only once it is
# answered, so the phone cannot read it there. This hook gets the whole
# question before the dialog is drawn and saves it as the session's record:
#
#   ${XDG_STATE_HOME:-$HOME/.local/state}/kudde/questions/<session id>.json
#
# kudde reads that record over SSH and still checks the terminal's screen
# before it sends any key. The hook prints nothing and always exits 0, so it
# never changes what Claude Code does. Records older than a day are removed.
# Install: docs/claude-code-question-hook.md.
umask 077
dir="${XDG_STATE_HOME:-$HOME/.local/state}/kudde/questions"
input=$(cat)
session=$(printf '%s\n' "$input" | sed -n 's/.*"session_id" *: *"\([^"]*\)".*/\1/p' | head -n 1)
case $session in
  '' | *[!0-9a-f-]*) exit 0 ;;
esac
mkdir -p "$dir" 2>/dev/null || exit 0
# Only into a directory of our own that nobody else may write: not a link,
# owned by us, no group or other write bit (on it or on its parent).
for d in "$dir" "${dir%/*}"; do
  [ -L "$d" ] && exit 0
  [ -d "$d" ] && [ -O "$d" ] || exit 0
  case $(ls -ld "$d" 2>/dev/null) in
    d????-??-*) ;;
    *) exit 0 ;;
  esac
done
tmp=$(mktemp "$dir/.$session.XXXXXX" 2>/dev/null) || exit 0
if printf '%s' "$input" > "$tmp" 2>/dev/null; then
  mv -f "$tmp" "$dir/$session.json" 2>/dev/null
fi
rm -f "$tmp" 2>/dev/null
find "$dir" -name '*.json' -mtime +1 -exec rm -f {} + 2>/dev/null
exit 0

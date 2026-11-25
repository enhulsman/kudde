//! The question hook (chat-questions design D1, section E): the script
//! Claude Code runs before AskUserQuestion saves the call as the session's
//! record; the reader prints it back; the parser takes the call from it.
//! Notes on a preview option (D9): the keys and what the screen must show.

use kudde_chat::question::{self, Answer, Record, Shape};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const HOOK: &str = include_str!("../../../contrib/claude-code/kudde-question-hook.sh");
const SESSION: &str = "11111111-1111-4111-8111-111111111111";

fn hook_input(session: &str) -> Value {
    json!({
        "session_id": session,
        "transcript_path": "/home/dev/.claude/projects/-home-dev-src-website/x.jsonl",
        "cwd": "/home/dev/src/website",
        "hook_event_name": "PreToolUse",
        "tool_name": "AskUserQuestion",
        "tool_input": {"questions": [{
            "question": "Which list layout?", "header": "Layout", "multiSelect": false,
            "options": [
                {"label": "Compact", "description": "Dense rows.", "preview": "+--+\n|a |\n+--+"},
                {"label": "Cards", "description": "Roomy blocks.", "preview": "+----+ +----+"}
            ]
        }]},
        "tool_use_id": "toolu_01HfEPgo"
    })
}

/// A fresh state directory for one test.
fn state(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("kudde-hook-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// Runs `script` with `args` under `sh`, `stdin` piped in, the state
/// directory as XDG_STATE_HOME; (exit code, stdout).
fn run(script: &str, args: &[&str], stdin: &str, state: &Path) -> (i32, String) {
    let mut child = Command::new("sh")
        .arg("-c")
        .arg(script)
        .arg("sh")
        .args(args)
        .env("XDG_STATE_HOME", state)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    use std::io::Write;
    child
        .stdin
        .take()
        .unwrap()
        .write_all(stdin.as_bytes())
        .unwrap();
    let out = child.wait_with_output().unwrap();
    (
        out.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&out.stdout).into_owned(),
    )
}

mod script {
    use super::*;

    #[test]
    fn the_hook_saves_the_call_as_the_sessions_record_and_says_nothing() {
        let d = state("save");
        let input = hook_input(SESSION).to_string();
        let (code, out) = run(HOOK, &[], &input, &d);
        assert_eq!(code, 0);
        assert_eq!(out, "", "the hook must not answer for Claude Code");
        let saved =
            std::fs::read_to_string(d.join(format!("kudde/questions/{SESSION}.json"))).unwrap();
        assert_eq!(saved, input);
    }

    #[test]
    fn a_later_call_replaces_the_record() {
        let d = state("replace");
        run(HOOK, &[], &hook_input(SESSION).to_string(), &d);
        let mut second = hook_input(SESSION);
        second["tool_use_id"] = json!("toolu_02");
        run(HOOK, &[], &second.to_string(), &d);
        let (_, out) = run(question::HOOK_READ_SCRIPT, &[SESSION], "", &d);
        assert_eq!(question::read_record(&out).unwrap().id, "toolu_02");
    }

    #[test]
    fn input_without_a_plain_session_id_is_not_saved() {
        let d = state("bad");
        for input in [
            "not json".to_string(),
            json!({"session_id": "../../etc/x", "tool_name": "AskUserQuestion"}).to_string(),
            json!({"tool_name": "AskUserQuestion"}).to_string(),
        ] {
            let (code, out) = run(HOOK, &[], &input, &d);
            assert_eq!((code, out.as_str()), (0, ""));
        }
        let dir = d.join("kudde/questions");
        let left = std::fs::read_dir(&dir).map(|r| r.count()).unwrap_or(0);
        assert_eq!(left, 0);
    }

    #[test]
    fn the_reader_prints_the_record_or_missing() {
        let d = state("read");
        let input = hook_input(SESSION).to_string();
        run(HOOK, &[], &input, &d);
        let (_, out) = run(question::HOOK_READ_SCRIPT, &[SESSION], "", &d);
        assert_eq!(out, input);
        let (_, out) = run(
            question::HOOK_READ_SCRIPT,
            &["22222222-1111-4111-8111-111111111111"],
            "",
            &d,
        );
        assert_eq!(out.trim(), "missing");
        // Only a plain session id is ever read.
        let (_, out) = run(question::HOOK_READ_SCRIPT, &["../questions/x"], "", &d);
        assert_eq!(out.trim(), "missing");
        let (_, out) = run(question::HOOK_READ_SCRIPT, &[""], "", &d);
        assert_eq!(out.trim(), "missing");
    }

    // 5.4 review (Codex): the state tree may not be the user's alone.

    #[test]
    fn a_symlinked_record_is_not_read() {
        let d = state("link-read");
        let dir = d.join("kudde/questions");
        std::fs::create_dir_all(&dir).unwrap();
        let elsewhere = d.join("forged.json");
        std::fs::write(&elsewhere, hook_input(SESSION).to_string()).unwrap();
        std::os::unix::fs::symlink(&elsewhere, dir.join(format!("{SESSION}.json"))).unwrap();
        let (_, out) = run(question::HOOK_READ_SCRIPT, &[SESSION], "", &d);
        assert!(
            question::read_record(&out).is_none(),
            "read through a link: {out}"
        );
        assert_ne!(out.trim(), "missing", "a link is not a missing hook");
    }

    #[test]
    fn the_hook_writes_nothing_through_a_symlinked_directory() {
        let d = state("link-dir");
        let target = d.join("target");
        std::fs::create_dir_all(&target).unwrap();
        std::fs::create_dir_all(d.join("kudde")).unwrap();
        std::os::unix::fs::symlink(&target, d.join("kudde/questions")).unwrap();
        let (code, out) = run(HOOK, &[], &hook_input(SESSION).to_string(), &d);
        assert_eq!((code, out.as_str()), (0, ""));
        assert_eq!(std::fs::read_dir(&target).unwrap().count(), 0);
    }

    #[test]
    fn the_hook_writes_nothing_into_a_directory_others_may_write() {
        use std::os::unix::fs::PermissionsExt;
        let d = state("open-dir");
        let dir = d.join("kudde/questions");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o777)).unwrap();
        let (code, out) = run(HOOK, &[], &hook_input(SESSION).to_string(), &d);
        assert_eq!((code, out.as_str()), (0, ""));
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0);
    }

    #[test]
    fn the_record_and_its_directory_are_private() {
        use std::os::unix::fs::PermissionsExt;
        let d = state("private");
        run(HOOK, &[], &hook_input(SESSION).to_string(), &d);
        let dir = d.join("kudde/questions");
        let mode = |p: &Path| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&dir), 0o700);
        assert_eq!(mode(&dir.join(format!("{SESSION}.json"))), 0o600);
        let left: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(left.len(), 1, "no temporary file left: {left:?}");
    }
}

mod record {
    use super::*;

    #[test]
    fn the_record_gives_the_call_whole() {
        let r: Record = question::read_record(&hook_input(SESSION).to_string()).unwrap();
        assert_eq!(r.id, "toolu_01HfEPgo");
        assert_eq!(r.session, SESSION);
        let Shape::Readable(qs) = r.shape else {
            panic!("readable expected")
        };
        assert_eq!(qs[0].text, "Which list layout?");
        assert_eq!(qs[0].options[1].label, "Cards");
        assert_eq!(
            qs[0].options[0].preview.as_deref(),
            Some("+--+\n|a |\n+--+")
        );
    }

    #[test]
    fn missing_another_tool_or_no_id_is_no_record() {
        assert_eq!(question::read_record("missing\n"), None);
        assert_eq!(question::read_record(""), None);
        assert_eq!(question::read_record("{not json"), None);
        let mut v = hook_input(SESSION);
        v["tool_name"] = json!("Bash");
        assert_eq!(question::read_record(&v.to_string()), None);
        let mut v = hook_input(SESSION);
        v["tool_use_id"] = json!("");
        assert_eq!(question::read_record(&v.to_string()), None);
        let mut v = hook_input(SESSION);
        v.as_object_mut().unwrap().remove("session_id");
        assert_eq!(question::read_record(&v.to_string()), None);
    }

    #[test]
    fn a_shape_the_card_cannot_take_keeps_its_texts() {
        let mut v = hook_input(SESSION);
        v["tool_input"]["questions"][0]["options"] = json!([{"description": "no label"}]);
        let r = question::read_record(&v.to_string()).unwrap();
        assert_eq!(
            r.shape,
            Shape::Unreadable {
                texts: vec!["Which list layout?".into()]
            }
        );
    }
}

mod notes {
    use super::*;

    fn layout() -> question::Question {
        match question::read_record(&hook_input(SESSION).to_string())
            .unwrap()
            .shape
        {
            Shape::Readable(mut qs) => qs.remove(0),
            _ => unreachable!(),
        }
    }

    const BEFORE: &str = "\
 ☐ Layout
Which list layout?
  1. Compact                      ┌────────────┐
❯ 2. Cards                        │ +----+     │
                                  └────────────┘
                                  Notes: press n to add notes
  Chat about this
Enter to select · ↑/↓ to navigate · n to add notes · Esc to cancel
";

    #[test]
    fn a_note_starts_with_the_digit_alone() {
        assert_eq!(
            question::plan_note(&layout(), 1),
            Some(vec!["2".to_string()])
        );
        assert_eq!(question::plan_note(&layout(), 2), None);
        // Only where the dialog has notes: a question with previews, single choice.
        let mut plain = layout();
        for o in &mut plain.options {
            o.preview = None;
        }
        assert_eq!(question::plan_note(&plain, 0), None);
        let mut multi = layout();
        multi.multi = true;
        assert_eq!(question::plan_note(&multi, 0), None);
        // Without a note a preview option is its digit and Enter.
        assert_eq!(
            question::plan(&layout(), &Answer::Option(1)),
            Some(vec!["2".to_string(), "enter".to_string()])
        );
    }

    #[test]
    fn the_focus_is_read_from_the_last_pointer_on_the_screen() {
        assert!(question::focused(BEFORE, 2));
        assert!(!question::focused(BEFORE, 1));
        let older = format!("❯ 1. an earlier prompt line\n{BEFORE}");
        assert!(question::focused(&older, 2));
        assert!(!question::focused("> \n", 1));
    }

    #[test]
    fn the_notes_field_reads_open_once_its_hint_is_gone() {
        assert!(!question::notes_open(BEFORE));
        let open = BEFORE.replace(
            "Notes: press n to add notes",
            "Notes: Add notes on this design…",
        );
        assert!(question::notes_open(&open));
        assert!(!question::notes_open("> \n"));
    }
}

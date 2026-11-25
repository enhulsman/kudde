//! Chat-questions task 3.2: answering an agent's question from the chat
//! against the fake core (design D2, D4, D7, D8). The order of the calls is
//! the safety: nothing is pressed before `terminal.get` (with a fresh
//! lookup) and `terminal.read` agree, one `terminal.keys` call per step,
//! never repeated, and the screen afterwards decides whether it arrived.

use super::*;
use crate::hub::chat::{Chat, ChatAnswer, ChatChoice};
use base64::Engine;

const ADDR: &str = "t1";
const SESSION: &str = "aaaaaaaa-1111-4111-8111-111111111111";
const LOG: &str =
    "/home/dev/.claude/projects/-home-dev-src-website/aaaaaaaa-1111-4111-8111-111111111111.jsonl";
const PID: i64 = 4242;
const START: u64 = 9001;

fn ask_line(id: &str, questions: Value) -> String {
    json!({
        "type": "assistant", "isSidechain": false, "sessionId": SESSION,
        "message": {"id": format!("msg_{id}"), "role": "assistant", "content": [
            {"type": "tool_use", "id": id, "name": "AskUserQuestion", "input": {"questions": questions}}
        ]}
    })
    .to_string()
}

fn result_line(id: &str, is_error: bool) -> String {
    json!({
        "type": "user", "isSidechain": false, "sessionId": SESSION,
        "message": {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": id, "content": "answered", "is_error": is_error}
        ]}
    })
    .to_string()
}

fn database() -> Value {
    json!({
        "question": "Which database should the todo app use?", "header": "Database",
        "multiSelect": false,
        "options": [
            {"label": "SQLite (Recommended)", "description": "One file, no server."},
            {"label": "Postgres", "description": "Needs a running server.", "preview": "services:\n  db: postgres"}
        ]
    })
}

fn login() -> Value {
    json!({
        "question": "Which login should it have?", "header": "Login", "multiSelect": false,
        "options": [
            {"label": "Password", "description": "A form."},
            {"label": "Passkey", "description": "The phone signs in."}
        ]
    })
}

const DIALOG: &str = "\
╭──────────────────────────────────────────────╮
│ ☐ Database                                   │
│ Which database should the todo app use?      │
│ ❯ 1. SQLite (Recommended)                    │
│      One file, no server.                    │
│   2. Postgres                                │
│      Needs a running server.                 │
│   3. Type something.                         │
╰──────────────────────────────────────────────╯
";
const LOGIN: &str = "\
│ ☒ Database  ☐ Login  ✔ Submit │
│ Which login should it have?   │
│ ❯ 1. Password                 │
│   2. Passkey                  │
";
const REVIEW: &str = "\
│ ☒ Database  ☒ Login  ✔ Submit │
│ Review your answers           │
│  ● Which database should the todo app use? │
│    → Postgres                 │
│  ● Which login should it have? │
│    → Passkey                  │
│ ❯ 1. Submit answers           │
│   2. Cancel                   │
";
/// [DIALOG] with the focus on its free-text row, as digit 3 leaves it (desk, 2.1.332).
const DIALOG_FREE: &str = "\
╭──────────────────────────────────────────────╮
│ ☐ Database                                   │
│ Which database should the todo app use?      │
│   1. SQLite (Recommended)                    │
│      One file, no server.                    │
│   2. Postgres                                │
│      Needs a running server.                 │
│ ❯ 3. Type something.                         │
╰──────────────────────────────────────────────╯
";
const PROMPT: &str = "⏺ Going with Postgres.\n\n> \n";
const OTHER: &str = "│ Allow this edit to notes.md? │\n│ ❯ 1. Yes │\n│ 2. No │\n";

fn terminal(status: &str) -> Value {
    json!({"address": ADDR, "cwd": "/home/dev/src/website", "state": "live",
           "status": {"state": status, "app": "claude"},
           "foreground": {"pid": PID, "name": "claude", "argv": ["claude"]}})
}

fn exec_out(stdout: &str) -> Value {
    json!({"stdout": base64::engine::general_purpose::STANDARD.encode(stdout),
           "stderr": "", "code": 0, "timed_out": false, "truncated": false, "ms": 20})
}

fn probe(session: &str) -> String {
    format!(
        "name claude\nprocstart {START}\npidfile {{\"sessionId\":\"{session}\",\"procStart\":\"{START}\"}}\nlog {LOG}\n"
    )
}

/// The tail script's output for `lines` written from byte `at`.
fn tail(at: u64, lines: &[String]) -> String {
    let mut out = String::new();
    let mut pos = at;
    let mut body = String::new();
    for l in lines {
        body.push_str(&format!("{pos} {l}\n"));
        pos += l.len() as u64 + 1;
    }
    out.push_str(&format!("size {pos}\nfrom {at}\nnext {pos}\n"));
    out.push_str(&body);
    out
}

impl Core {
    /// `terminal.get` (the status given), then the lookup for the session.
    async fn serves_check(&mut self, status: &str) {
        let req = self.expect("terminal.get").await;
        assert_eq!(req["params"]["terminal"], ADDR);
        self.reply(&req, terminal(status)).await;
        let req = self.expect("machine.exec").await;
        assert_eq!(req["params"]["argv"][4], PID.to_string(), "{req}");
        self.reply(&req, exec_out(&probe(SESSION))).await;
    }

    async fn serves_screen(&mut self, text: &str) {
        let req = self.expect("terminal.read").await;
        assert_eq!(req["params"]["terminal"], ADDR);
        self.reply(
            &req,
            json!({"text": text, "complete": true, "how": "screen"}),
        )
        .await;
    }

    /// The next request is `terminal.keys` with exactly these keys; answered.
    async fn takes_keys(&mut self, keys: &[&str]) -> Value {
        let req = self.expect("terminal.keys").await;
        assert_eq!(req["params"]["terminal"], ADDR);
        assert_eq!(req["params"]["keys"], json!(keys), "{req}");
        req
    }

    async fn serves_log(&mut self, at: u64, lines: &[String]) {
        let req = self.expect("machine.exec").await;
        assert_eq!(
            req["params"]["argv"][2],
            kudde_chat::tail::SCRIPT,
            "a log read"
        );
        self.reply(&req, exec_out(&tail(at, lines))).await;
    }

    /// No request arrives within 10 s.
    async fn nothing_more(&mut self) {
        let got = tokio::time::timeout(Duration::from_secs(10), self.request()).await;
        assert!(got.is_err(), "unexpected request: {:?}", got.unwrap());
    }
}

/// A chat on a connected desk that holds `lines` of the log, with the log's
/// call with no result held as if the hook's record had given it.
async fn with_log(r: &mut Rig, lines: &[String]) -> (Core, Arc<Chat>, u64) {
    let (core, chat, end) = open_chat(r, lines).await;
    chat.hold_for_test();
    (core, chat, end)
}

/// A chat on a connected desk that holds `lines` of the log; nothing held.
async fn open_chat(r: &mut Rig, lines: &[String]) -> (Core, Arc<Chat>, u64) {
    r.add_trusted(WS, WS_KEY);
    r.hub.resume();
    let mut core = r.core().await;
    core.connect("0.1.15", vec![agent(ADDR, "blocked", 1)])
        .await;
    r.until_state(WS, connected).await;
    let chat = Arc::new(Chat::new(r.hub.clone(), WS.into(), ADDR.into()));
    let end = chat.seed(LOG, SESSION, PID, &tail(0, lines));
    (core, chat, end)
}

fn spawn_answer(
    chat: &Arc<Chat>,
    id: &str,
    step: u32,
    choice: ChatChoice,
) -> tokio::task::JoinHandle<ChatAnswer> {
    let chat = chat.clone();
    let id = id.to_owned();
    tokio::spawn(async move { chat.answer_async(id, step, choice).await })
}

// ---- the answer path

#[tokio::test(start_paused = true)]
async fn an_option_is_checked_then_sent_as_one_keys_call() {
    let mut r = rig("q-option");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 1 });
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let keys = core.takes_keys(&["2", "enter"]).await;
    core.reply(&keys, json!({"turn": 3, "status": "blocked"}))
        .await;
    // The dialog is gone; the log then holds the result.
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn no_keys_when_the_status_is_no_longer_blocked() {
    let mut r = rig("q-working");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("working").await;
    assert_eq!(got.await.unwrap(), ChatAnswer::AlreadyAnswered);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn no_keys_when_the_screen_shows_another_dialog() {
    let mut r = rig("q-other");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("blocked").await;
    core.serves_screen(OTHER).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn keys_with_no_reply_are_not_sent_again() {
    let mut r = rig("q-noreply");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let _unanswered = core.takes_keys(&["1", "enter"]).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Unconfirmed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_lost_connection_during_the_keys_is_unconfirmed() {
    let mut r = rig("q-lost");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    core.takes_keys(&["1", "enter"]).await;
    core.exit(255, "connection reset");
    assert_eq!(got.await.unwrap(), ChatAnswer::Unconfirmed);
}

#[tokio::test(start_paused = true)]
async fn a_screen_that_never_moves_on_is_unconfirmed_and_not_repeated() {
    let mut r = rig("q-rebound");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let keys = core.takes_keys(&["1", "enter"]).await;
    core.reply(&keys, json!({"turn": 3})).await;
    // Every look afterwards still shows the same question; no key goes again.
    let mut got = got;
    let answered = loop {
        let req = tokio::select! {
            req = core.request() => req,
            a = &mut got => break a.unwrap(),
        };
        match req["method"].as_str() {
            Some("terminal.read") => {
                core.reply(
                    &req,
                    json!({"text": DIALOG, "complete": true, "how": "screen"}),
                )
                .await
            }
            other => panic!("only screen reads after the keys, got {other:?}: {req}"),
        }
    };
    assert_eq!(answered, ChatAnswer::Unconfirmed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn free_text_focuses_the_row_then_types_with_force() {
    let mut r = rig("q-text");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Other {
            text: "Use Postgres, but through Docker".into(),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let keys = core.takes_keys(&["3"]).await;
    core.reply(&keys, json!({"turn": 3})).await;
    core.serves_screen(DIALOG_FREE).await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["terminal"], ADDR);
    assert_eq!(send["params"]["text"], "Use Postgres, but through Docker");
    assert_eq!(send["params"]["force"], true);
    assert!(send["params"].get("if").is_none());
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

/// 5.4 review: the text goes only once the screen shows the free row focused.
#[tokio::test(start_paused = true)]
async fn free_text_waits_for_its_row_in_focus_and_never_types_without_it() {
    let mut r = rig("q-text-focus");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Other {
            text: "Use Postgres".into(),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let keys = core.takes_keys(&["3"]).await;
    core.reply(&keys, json!({"turn": 3})).await;
    // The focus stays on option 1 (a rebound digit, a lagging redraw): no paste.
    assert_eq!(
        only_reads_until(&mut core, got, DIALOG).await,
        ChatAnswer::Unconfirmed
    );
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn free_text_over_the_limit_sends_nothing() {
    let mut r = rig("q-long");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let text = "x".repeat(2001);
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Other { text });
    assert!(matches!(got.await.unwrap(), ChatAnswer::Failed { .. }));
    let blank = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Other { text: "  ".into() },
    );
    assert!(matches!(blank.await.unwrap(), ChatAnswer::Failed { .. }));
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn skip_sends_one_esc_after_the_checks() {
    let mut r = rig("q-skip");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Skip);
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let keys = core.takes_keys(&["esc"]).await;
    core.reply(&keys, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", true)]).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn skip_sends_nothing_when_a_check_fails() {
    let mut r = rig("q-skip-late");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Skip);
    core.serves_check("working").await;
    assert_eq!(got.await.unwrap(), ChatAnswer::AlreadyAnswered);
    let again = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Skip);
    core.serves_check("blocked").await;
    core.serves_screen(PROMPT).await;
    assert_eq!(again.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_tap_racing_the_end_of_the_card_sends_nothing() {
    let mut r = rig("q-race");
    let (mut core, chat, _) = with_log(
        &mut r,
        &[
            ask_line("toolu_01", json!([database()])),
            result_line("toolu_01", false),
        ],
    )
    .await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    assert_eq!(got.await.unwrap(), ChatAnswer::AlreadyAnswered);
    // A call the log does not hold at all: nothing either.
    let unknown = spawn_answer(&chat, "toolu_99", 0, ChatChoice::Pick { index: 0 });
    assert_eq!(unknown.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn another_session_in_front_sends_nothing() {
    let mut r = rig("q-session");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    let req = core.expect("terminal.get").await;
    core.reply(&req, terminal("blocked")).await;
    let req = core.expect("machine.exec").await;
    core.reply(
        &req,
        exec_out(&probe("bbbbbbbb-1111-4111-8111-111111111111")),
    )
    .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn several_questions_step_by_step_then_send_answers() {
    let mut r = rig("q-steps");
    let (mut core, chat, end) = with_log(
        &mut r,
        &[ask_line("toolu_01", json!([database(), login()]))],
    )
    .await;
    let first = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 1 });
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["2", "enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(LOGIN).await;
    assert_eq!(first.await.unwrap(), ChatAnswer::Done);
    // A tap meant for the first question once the screen is on the second.
    let stale = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("blocked").await;
    core.serves_screen(LOGIN).await;
    assert_eq!(stale.await.unwrap(), ChatAnswer::Changed);
    let second = spawn_answer(&chat, "toolu_01", 1, ChatChoice::Pick { index: 1 });
    core.serves_check("blocked").await;
    core.serves_screen(LOGIN).await;
    // No previews: the digit alone answers (5.1).
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(REVIEW).await;
    assert_eq!(second.await.unwrap(), ChatAnswer::Done);
    let send = spawn_answer(&chat, "toolu_01", 2, ChatChoice::Submit);
    core.serves_check("blocked").await;
    core.serves_screen(REVIEW).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(send.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn multi_select_is_not_sent_with_the_switch_off() {
    let mut r = rig("q-multi");
    let mut v = database();
    v["multiSelect"] = json!(true);
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([v]))]).await;
    // The switch stays as a way back should a later Claude Code change the dialog.
    chat.set_multi_for_test(false);
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![0, 1],
            text: None,
        },
    );
    assert!(matches!(got.await.unwrap(), ChatAnswer::Failed { .. }));
    core.nothing_more().await;
}

// ---- the card in the poll (design D1: the hook's record, the log, the screen)

/// The hook's record for `id` asking `questions`, as the reader prints it.
fn record(id: &str, session: &str, questions: Value) -> String {
    json!({"session_id": session, "hook_event_name": "PreToolUse", "tool_name": "AskUserQuestion",
           "tool_input": {"questions": questions}, "tool_use_id": id})
    .to_string()
}

fn terminal_kind(status: &str, kind: &str) -> Value {
    let mut t = terminal(status);
    t["status"]["kind"] = json!(kind);
    t
}

impl Core {
    /// The reader of the hook's record for the session: `None` prints "missing".
    async fn serves_record(&mut self, rec: Option<&str>) {
        let req = self.expect("machine.exec").await;
        assert_eq!(
            req["params"]["argv"][2],
            kudde_chat::question::HOOK_READ_SCRIPT,
            "a record read: {req}"
        );
        assert_eq!(req["params"]["argv"][4], SESSION);
        self.reply(&req, exec_out(rec.unwrap_or("missing\n"))).await;
    }
}

/// One poll: `terminal.get` with `t`, the log, then (when a question may
/// wait) the record and (when it is fresh and readable) the screen.
async fn poll_with(
    core: &mut Core,
    chat: &Arc<Chat>,
    t: Value,
    end: u64,
    rec: Option<Option<&str>>,
    screen: Option<&str>,
) -> crate::hub::chat::ChatPoll {
    let c = chat.clone();
    let got = tokio::spawn(async move { c.poll_for_test().await });
    let req = core.expect("terminal.get").await;
    core.reply(&req, t).await;
    core.serves_log(end, &[]).await;
    if let Some(r) = rec {
        core.serves_record(r).await;
    }
    if let Some(s) = screen {
        core.serves_screen(s).await;
    }
    got.await.unwrap()
}

#[tokio::test(start_paused = true)]
async fn the_record_a_block_and_the_screen_give_the_card() {
    let mut r = rig("q-card");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let rec = record("toolu_01", SESSION, json!([database()]));
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "question"),
        end,
        Some(Some(&rec)),
        Some(DIALOG),
    )
    .await;
    let q = p.question.expect("a card");
    assert_eq!(q.id, "toolu_01");
    assert!(q.readable);
    assert_eq!((q.step, q.review), (0, false));
    let a = &q.questions[0];
    assert_eq!(a.text, "Which database should the todo app use?");
    assert_eq!(a.header, "Database");
    assert!(a.answerable);
    assert_eq!(a.options[1].label, "Postgres");
    assert_eq!(a.options[1].description, "Needs a running server.");
    assert_eq!(
        a.options[1].preview.as_deref(),
        Some("services:\n  db: postgres")
    );
    assert_eq!(a.options[0].preview, None);
    // The card then answers from the held record.
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["1", "enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    // Claude Code 2.1.331 writes the call and its result together.
    core.serves_log(
        end,
        &[
            ask_line("toolu_01", json!([database()])),
            result_line("toolu_01", false),
        ],
    )
    .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn no_record_says_open_the_terminal_and_answers_nothing() {
    let mut r = rig("q-nohook");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "question"),
        end,
        Some(None),
        None,
    )
    .await;
    let q = p.question.expect("a card that says so");
    assert!(!q.readable);
    assert!(q.questions.is_empty());
    assert!(q.texts.is_empty());
    assert!(
        q.hook_missing,
        "no record at all: the card says to add the hook"
    );
    let got = spawn_answer(&chat, &q.id, 0, ChatChoice::Pick { index: 0 });
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_record_that_cannot_be_read_is_not_a_missing_hook() {
    let mut r = rig("q-unread");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "question"),
        end,
        Some(Some("{not json")),
        None,
    )
    .await;
    let q = p.question.unwrap();
    assert!(!q.readable);
    assert!(
        !q.hook_missing,
        "only the reader's \"missing\" means no hook"
    );
}

#[tokio::test(start_paused = true)]
async fn a_stale_record_says_open_the_terminal() {
    let mut r = rig("q-stale");
    let (mut core, chat, end) = open_chat(
        &mut r,
        &[
            ask_line("toolu_01", json!([database()])),
            result_line("toolu_01", false),
        ],
    )
    .await;
    let rec = record("toolu_01", SESSION, json!([database()]));
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "question"),
        end,
        Some(Some(&rec)),
        None,
    )
    .await;
    let q = p.question.unwrap();
    assert!(!q.readable);
    assert!(
        q.texts.is_empty(),
        "an answered call's text is not the new question"
    );
    assert!(!q.hook_missing, "a record exists: the hook is there");
}

#[tokio::test(start_paused = true)]
async fn a_record_of_another_session_says_open_the_terminal() {
    let mut r = rig("q-other-session");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let rec = record(
        "toolu_01",
        "bbbbbbbb-1111-4111-8111-111111111111",
        json!([database()]),
    );
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "question"),
        end,
        Some(Some(&rec)),
        None,
    )
    .await;
    let q = p.question.unwrap();
    assert!(!q.readable);
    assert!(!q.hook_missing);
}

#[tokio::test(start_paused = true)]
async fn a_screen_that_disagrees_says_open_the_terminal() {
    let mut r = rig("q-disagree");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let rec = record("toolu_01", SESSION, json!([database()]));
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "question"),
        end,
        Some(Some(&rec)),
        Some(OTHER),
    )
    .await;
    let q = p.question.unwrap();
    assert!(!q.readable);
    assert_eq!(
        q.texts,
        vec!["Which database should the todo app use?".to_string()]
    );
    assert!(!q.hook_missing);
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_permission_prompt_shows_no_card_and_reads_no_record() {
    let mut r = rig("q-permission");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let p = poll_with(
        &mut core,
        &chat,
        terminal_kind("blocked", "permission"),
        end,
        None,
        None,
    )
    .await;
    assert!(p.question.is_none());
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn no_card_unless_blocked() {
    let mut r = rig("q-nocard");
    let (mut core, chat, end) =
        open_chat(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    for status in ["idle", "working", "done"] {
        let p = poll_with(&mut core, &chat, terminal(status), end, None, None).await;
        assert!(p.question.is_none(), "{status}");
    }
}

#[tokio::test(start_paused = true)]
async fn a_call_in_the_log_without_a_record_says_open_the_terminal() {
    // A Claude Code that writes the waiting call early, with no hook and no status kind.
    let mut r = rig("q-log-only");
    let (mut core, chat, end) =
        open_chat(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let p = poll_with(&mut core, &chat, terminal("blocked"), end, Some(None), None).await;
    let q = p.question.unwrap();
    assert_eq!(q.id, "toolu_01");
    assert!(!q.readable);
    assert!(q.hook_missing);
    assert_eq!(
        q.texts,
        vec!["Which database should the todo app use?".to_string()]
    );
}

#[tokio::test(start_paused = true)]
async fn several_questions_follow_the_screen() {
    let mut r = rig("q-follow");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let rec = record("toolu_01", SESSION, json!([database(), login()]));
    let t = || terminal_kind("blocked", "question");
    let q = poll_with(&mut core, &chat, t(), end, Some(Some(&rec)), Some(LOGIN))
        .await
        .question
        .unwrap();
    assert_eq!((q.readable, q.step, q.review), (true, 1, false));
    let q = poll_with(&mut core, &chat, t(), end, Some(Some(&rec)), Some(REVIEW))
        .await
        .question
        .unwrap();
    assert_eq!((q.step, q.review), (2, true));
    // A screen the card does not know keeps the step it had for the call held.
    let q = poll_with(&mut core, &chat, t(), end, Some(Some(&rec)), Some(OTHER))
        .await
        .question
        .unwrap();
    assert_eq!((q.readable, q.step, q.review), (true, 2, true));
}

#[tokio::test(start_paused = true)]
async fn an_unreadable_record_shows_its_text_only_and_multi_select_is_answerable() {
    let mut r = rig("q-unreadable");
    let (mut core, chat, end) = open_chat(&mut r, &[]).await;
    let bad = json!([{"question": "Which database?", "header": "Database", "multiSelect": false,
                      "options": [{"description": "no label"}, {"label": "Postgres"}]}]);
    let rec = record("toolu_01", SESSION, bad);
    let q = poll_with(
        &mut core,
        &chat,
        terminal("blocked"),
        end,
        Some(Some(&rec)),
        None,
    )
    .await
    .question
    .unwrap();
    assert!(!q.readable);
    assert_eq!(q.texts, vec!["Which database?".to_string()]);
    let mut v = database();
    v["multiSelect"] = json!(true);
    let rec = record("toolu_02", SESSION, json!([v]));
    let q = poll_with(
        &mut core,
        &chat,
        terminal("blocked"),
        end,
        Some(Some(&rec)),
        Some(DIALOG),
    )
    .await
    .question
    .unwrap();
    assert!(q.readable);
    assert!(q.questions[0].multi);
    assert!(
        q.questions[0].answerable,
        "multi-select passed on the desk and the phone (owner, 2026-11-25)"
    );
}

#[tokio::test(start_paused = true)]
async fn nothing_is_answered_without_a_held_record() {
    let mut r = rig("q-unheld");
    let (mut core, chat, _) = open_chat(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

// ---- a note on a preview option (design D9)

const FOCUSED: &str = "\
 ☐ Database
Which database should the todo app use?
  1. SQLite (Recommended)        ┌──────────────┐
❯ 2. Postgres                    │ services:    │
                                 └──────────────┘
                                 Notes: press n to add notes
  Chat about this
";

#[tokio::test(start_paused = true)]
async fn a_note_is_the_digit_then_n_then_the_note_each_after_its_screen() {
    let mut r = rig("q-note");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Note {
            index: 1,
            text: "Through Docker, please".into(),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(FOCUSED).await;
    let k = core.takes_keys(&["n"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&FOCUSED.replace("press n to add notes", "Add notes on this design…"))
        .await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "Through Docker, please");
    assert_eq!(send["params"]["force"], true);
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_focus_that_never_shows_stops_before_n() {
    let mut r = rig("q-note-stuck");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Note {
            index: 1,
            text: "Docker".into(),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    assert_eq!(
        only_reads_until(&mut core, got, DIALOG).await,
        ChatAnswer::Unconfirmed
    );
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn an_empty_note_is_a_plain_pick_and_a_long_one_sends_nothing() {
    let mut r = rig("q-note-empty");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Note {
            index: 1,
            text: "  ".into(),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["2", "enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    let long = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Note {
            index: 1,
            text: "x".repeat(2001),
        },
    );
    assert!(matches!(long.await.unwrap(), ChatAnswer::Failed { .. }));
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_note_where_the_dialog_takes_none_sends_nothing() {
    let mut r = rig("q-note-none");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([login()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Note {
            index: 0,
            text: "Hi".into(),
        },
    );
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn no_connection_sends_nothing_and_says_so() {
    let r = rig("q-offline");
    let chat = Arc::new(Chat::new(r.hub.clone(), WS.into(), ADDR.into()));
    chat.seed(
        LOG,
        SESSION,
        PID,
        &tail(0, &[ask_line("toolu_01", json!([database()]))]),
    );
    chat.hold_for_test();
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    assert_eq!(got.await.unwrap(), ChatAnswer::NotConnected);
}

#[tokio::test(start_paused = true)]
async fn no_agent_in_front_any_more_sends_nothing() {
    let mut r = rig("q-exited");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 0 });
    let req = core.expect("terminal.get").await;
    // The agent exited and the shell is in front: no lookup, no screen, no keys.
    core.reply(
        &req,
        json!({"address": ADDR, "state": "live", "status": null,
               "foreground": {"pid": 77, "name": "zsh", "argv": ["-zsh"]}}),
    )
    .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

/// Regression (5.1): [2, enter] on the first of three questions without
/// previews also answered the second ("Login" = "None"). The digit goes
/// alone, and nothing follows once the screen is on the next question.
#[tokio::test(start_paused = true)]
async fn an_option_never_answers_the_next_question_too() {
    let mut r = rig("q-no-carry");
    let plain = |text: &str, header: &str, labels: [&str; 3]| {
        json!({"question": text, "header": header, "multiSelect": false,
               "options": labels.iter().map(|l| json!({"label": l, "description": "-"})).collect::<Vec<_>>()})
    };
    let qs = json!([
        plain(
            "Which database should the todo app use?",
            "Database",
            ["SQLite", "Postgres", "JSON files"]
        ),
        plain(
            "How should users log in?",
            "Login",
            ["None", "Passkeys", "Email link"]
        ),
        plain(
            "Where should it be hosted?",
            "Hosting",
            ["Laptop", "VPS", "Cloud"]
        ),
    ]);
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", qs)]).await;
    let first = "\
←  ☐ Database  ☐ Login  ☐ Hosting  ✔ Submit  →
Which database should the todo app use?
❯ 1. SQLite
  2. Postgres
  3. JSON files
  4. Type something.
";
    let second = "\
←  ☒ Database  ☐ Login  ☐ Hosting  ✔ Submit  →
How should users log in?
❯ 1. None
  2. Passkeys
  3. Email link
  4. Type something.
";
    let got = spawn_answer(&chat, "toolu_01", 0, ChatChoice::Pick { index: 1 });
    core.serves_check("blocked").await;
    core.serves_screen(first).await;
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(second).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

// Owner, 2026-11-25: on a multi-select question a typed answer joins the
// ticks and goes out with the one send. The keys are the 5.1 desk round's;
// the production gate stays shut until the phone round; these tests open it.

const FRUIT_Q: &str = "Which fruits would you put in a fruit salad?";

fn fruit() -> Value {
    json!({"question": FRUIT_Q, "header": "Fruit", "multiSelect": true,
           "options": [{"label": "Apple", "description": "Crisp."},
                       {"label": "Banana", "description": "Soft."},
                       {"label": "Mango", "description": "Juicy."}]})
}

fn bowl() -> Value {
    json!({"question": "Which bowl?", "header": "Bowl", "multiSelect": false,
           "options": [{"label": "Glass", "description": "-"}, {"label": "Wood", "description": "-"}]})
}

/// The multi-select dialog with these rows ticked and this free row.
fn fruit_screen(ticked: [bool; 3], free: &str, free_on: bool) -> String {
    let b = |on: bool| if on { "[✔]" } else { "[ ]" };
    format!(
        "←  ☒ Fruit  ✔ Submit  →\n{FRUIT_Q}\n❯ 1. {} Apple\n     Crisp.\n  2. {} Banana\n     Soft.\n  3. {} Mango\n     Juicy.\n  4. {} {free}\n     Submit\n  5. Chat about this\n",
        b(ticked[0]),
        b(ticked[1]),
        b(ticked[2]),
        b(free_on)
    )
}

/// `screen` with the focus on the unnumbered Submit row (Tab from the editor).
fn on_submit(screen: &str) -> String {
    screen
        .replace("❯ 1.", "  1.")
        .replace("❯ 4.", "  4.")
        .replace("     Submit", "❯    Submit")
}

/// `screen` with the focus in the free row, where a paste leaves it (desk, 2.1.332).
fn in_free(screen: &str) -> String {
    screen.replace("❯ 1.", "  1.").replace("  4. [", "❯ 4. [")
}

const FRUIT_REVIEW: &str = "\
←  ☒ Fruit  ✔ Submit  →
Review your answers
 ● Which fruits would you put in a fruit salad?
   → Apple, Mango
❯ 1. Submit answers
  2. Cancel
";

/// Answers every screen read with `screen` until the answer ends; any
/// other request fails the test.
async fn only_reads_until(
    core: &mut Core,
    got: tokio::task::JoinHandle<ChatAnswer>,
    screen: &str,
) -> ChatAnswer {
    let mut got = got;
    loop {
        let req = tokio::select! {
            req = core.request() => req,
            a = &mut got => break a.unwrap(),
        };
        match req["method"].as_str() {
            Some("terminal.read") => {
                core.reply(
                    &req,
                    json!({"text": screen, "complete": true, "how": "screen"}),
                )
                .await
            }
            other => panic!("only screen reads, got {other:?}: {req}"),
        }
    }
}

#[tokio::test(start_paused = true)]
async fn ticks_are_digits_then_tab_then_the_review_is_sent() {
    let mut r = rig("q-multi-ticks");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![2, 0],
            text: None,
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let k = core.takes_keys(&["1", "3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&fruit_screen([true, false, true], "Type something", false))
        .await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    // A call of one question: its review step, then Enter sends.
    core.serves_screen(FRUIT_REVIEW).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_typed_answer_is_pasted_ticked_then_sent_with_the_ticks() {
    let mut r = rig("q-multi-text");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![0, 2],
            text: Some("Berries".into()),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let k = core.takes_keys(&["1", "3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&fruit_screen([true, false, true], "Type something", false))
        .await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "Berries");
    assert_eq!(send["params"]["force"], true);
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    // The text is in the free row, unticked: one Enter ticks it.
    core.serves_screen(&in_free(&fruit_screen(
        [true, false, true],
        "Berries",
        false,
    )))
    .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&in_free(&fruit_screen(
        [true, false, true],
        "Berries",
        true,
    )))
    .await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    // From the editor Tab moves the focus to Submit; Enter only once the screen shows it.
    core.serves_screen(&on_submit(&in_free(&fruit_screen(
        [true, false, true],
        "Berries",
        true,
    ))))
    .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(FRUIT_REVIEW).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_free_row_already_ticked_gets_no_extra_enter() {
    let mut r = rig("q-multi-text-on");
    let (mut core, chat, _) =
        with_log(&mut r, &[ask_line("toolu_01", json!([fruit(), bowl()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![],
            text: Some("Berries".into()),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let send = core.expect("terminal.send").await;
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(&in_free(&fruit_screen([false; 3], "Berries", true)))
        .await;
    core.serves_screen(&in_free(&fruit_screen([false; 3], "Berries", true)))
        .await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&on_submit(&in_free(&fruit_screen(
        [false; 3], "Berries", true,
    ))))
    .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    // A call with a second question: the screen moves on to it; no Enter.
    core.serves_screen("←  ☒ Fruit  ☐ Bowl  ✔ Submit  →\nWhich bowl?\n❯ 1. Glass\n  2. Wood\n")
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

/// 5.4 review: a retry after a lost reply, or a tick made at the desk, sends
/// only the rows the screen shows otherwise (a digit toggles its row).
#[tokio::test(start_paused = true)]
async fn ticks_already_on_screen_are_not_toggled_again() {
    let mut r = rig("q-multi-retry");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![0, 2],
            text: None,
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([true, false, false], "Type something", false))
        .await;
    let k = core.takes_keys(&["3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&fruit_screen([true, false, true], "Type something", false))
        .await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(FRUIT_REVIEW).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

/// 5.4 review: Tab from the editor must show Submit in focus before Enter.
#[tokio::test(start_paused = true)]
async fn no_enter_unless_submit_shows_in_focus() {
    let mut r = rig("q-multi-tab");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![],
            text: Some("Berries".into()),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let send = core.expect("terminal.send").await;
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(&in_free(&fruit_screen([false; 3], "Berries", true)))
        .await;
    core.serves_screen(&in_free(&fruit_screen([false; 3], "Berries", true)))
        .await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    let stuck = in_free(&fruit_screen([false; 3], "Berries", true));
    assert_eq!(
        only_reads_until(&mut core, got, &stuck).await,
        ChatAnswer::Unconfirmed
    );
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn ticks_the_screen_does_not_show_stop_before_leaving_the_question() {
    let mut r = rig("q-multi-mismatch");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![0],
            text: None,
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let k = core.takes_keys(&["1"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    // The tick never shows: no Tab, no Enter, nothing again.
    let screen = fruit_screen([false; 3], "Type something", false);
    assert_eq!(
        only_reads_until(&mut core, got, &screen).await,
        ChatAnswer::Unconfirmed
    );
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_typed_answer_over_the_limit_or_nothing_at_all_sends_nothing() {
    let mut r = rig("q-multi-empty");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    for choice in [
        ChatChoice::Multi {
            indices: vec![],
            text: None,
        },
        ChatChoice::Multi {
            indices: vec![],
            text: Some("  ".into()),
        },
        ChatChoice::Multi {
            indices: vec![0],
            text: Some("x".repeat(2001)),
        },
    ] {
        let got = spawn_answer(&chat, "toolu_01", 0, choice);
        assert!(matches!(got.await.unwrap(), ChatAnswer::Failed { .. }));
    }
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn the_screen_leaving_the_question_before_the_text_stops_it() {
    let mut r = rig("q-multi-moved");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![0],
            text: Some("Berries".into()),
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let k = core.takes_keys(&["1"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    assert_eq!(
        only_reads_until(&mut core, got, PROMPT).await,
        ChatAnswer::Unconfirmed
    );
    core.nothing_more().await;
}

// ---- the phone keeps the answers until Send (design D11, owner 2026-11-25)

fn spawn_send(
    chat: &Arc<Chat>,
    id: &str,
    answers: Vec<ChatChoice>,
    from: u32,
) -> tokio::task::JoinHandle<ChatAnswer> {
    let chat = chat.clone();
    let id = id.to_owned();
    tokio::spawn(async move { chat.send_answers_async(id, answers, from).await })
}

/// [database] without its preview: digits alone, and a free-text row.
fn plain_db() -> Value {
    let mut v = database();
    v["options"][1].as_object_mut().unwrap().remove("preview");
    v
}

fn pick(index: u32) -> ChatChoice {
    ChatChoice::Pick { index }
}

/// Three single-choice questions: database, login, tests.
fn three_qs() -> Value {
    json!([plain_db(), login(), {"question": "Which tests first?", "header": "Tests", "multiSelect": false,
        "options": [{"label": "Unit", "description": "-"}, {"label": "End to end", "description": "-"}]}])
}

/// The dialog on question `at` (0-based) of [three_qs], earlier ones ticked.
fn three_screen(at: usize) -> String {
    let marks: Vec<String> = ["Database", "Login", "Tests"]
        .iter()
        .enumerate()
        .map(|(i, h)| format!("{} {h}", if i < at { "☒" } else { "☐" }))
        .collect();
    let (text, a, b) = match at {
        0 => (
            "Which database should the todo app use?",
            "SQLite (Recommended)",
            "Postgres",
        ),
        1 => ("Which login should it have?", "Password", "Passkey"),
        _ => ("Which tests first?", "Unit", "End to end"),
    };
    format!(
        "←  {}  ✔ Submit  →\n{text}\n❯ 1. {a}\n  2. {b}\n  3. Type something.\n",
        marks.join("  ")
    )
}

/// The dialog's review step listing these answers for [three_qs].
fn three_review(answers: [&str; 3]) -> String {
    format!(
        "←  ☒ Database  ☒ Login  ☒ Tests  ✔ Submit  →\nReview your answers\n ● Which database should the todo app use?\n   → {}\n ● Which login should it have?\n   → {}\n ● Which tests first?\n   → {}\n❯ 1. Submit answers\n  2. Cancel\n",
        answers[0], answers[1], answers[2]
    )
}

impl Core {
    /// One question typed: the check, the screen showing it, its keys, the
    /// screen after them.
    async fn types(&mut self, before: &str, keys: &[&str], after: &str) {
        self.serves_check("blocked").await;
        self.serves_screen(before).await;
        let k = self.takes_keys(keys).await;
        self.reply(&k, json!({"turn": 3})).await;
        self.serves_screen(after).await;
    }
}

#[tokio::test(start_paused = true)]
async fn send_types_each_answer_after_its_screen_then_enter_on_the_review() {
    let mut r = rig("send-all");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 0);
    core.types(&three_screen(0), &["2"], &three_screen(1)).await;
    core.types(&three_screen(1), &["2"], &three_screen(2)).await;
    let review = three_review(["Postgres", "Passkey", "Unit"]);
    core.types(&three_screen(2), &["1"], &review).await;
    // The dialog's review step lists what the phone typed: then Enter.
    core.serves_check("blocked").await;
    core.serves_screen(&review).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn sending_stops_halfway_and_says_how_many_were_typed() {
    let mut r = rig("send-halfway");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 0);
    core.types(&three_screen(0), &["2"], &three_screen(1)).await;
    core.types(&three_screen(1), &["2"], &three_screen(2)).await;
    // The third question's check finds another dialog: nothing more is sent.
    core.serves_check("blocked").await;
    core.serves_screen(OTHER).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Stopped { sent: 2 });
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_step_that_cannot_be_confirmed_stops_with_the_ones_before_it() {
    let mut r = rig("send-unconfirmed");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 0);
    core.types(&three_screen(0), &["2"], &three_screen(1)).await;
    core.serves_check("blocked").await;
    core.serves_screen(&three_screen(1)).await;
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    // The dialog never moves on from the second question.
    assert_eq!(
        only_reads_until(&mut core, got, &three_screen(1)).await,
        ChatAnswer::Stopped { sent: 1 }
    );
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn the_rest_goes_on_only_from_the_first_question_not_yet_typed() {
    let mut r = rig("send-rest");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    // The screen shows another question than the third: nothing is sent.
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 2);
    core.serves_check("blocked").await;
    core.serves_screen(&three_screen(1)).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
    // On the third question: only its answer, then the review's Enter.
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 2);
    let review = three_review(["Postgres", "Passkey", "Unit"]);
    core.types(&three_screen(2), &["1"], &review).await;
    core.serves_check("blocked").await;
    core.serves_screen(&review).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_first_send_needs_the_first_question_on_screen() {
    let mut r = rig("send-first");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 0);
    core.serves_check("blocked").await;
    core.serves_screen(&three_screen(1)).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn other_answers_on_the_review_step_stop_before_enter() {
    let mut r = rig("send-review-differs");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    let got = spawn_send(&chat, "toolu_01", vec![pick(1), pick(1), pick(0)], 0);
    core.types(&three_screen(0), &["2"], &three_screen(1)).await;
    core.types(&three_screen(1), &["2"], &three_screen(2)).await;
    let differs = three_review(["SQLite (Recommended)", "Passkey", "Unit"]);
    core.types(&three_screen(2), &["1"], &differs).await;
    core.serves_check("blocked").await;
    assert_eq!(
        only_reads_until(&mut core, got, &differs).await,
        ChatAnswer::Stopped { sent: 3 }
    );
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn one_multi_select_question_with_a_typed_answer_ends_on_its_review() {
    let mut r = rig("send-multi");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", json!([fruit()]))]).await;
    let got = spawn_send(
        &chat,
        "toolu_01",
        vec![ChatChoice::Multi {
            indices: vec![0],
            text: Some("Berries".into()),
        }],
        0,
    );
    core.serves_check("blocked").await;
    core.serves_screen(&fruit_screen([false; 3], "Type something", false))
        .await;
    let k = core.takes_keys(&["1"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&fruit_screen([true, false, false], "Type something", false))
        .await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "Berries");
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(&in_free(&fruit_screen(
        [true, false, false],
        "Berries",
        false,
    )))
    .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&in_free(&fruit_screen(
        [true, false, false],
        "Berries",
        true,
    )))
    .await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&on_submit(&in_free(&fruit_screen(
        [true, false, false],
        "Berries",
        true,
    ))))
    .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    let review = FRUIT_REVIEW.replace("Apple, Mango", "Apple, Berries");
    core.serves_screen(&review).await;
    core.serves_check("blocked").await;
    core.serves_screen(&review).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn own_words_and_a_note_are_typed_in_their_turn() {
    let mut r = rig("send-mixed");
    let layout = json!({"question": "Which layout?", "header": "Layout", "multiSelect": false,
        "options": [{"label": "Compact", "description": "-", "preview": "+--+"},
                    {"label": "Cards", "description": "-", "preview": "+----+"}]});
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([plain_db(), layout]))]).await;
    let got = spawn_send(
        &chat,
        "toolu_01",
        vec![
            ChatChoice::Other {
                text: "Through Docker".into(),
            },
            ChatChoice::Note {
                index: 1,
                text: "Bigger titles".into(),
            },
        ],
        0,
    );
    let first = "←  ☐ Database  ☐ Layout  ✔ Submit  →\nWhich database should the todo app use?\n❯ 1. SQLite (Recommended)\n  2. Postgres\n  3. Type something.\n";
    let first_free = first
        .replace("❯ 1.", "  1.")
        .replace("  3. Type something.", "❯ 3. Type something.");
    let second = "←  ☒ Database  ☐ Layout  ✔ Submit  →\nWhich layout?\n❯ 1. Compact      ┌──────┐\n  2. Cards        │ +--+ │\n                  Notes: press n to add notes\n";
    let second_on = second
        .replace("❯ 1.", "  1.")
        .replace("  2. Cards", "❯ 2. Cards");
    let notes = second_on.replace(
        "Notes: press n to add notes",
        "Notes: Add notes on this design…",
    );
    let review = "←  ☒ Database  ☒ Layout  ✔ Submit  →\nReview your answers\n ● Which database should the todo app use?\n   → Through Docker\n ● Which layout?\n   → Cards\n❯ 1. Submit answers\n  2. Cancel\n";
    core.serves_check("blocked").await;
    core.serves_screen(first).await;
    let k = core.takes_keys(&["3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&first_free).await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "Through Docker");
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(second).await;
    core.serves_check("blocked").await;
    core.serves_screen(second).await;
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&second_on).await;
    let k = core.takes_keys(&["n"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&notes).await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "Bigger titles");
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(review).await;
    core.serves_check("blocked").await;
    core.serves_screen(review).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn one_single_choice_question_has_no_review_to_wait_for() {
    let mut r = rig("send-one");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([plain_db()]))]).await;
    let plain = DIALOG;
    let got = spawn_send(&chat, "toolu_01", vec![pick(1)], 0);
    core.serves_check("blocked").await;
    core.serves_screen(plain).await;
    let k = core.takes_keys(&["2"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn answers_that_do_not_fit_the_call_send_nothing() {
    let mut r = rig("send-misfit");
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", three_qs())]).await;
    for (answers, from) in [
        (vec![pick(1), pick(1)], 0),
        (vec![pick(1), ChatChoice::Skip, pick(0)], 0),
        (vec![pick(1), pick(5), pick(0)], 0),
        (vec![pick(1), pick(1), pick(0)], 4),
    ] {
        let got = spawn_send(&chat, "toolu_01", answers, from);
        assert!(matches!(got.await.unwrap(), ChatAnswer::Failed { .. }));
    }
    let got = spawn_send(&chat, "toolu_02", vec![pick(1), pick(1), pick(0)], 0);
    assert_eq!(got.await.unwrap(), ChatAnswer::Changed);
    core.nothing_more().await;
}

// ---- Ezra's try (2026-11-25): a multi-select question before another one

fn lunch_call() -> Value {
    json!([
        {"question": "Where should we get lunch today?", "header": "Lunch", "multiSelect": false,
         "options": [{"label": "Bakery", "description": "-"}, {"label": "Noodle bar", "description": "-"},
                     {"label": "Leftovers", "description": "-"}]},
        {"question": "What should we bring back?", "header": "Extras", "multiSelect": true,
         "options": [{"label": "Coffee", "description": "Coffee"}, {"label": "Fruit", "description": "Fruit"},
                     {"label": "Cake", "description": "Cake"}]},
        {"question": "When do we leave?", "header": "When", "multiSelect": false,
         "options": [{"label": "Noon", "description": "-"}, {"label": "Half past twelve", "description": "-"},
                     {"label": "One o clock", "description": "-"}]}
    ])
}

const LUNCH: &str = "←  ☐ Lunch  ☐ Extras  ☐ When  ✔ Submit  →\nWhere should we get lunch today?\n❯ 1. Bakery\n  2. Noodle bar\n  3. Leftovers\n  4. Type something.\n";
const WHEN: &str = "←  ☒ Lunch  ☒ Extras  ☐ When  ✔ Submit  →\nWhen do we leave?\n❯ 1. Noon\n  2. Half past twelve\n  3. One o clock\n  4. Type something.\n";

/// The Extras question as the desk draws it: these ticks, the free row's
/// text and tick, and the focus on row `focus` (1-based) or, with 0, on the
/// unnumbered "Next" row (this question is not the call's last).
fn extras(ticks: [bool; 3], free: &str, free_on: bool, focus: usize) -> String {
    let b = |on: bool| if on { "[✔]" } else { "[ ]" };
    let p = |row: usize| if focus == row { "❯" } else { " " };
    format!(
        "←  ☒ Lunch  ☐ Extras  ☐ When  ✔ Submit  →\nWhat should we bring back?\n{} 1. {} Coffee\n         Coffee\n{} 2. {} Fruit\n         Fruit\n{} 3. {} Cake\n         Cake\n{} 4. {} {free}\n{}    Next\n  5. Chat about this\n",
        p(1),
        b(ticks[0]),
        p(2),
        b(ticks[1]),
        p(3),
        b(ticks[2]),
        p(4),
        b(free_on),
        p(0)
    )
}

const LUNCH_REVIEW: &str = "←  ☒ Lunch  ☒ Extras  ☒ When  ✔ Submit  →\nReview your answers\n ● Where should we get lunch today?\n   → Noodle bar\n ● What should we bring back?\n   → Coffee, Cake, tea\n ● When do we leave?\n   → Half past twelve\n❯ 1. Submit answers\n  2. Cancel\n";

fn lunch_answers() -> Vec<ChatChoice> {
    vec![
        pick(1),
        ChatChoice::Multi {
            indices: vec![0, 2],
            text: Some("tea".into()),
        },
        pick(1),
    ]
}

impl Core {
    /// The last question typed, then the review step and its Enter.
    async fn ends_on_when_and_review(&mut self, end: u64) {
        self.types(WHEN, &["2"], LUNCH_REVIEW).await;
        self.serves_check("blocked").await;
        self.serves_screen(LUNCH_REVIEW).await;
        let k = self.takes_keys(&["enter"]).await;
        self.reply(&k, json!({"turn": 3})).await;
        self.serves_screen(PROMPT).await;
        self.serves_log(end, &[result_line("toolu_01", false)])
            .await;
    }
}

#[tokio::test(start_paused = true)]
async fn a_multi_select_with_own_words_before_another_question_leaves_through_next() {
    let mut r = rig("send-lunch");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", lunch_call())]).await;
    let got = spawn_send(&chat, "toolu_01", lunch_answers(), 0);
    let fresh = extras([false; 3], "Type something", false, 1);
    core.types(LUNCH, &["2"], &fresh).await;
    core.serves_check("blocked").await;
    core.serves_screen(&fresh).await;
    let k = core.takes_keys(&["1", "3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&extras([true, false, true], "Type something", false, 1))
        .await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "tea");
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    // The paste leaves the text in the free row, unticked, in focus: Enter ticks it.
    core.serves_screen(&extras([true, false, true], "tea", false, 4))
        .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&extras([true, false, true], "tea", true, 4))
        .await;
    // From the editor Tab reaches the "Next" row; Enter there moves on.
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(&extras([true, false, true], "tea", true, 0))
        .await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(WHEN).await;
    core.ends_on_when_and_review(end).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_partly_typed_multi_select_goes_on_without_typing_it_again() {
    // Where Ezra's try stopped: the ticks and "tea" in, the focus on "Next".
    let mut r = rig("send-lunch-rest");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", lunch_call())]).await;
    let got = spawn_send(&chat, "toolu_01", lunch_answers(), 1);
    let stuck = extras([true, false, true], "tea", true, 0);
    core.serves_check("blocked").await;
    core.serves_screen(&stuck).await;
    // Nothing toggled, nothing pasted: the screen already shows them.
    core.serves_screen(&stuck).await;
    let k = core.takes_keys(&["enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(WHEN).await;
    core.ends_on_when_and_review(end).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_multi_select_revisited_with_its_answer_in_place_leaves_with_tab() {
    // Back on the question at the desk, the focus starts on row 1 (desk, 2.1.332).
    let mut r = rig("send-lunch-revisit");
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", lunch_call())]).await;
    let got = spawn_send(&chat, "toolu_01", lunch_answers(), 1);
    let back = extras([true, false, true], "tea", true, 1);
    core.serves_check("blocked").await;
    core.serves_screen(&back).await;
    core.serves_screen(&back).await;
    let k = core.takes_keys(&["tab"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(WHEN).await;
    core.ends_on_when_and_review(end).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

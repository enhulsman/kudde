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
│ Review your answers           │
│ ❯ 1. Submit answers           │
│   2. Cancel                   │
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

/// A chat on a connected desk that holds `lines` of the log.
async fn with_log(r: &mut Rig, lines: &[String]) -> (Core, Arc<Chat>, u64) {
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
    core.serves_screen(DIALOG).await;
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
    let k = core.takes_keys(&["2", "enter"]).await;
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
async fn multi_select_is_not_sent_until_confirmed() {
    let mut r = rig("q-multi");
    let mut v = database();
    v["multiSelect"] = json!(true);
    let (mut core, chat, _) = with_log(&mut r, &[ask_line("toolu_01", json!([v]))]).await;
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

// ---- the card in the poll (design D1)

async fn poll(
    core: &mut Core,
    chat: &Arc<Chat>,
    status: &str,
    end: u64,
    screen: Option<&str>,
) -> crate::hub::chat::ChatPoll {
    let c = chat.clone();
    let got = tokio::spawn(async move { c.poll_for_test().await });
    let req = core.expect("terminal.get").await;
    core.reply(&req, terminal(status)).await;
    core.serves_log(end, &[]).await;
    if let Some(s) = screen {
        core.serves_screen(s).await;
    }
    got.await.unwrap()
}

#[tokio::test(start_paused = true)]
async fn a_blocked_agent_with_a_pending_call_shows_the_card() {
    let mut r = rig("q-card");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    let p = poll(&mut core, &chat, "blocked", end, None).await;
    let q = p.question.expect("a card");
    assert_eq!(q.id, "toolu_01");
    assert!(q.readable);
    assert_eq!(q.step, 0);
    assert!(!q.review);
    assert_eq!(q.questions.len(), 1);
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
}

#[tokio::test(start_paused = true)]
async fn no_card_unless_blocked() {
    let mut r = rig("q-nocard");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([database()]))]).await;
    for status in ["idle", "working", "done"] {
        let p = poll(&mut core, &chat, status, end, None).await;
        assert!(p.question.is_none(), "{status}");
    }
}

#[tokio::test(start_paused = true)]
async fn several_questions_follow_the_screen() {
    let mut r = rig("q-follow");
    let (mut core, chat, end) = with_log(
        &mut r,
        &[ask_line("toolu_01", json!([database(), login()]))],
    )
    .await;
    let p = poll(&mut core, &chat, "blocked", end, Some(LOGIN)).await;
    let q = p.question.unwrap();
    assert_eq!((q.step, q.review), (1, false));
    let p = poll(&mut core, &chat, "blocked", end, Some(REVIEW)).await;
    let q = p.question.unwrap();
    assert_eq!((q.step, q.review), (2, true));
    // A screen the card does not know keeps the step it had.
    let p = poll(&mut core, &chat, "blocked", end, Some(OTHER)).await;
    let q = p.question.unwrap();
    assert_eq!((q.step, q.review), (2, true));
}

#[tokio::test(start_paused = true)]
async fn an_unreadable_call_shows_its_text_only_and_multi_select_is_not_answerable() {
    let mut r = rig("q-unreadable");
    let bad = json!([{"question": "Which database?", "header": "Database", "multiSelect": false,
                      "options": [{"description": "no label"}, {"label": "Postgres"}]}]);
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_01", bad)]).await;
    let q = poll(&mut core, &chat, "blocked", end, None)
        .await
        .question
        .unwrap();
    assert!(!q.readable);
    assert_eq!(q.texts, vec!["Which database?".to_string()]);
    let mut r = rig("q-multi-card");
    let mut v = database();
    v["multiSelect"] = json!(true);
    let (mut core, chat, end) = with_log(&mut r, &[ask_line("toolu_02", json!([v]))]).await;
    let q = poll(&mut core, &chat, "blocked", end, None)
        .await
        .question
        .unwrap();
    assert!(q.readable);
    assert!(q.questions[0].multi);
    assert!(!q.questions[0].answerable);
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

// Owner, 2026-11-25: on a multi-select question a typed answer joins the
// ticks and goes out with the one send. The production gate stays shut until
// the phone check; these tests open it.

fn multi_database() -> Value {
    let mut v = database();
    v["multiSelect"] = json!(true);
    v
}

#[tokio::test(start_paused = true)]
async fn ticks_alone_go_as_one_keys_call() {
    let mut r = rig("q-multi-ticks");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([multi_database()]))]).await;
    chat.allow_multi_for_test();
    let got = spawn_answer(
        &chat,
        "toolu_01",
        0,
        ChatChoice::Multi {
            indices: vec![1, 0],
            text: None,
        },
    );
    core.serves_check("blocked").await;
    core.serves_screen(DIALOG).await;
    let k = core
        .takes_keys(&["1", "enter", "2", "enter", "4", "enter"])
        .await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_typed_answer_goes_out_with_the_ticks() {
    let mut r = rig("q-multi-text");
    let (mut core, chat, end) =
        with_log(&mut r, &[ask_line("toolu_01", json!([multi_database()]))]).await;
    chat.allow_multi_for_test();
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
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["1", "enter", "3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(DIALOG).await;
    let send = core.expect("terminal.send").await;
    assert_eq!(send["params"]["text"], "Berries");
    assert_eq!(send["params"]["force"], true);
    core.reply(&send, json!({"turn": 3, "status": "blocked"}))
        .await;
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["4", "enter"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    core.serves_log(end, &[result_line("toolu_01", false)])
        .await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Done);
    core.nothing_more().await;
}

#[tokio::test(start_paused = true)]
async fn a_typed_answer_over_the_limit_or_nothing_at_all_sends_nothing() {
    let mut r = rig("q-multi-empty");
    let (mut core, chat, _) =
        with_log(&mut r, &[ask_line("toolu_01", json!([multi_database()]))]).await;
    chat.allow_multi_for_test();
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
    let (mut core, chat, _) =
        with_log(&mut r, &[ask_line("toolu_01", json!([multi_database()]))]).await;
    chat.allow_multi_for_test();
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
    core.serves_screen(DIALOG).await;
    let k = core.takes_keys(&["1", "enter", "3"]).await;
    core.reply(&k, json!({"turn": 3})).await;
    core.serves_screen(PROMPT).await;
    assert_eq!(got.await.unwrap(), ChatAnswer::Unconfirmed);
    core.nothing_more().await;
}

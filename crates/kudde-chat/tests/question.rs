//! Answering Claude Code's AskUserQuestion from the chat (chat-questions
//! tasks 2.1 to 2.5): the call as the log holds it, whether it is still
//! open, the keys that answer it and what the screen says about it. The
//! records are invented in the shapes of design section A.

use kudde_chat::question::{
    self, Answer, Asked, Choice, Progress, Question, Refusal, Shape, State,
};
use kudde_chat::{Agent, Item, Parser};
use serde_json::{Value, json};

const SESSION: &str = "11111111-1111-4111-8111-111111111111";

fn line(v: Value) -> String {
    format!("{v}\n")
}

fn ask(id: &str, questions: Value) -> String {
    line(json!({
        "type": "assistant", "isSidechain": false, "sessionId": SESSION,
        "message": {"id": format!("msg_{id}"), "role": "assistant", "content": [
            {"type": "tool_use", "id": id, "name": "AskUserQuestion",
             "input": {"questions": questions}}
        ]}
    }))
}

fn result(id: &str, text: &str, is_error: bool) -> String {
    line(json!({
        "type": "user", "isSidechain": false, "sessionId": SESSION,
        "message": {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": id, "content": text, "is_error": is_error}
        ]},
        "toolUseResult": {"answers": {"Which database should the todo app use?": "Postgres"}}
    }))
}

fn user(text: &str) -> String {
    line(json!({
        "type": "user", "isSidechain": false, "sessionId": SESSION,
        "message": {"role": "user", "content": text}
    }))
}

fn read(id: &str) -> String {
    line(json!({
        "type": "assistant", "isSidechain": false, "sessionId": SESSION,
        "message": {"id": format!("msg_{id}"), "role": "assistant", "content": [
            {"type": "tool_use", "id": id, "name": "Read", "input": {"file_path": "/work/website/notes.md"}}
        ]}
    }))
}

fn database() -> Value {
    json!({
        "question": "Which database should the todo app use?", "header": "Database",
        "multiSelect": false,
        "options": [
            {"label": "SQLite (Recommended)", "description": "One file, no server."},
            {"label": "Postgres", "description": "Needs a running server.",
             "preview": "services:\n  db:\n    image: postgres"}
        ]
    })
}

fn q(text: &str, header: &str, labels: &[&str]) -> Value {
    json!({
        "question": text, "header": header, "multiSelect": false,
        "options": labels.iter().map(|l| json!({"label": l, "description": format!("About {l}.")})).collect::<Vec<_>>()
    })
}

fn parse(text: &str) -> Parser {
    let mut p = Parser::new(Agent::Claude);
    p.feed(text);
    p
}

fn readable(v: Value) -> Vec<Question> {
    match question::read_input(&json!({"questions": v})) {
        Shape::Readable(qs) => qs,
        other => panic!("readable expected, got {other:?}"),
    }
}

fn unreadable(v: Value) -> Vec<String> {
    match question::read_input(&v) {
        Shape::Unreadable { texts } => texts,
        other => panic!("unreadable expected, got {other:?}"),
    }
}

// ---- 2.1 parse

#[test]
fn a_single_question_is_read_whole() {
    let qs = readable(json!([database()]));
    assert_eq!(
        qs,
        vec![Question {
            text: "Which database should the todo app use?".into(),
            header: "Database".into(),
            multi: false,
            options: vec![
                Choice {
                    label: "SQLite (Recommended)".into(),
                    description: "One file, no server.".into(),
                    preview: None,
                },
                Choice {
                    label: "Postgres".into(),
                    description: "Needs a running server.".into(),
                    preview: Some("services:\n  db:\n    image: postgres".into()),
                },
            ],
        }]
    );
}

#[test]
fn four_questions_are_read_in_order() {
    let qs = readable(json!([
        q("Which database?", "Database", &["SQLite", "Postgres"]),
        q("Which login?", "Login", &["Password", "Passkey"]),
        q("Which host?", "Host", &["Desk", "Cloud", "Both"]),
        q("Which tests?", "Tests", &["Unit", "End to end"]),
    ]));
    assert_eq!(
        qs.iter().map(|q| q.header.as_str()).collect::<Vec<_>>(),
        ["Database", "Login", "Host", "Tests"]
    );
    assert_eq!(qs[2].options.len(), 3);
}

#[test]
fn a_multi_select_question_is_marked() {
    let mut v = q("Which parts?", "Parts", &["A", "B", "C"]);
    v["multiSelect"] = json!(true);
    assert!(readable(json!([v]))[0].multi);
}

#[test]
fn unknown_option_fields_are_ignored() {
    let qs = readable(json!([{
        "question": "Which database?", "header": "Database", "multiSelect": false,
        "options": [
            {"label": "SQLite", "description": "One file.", "options_note": "x"},
            {"label": "Postgres", "description": "A server.", "description_detail": {"a": 1}}
        ],
        "somethingNew": true
    }]));
    assert_eq!(qs[0].options[1].label, "Postgres");
    assert_eq!(qs[0].options[1].preview, None);
}

#[test]
fn long_labels_and_descriptions_are_never_cut() {
    let label = "L".repeat(71);
    let description = "d".repeat(698);
    let qs = readable(json!([{
        "question": "Which one?", "header": "Pick", "multiSelect": false,
        "options": [{"label": label, "description": description}, {"label": "Other", "description": ""}]
    }]));
    assert_eq!(qs[0].options[0].label.chars().count(), 71);
    assert_eq!(qs[0].options[0].description.chars().count(), 698);
}

#[test]
fn shapes_the_card_cannot_take_keep_their_question_texts() {
    // No questions at all.
    assert_eq!(unreadable(json!({"questions": []})), Vec::<String>::new());
    assert_eq!(unreadable(json!({})), Vec::<String>::new());
    // An option without a label.
    assert_eq!(
        unreadable(json!({"questions": [{
            "question": "Which database?", "header": "Database", "multiSelect": false,
            "options": [{"description": "No label."}, {"label": "Postgres", "description": ""}]
        }]})),
        vec!["Which database?".to_string()]
    );
    // More than four questions.
    let five: Vec<Value> = (1..=5)
        .map(|i| q(&format!("Question {i}?"), "H", &["A", "B"]))
        .collect();
    assert_eq!(unreadable(json!({ "questions": five })).len(), 5);
    // More than four options.
    assert_eq!(
        unreadable(json!({"questions": [q("Which?", "H", &["A", "B", "C", "D", "E"])]})),
        vec!["Which?".to_string()]
    );
    // A question without its text.
    assert!(matches!(
        question::read_input(&json!({"questions": [{"header": "H", "options": []}]})),
        Shape::Unreadable { .. }
    ));
}

// ---- 2.2 pending state

#[test]
fn a_call_with_no_result_is_pending_and_a_result_closes_it() {
    let mut p = parse(&ask("toolu_01", json!([database()])));
    let pending = p.question().expect("pending");
    assert_eq!(pending.id, "toolu_01");
    assert_eq!(pending.state, State::Pending);
    assert!(matches!(pending.shape, Shape::Readable(ref qs) if qs.len() == 1));
    p.feed(&result(
        "toolu_01",
        "Your questions have been answered: \"Which database should the todo app use?\"=\"Postgres\"",
        false,
    ));
    assert!(p.question().is_none());
    assert_eq!(p.asked("toolu_01").map(|a| a.state), Some(State::Answered));
}

#[test]
fn an_error_result_marks_the_call_declined() {
    let p = parse(&format!(
        "{}{}",
        ask("toolu_01", json!([database()])),
        result(
            "toolu_01",
            "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed.",
            true
        )
    ));
    assert!(p.question().is_none());
    assert_eq!(p.asked("toolu_01").map(|a| a.state), Some(State::Declined));
}

#[test]
fn the_result_text_is_not_parsed_in_either_wording() {
    for text in [
        "Your questions have been answered: \"Which database should the todo app use?\"=\"Postgres\"",
        "The user answered: \"Which database should the todo app use?\"=\"Something else entirely\". Read the answers carefully.",
        "",
    ] {
        let p = parse(&format!(
            "{}{}",
            ask("toolu_01", json!([database()])),
            result("toolu_01", text, false)
        ));
        let a: &Asked = p.asked("toolu_01").unwrap();
        assert_eq!(a.state, State::Answered, "{text}");
        // Nothing of the answer is shown from the result text.
        let Some(Item::Tools { calls }) = p.items().last() else {
            panic!("a tools row")
        };
        assert_eq!(calls[0].title, "Answered: Database");
    }
}

#[test]
fn the_folded_line_says_answered_or_skipped() {
    let p = parse(&format!(
        "{}{}{}{}",
        ask("toolu_01", json!([database()])),
        result("toolu_01", "x", false),
        ask("toolu_02", json!([database()])),
        result("toolu_02", "rejected", true),
    ));
    let titles: Vec<String> = p
        .items()
        .iter()
        .filter_map(|i| match i {
            Item::Tools { calls } => Some(calls[0].title.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(titles, ["Answered: Database", "Skipped: Database"]);
}

#[test]
fn a_question_call_is_a_row_of_its_own() {
    let p = parse(&format!(
        "{}{}{}",
        read("toolu_r1"),
        ask("toolu_01", json!([database()])),
        read("toolu_r2"),
    ));
    let rows: Vec<Vec<String>> = p
        .items()
        .iter()
        .map(|i| match i {
            Item::Tools { calls } => calls.iter().map(|c| c.name.clone()).collect(),
            other => panic!("tools only, got {other:?}"),
        })
        .collect();
    assert_eq!(
        rows,
        [
            vec!["Read".to_string()],
            vec!["AskUserQuestion".into()],
            vec!["Read".into()]
        ]
    );
}

#[test]
fn a_call_with_no_result_at_the_end_of_the_log_stays_pending() {
    let p = parse(&format!(
        "{}{}{}",
        user("Plan the todo app"),
        ask("toolu_01", json!([database()])),
        // A parallel call written after it does not end it.
        read("toolu_r1"),
    ));
    assert_eq!(p.question().map(|a| a.id.as_str()), Some("toolu_01"));
}

#[test]
fn a_call_left_open_before_a_new_message_is_not_pending() {
    // The session ended with the dialog open and went on with a new message.
    let p = parse(&format!(
        "{}{}",
        ask("toolu_01", json!([database()])),
        user("Never mind, use SQLite"),
    ));
    assert!(p.question().is_none());
    assert_eq!(p.asked("toolu_01").map(|a| a.state), Some(State::Pending));
}

#[test]
fn only_the_latest_call_is_pending() {
    let p = parse(&format!(
        "{}{}",
        ask("toolu_01", json!([database()])),
        ask(
            "toolu_02",
            json!([q("Which login?", "Login", &["Password", "Passkey"])])
        ),
    ));
    assert_eq!(p.question().map(|a| a.id.as_str()), Some("toolu_02"));
}

#[test]
fn a_subagents_question_is_not_the_chats() {
    let mut v: Value = serde_json::from_str(&ask("toolu_01", json!([database()]))).unwrap();
    v["isSidechain"] = json!(true);
    let p = parse(&line(v));
    assert!(p.question().is_none());
}

#[test]
fn codex_has_no_question_calls() {
    let mut p = Parser::new(Agent::Codex);
    p.feed(&ask("toolu_01", json!([database()])));
    assert!(p.question().is_none());
}

// ---- 2.3 key plan

fn keys(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| s.to_string()).collect()
}

// Phone round 5.1 (desk, Claude Code 2.1.331): without previews a digit
// alone answers and moves on; with previews it only moves the focus.

#[test]
fn option_n_is_its_digit_alone_without_previews() {
    let qs = readable(json!([q(
        "Which login?",
        "Login",
        &["None", "Passkeys", "Email link"]
    )]));
    assert_eq!(
        question::plan(&qs[0], &Answer::Option(0)),
        Some(keys(&["1"]))
    );
    assert_eq!(
        question::plan(&qs[0], &Answer::Option(2)),
        Some(keys(&["3"]))
    );
    assert_eq!(question::plan(&qs[0], &Answer::Option(3)), None);
}

#[test]
fn with_previews_option_n_is_its_digit_and_enter() {
    let qs = readable(json!([database()]));
    assert_eq!(
        question::plan(&qs[0], &Answer::Option(0)),
        Some(keys(&["1", "enter"]))
    );
    assert_eq!(
        question::plan(&qs[0], &Answer::Option(1)),
        Some(keys(&["2", "enter"]))
    );
    assert_eq!(question::plan(&qs[0], &Answer::Option(2)), None);
}

/// Regression (5.1): [2, enter] on the first of three questions without
/// previews also answered the second ("Login" = "None"). No plan for an
/// option of such a question holds more than its digit.
#[test]
fn an_option_never_carries_a_key_into_the_next_question() {
    let qs = readable(json!([
        q(
            "Which database?",
            "Database",
            &["SQLite", "Postgres", "JSON files"]
        ),
        q("Which login?", "Login", &["None", "Passkeys", "Email link"]),
        q("Where to host it?", "Hosting", &["Laptop", "VPS", "Cloud"]),
    ]));
    for (s, q) in qs.iter().enumerate() {
        for i in 0..3 {
            let k = question::plan(q, &Answer::Option(i)).unwrap();
            assert_eq!(k, vec![(i + 1).to_string()], "question {s}, option {i}");
        }
    }
}

#[test]
fn something_else_focuses_the_row_after_the_options() {
    let qs = readable(json!([database()]));
    assert_eq!(question::plan(&qs[0], &Answer::Other), Some(keys(&["3"])));
    let four = readable(json!([q("Which?", "H", &["A", "B", "C", "D"])]));
    assert_eq!(question::plan(&four[0], &Answer::Other), Some(keys(&["5"])));
}

#[test]
fn skip_is_esc_and_send_answers_is_enter() {
    let qs = readable(json!([database()]));
    assert_eq!(question::plan(&qs[0], &Answer::Skip), Some(keys(&["esc"])));
    assert_eq!(
        question::plan(&qs[0], &Answer::Submit),
        Some(keys(&["enter"]))
    );
}

#[test]
fn multi_select_goes_through_its_own_plan() {
    let mut v = q("Which parts?", "Parts", &["A", "B", "C"]);
    v["multiSelect"] = json!(true);
    let qs = readable(json!([v]));
    // Ticks need screen checks between their keys ([question::plan_multi]).
    assert_eq!(question::plan(&qs[0], &Answer::Multi(vec![0])), None);
    // Not for a single-choice question, nor an option tap for a multi-select one.
    let single = readable(json!([database()]));
    assert_eq!(question::plan(&single[0], &Answer::Multi(vec![0])), None);
    assert_eq!(question::plan(&qs[0], &Answer::Option(0)), None);
}

#[test]
fn a_plan_never_holds_an_arrow_or_a_digit_above_nine() {
    let mut multi = q("Which parts?", "Parts", &["A", "B", "C", "D"]);
    multi["multiSelect"] = json!(true);
    let qs = readable(json!([q("Which?", "H", &["A", "B", "C", "D"]), multi]));
    let mut all = Vec::new();
    for i in 0..4 {
        all.extend(question::plan(&qs[0], &Answer::Option(i)).unwrap());
    }
    all.extend(question::plan(&qs[0], &Answer::Other).unwrap());
    all.extend(question::plan(&qs[0], &Answer::Skip).unwrap());
    all.extend(question::plan(&qs[0], &Answer::Submit).unwrap());
    let m = question::plan_multi(&qs[1], &[0, 1, 2, 3], true).unwrap();
    all.extend(m.ticks);
    all.extend(m.next);
    for k in &all {
        assert!(
            ["enter", "esc", "tab"].contains(&k.as_str())
                || (k.len() == 1 && ('1'..='9').contains(&k.chars().next().unwrap())),
            "unexpected key {k}"
        );
    }
}

// ---- 2.4 screen match

const DIALOG: &str = "\
╭──────────────────────────────────────────────╮
│ ☐ Database                                   │
│                                              │
│ Which database should the todo app use?      │
│                                              │
│ ❯ 1. SQLite (Recommended)                    │
│      One file, no server.                    │
│   2. Postgres                                │
│      Needs a running server.                 │
│   3. Type something.                         │
│ ──────────────────────────────────────────── │
│   Chat about this                            │
╰──────────────────────────────────────────────╯
";

#[test]
fn the_question_is_found_through_box_characters_and_spaces() {
    let qs = readable(json!([database()]));
    assert!(question::shows(DIALOG, &qs[0]));
    assert_eq!(question::progress(DIALOG, &qs), Progress::Question(0));
}

#[test]
fn a_different_question_is_not_found() {
    let qs = readable(json!([q(
        "Which cache should the todo app use?",
        "Cache",
        &["SQLite (Recommended)", "Postgres"]
    )]));
    assert!(!question::shows(DIALOG, &qs[0]));
    assert_eq!(question::progress(DIALOG, &qs), Progress::Gone);
}

#[test]
fn a_missing_option_label_is_not_a_match() {
    let qs = readable(json!([q(
        "Which database should the todo app use?",
        "Database",
        &["SQLite (Recommended)", "MySQL"]
    )]));
    assert!(!question::shows(DIALOG, &qs[0]));
}

#[test]
fn a_wrapped_question_is_found() {
    let screen = "\
│ Which database should the todo │
│ app use?                       │
│ ❯ 1. SQLite (Recommended)      │
│   2. Postgres                  │
";
    let qs = readable(json!([database()]));
    assert!(question::shows(screen, &qs[0]));
}

#[test]
fn of_two_similar_questions_the_one_on_screen_wins() {
    let qs = readable(json!([
        q("Which database?", "Database", &["SQLite", "Postgres"]),
        q(
            "Which database for the tests?",
            "Tests",
            &["SQLite", "Postgres"]
        ),
    ]));
    let second = "\
│ ☒ Database  ☐ Tests  ✔ Submit │
│ Which database for the tests? │
│ ❯ 1. SQLite                   │
│   2. Postgres                 │
";
    assert_eq!(question::progress(second, &qs), Progress::Question(1));
    let first = "\
│ ☐ Database  ☐ Tests  ✔ Submit │
│ Which database?               │
│ ❯ 1. SQLite                   │
│   2. Postgres                 │
";
    assert_eq!(question::progress(first, &qs), Progress::Question(0));
}

// ---- 2.5 progress

#[test]
fn progress_reads_next_question_review_or_gone() {
    let qs = readable(json!([
        q("Which database?", "Database", &["SQLite", "Postgres"]),
        q("Which login?", "Login", &["Password", "Passkey"]),
    ]));
    let next = "│ Which login? │\n│ ❯ 1. Password │\n│ 2. Passkey │\n";
    assert_eq!(question::progress(next, &qs), Progress::Question(1));
    let review = "\
│ Review your answers                     │
│  ● Which database?                      │
│    → Postgres                           │
│  ● Which login?                         │
│    → Passkey                            │
│ Ready to submit your answers?           │
│ ❯ 1. Submit answers                     │
│   2. Cancel                             │
";
    assert_eq!(question::progress(review, &qs), Progress::Review);
    let gone = "⏺ Using Postgres and passkeys.\n\n> \n";
    assert_eq!(question::progress(gone, &qs), Progress::Gone);
}

#[test]
fn a_single_multi_select_question_has_a_review_step() {
    let mut v = q("Which fruits?", "Fruit", &["Apple", "Banana", "Mango"]);
    v["multiSelect"] = json!(true);
    let qs = readable(json!([v]));
    let review = "\
←  ☒ Fruit  ✔ Submit  →
Review your answers
 ● Which fruits?
   → Apple, Mango
❯ 1. Submit answers
  2. Cancel
";
    assert_eq!(question::progress(review, &qs), Progress::Review);
}

#[test]
fn a_single_question_has_no_review_step() {
    let qs = readable(json!([q(
        "Shall I submit answers to the form?",
        "Form",
        &["Submit answers", "Wait"]
    )]));
    let screen = "│ Shall I submit answers to the form? │\n│ ❯ 1. Submit answers │\n│ 2. Wait │\n";
    assert_eq!(question::progress(screen, &qs), Progress::Question(0));
}

// ---- the check before an answer (design D2, decision 8)

mod check {
    use super::*;
    use kudde_chat::locate::Lookup;
    use kudde_chat::send::Seen;

    fn found(session: &str) -> Lookup {
        Lookup::Found {
            path: format!("/u/.claude/projects/-work-website/{session}.jsonl"),
            session: session.into(),
        }
    }

    fn seen<'a>(status: Option<&'a str>, lookup: &'a Lookup) -> Seen<'a> {
        Seen {
            agent: Some(Agent::Claude),
            pid: Some(4242),
            proc_start: Some(9001),
            status,
            lookup,
        }
    }

    #[test]
    fn only_a_blocked_agent_of_the_same_session_is_answered() {
        let l = found(SESSION);
        let t = question::check(Agent::Claude, SESSION, &seen(Some("blocked"), &l)).unwrap();
        assert_eq!(t.pid, 4242);
        assert_eq!(t.session, SESSION);
        for st in [Some("working"), Some("idle"), Some("done"), None] {
            assert_eq!(
                question::check(Agent::Claude, SESSION, &seen(st, &l)),
                Err(Refusal::Answered),
                "{st:?}"
            );
        }
    }

    #[test]
    fn another_session_agent_or_none_is_changed() {
        let other = found("bbbbbbbb-1111-4111-8111-111111111111");
        assert_eq!(
            question::check(Agent::Claude, SESSION, &seen(Some("blocked"), &other)),
            Err(Refusal::Changed)
        );
        let l = found(SESSION);
        assert_eq!(
            question::check(Agent::Codex, SESSION, &seen(Some("blocked"), &l)),
            Err(Refusal::Changed)
        );
        let mut gone = seen(Some("blocked"), &l);
        gone.agent = None;
        gone.pid = None;
        assert_eq!(
            question::check(Agent::Claude, SESSION, &gone),
            Err(Refusal::Changed)
        );
        let missed = Lookup::Missed("no log");
        assert_eq!(
            question::check(Agent::Claude, SESSION, &seen(Some("blocked"), &missed)),
            Err(Refusal::Changed)
        );
    }
}

// ---- the screen before and after a step (design D4)

mod steps {
    use super::*;
    use kudde_chat::question::{Moved, before, moved_on};

    #[test]
    fn a_step_starts_only_where_the_screen_is() {
        let q = |i| Progress::Question(i);
        assert!(before(&Answer::Option(1), 0, q(0)));
        assert!(!before(&Answer::Option(1), 0, q(1)));
        assert!(!before(&Answer::Option(1), 0, Progress::Review));
        assert!(before(&Answer::Other, 1, q(1)));
        assert!(!before(&Answer::Other, 1, Progress::Gone));
        assert!(before(&Answer::Submit, 2, Progress::Review));
        assert!(!before(&Answer::Submit, 2, q(1)));
        assert!(before(&Answer::Skip, 0, q(1)));
        assert!(before(&Answer::Skip, 0, Progress::Review));
        assert!(!before(&Answer::Skip, 0, Progress::Gone));
    }

    #[test]
    fn a_step_is_done_when_the_screen_moves_on() {
        let q = |i| Progress::Question(i);
        assert_eq!(moved_on(&Answer::Option(0), 0, q(1)), Moved::Yes);
        assert_eq!(
            moved_on(&Answer::Option(0), 1, Progress::Review),
            Moved::Yes
        );
        assert_eq!(moved_on(&Answer::Option(0), 0, q(0)), Moved::No);
        assert_eq!(
            moved_on(&Answer::Option(0), 0, Progress::Gone),
            Moved::AskLog
        );
        assert_eq!(moved_on(&Answer::Other, 0, Progress::Gone), Moved::AskLog);
        assert_eq!(moved_on(&Answer::Submit, 2, Progress::Review), Moved::No);
        assert_eq!(moved_on(&Answer::Submit, 2, Progress::Gone), Moved::AskLog);
        assert_eq!(moved_on(&Answer::Skip, 0, q(0)), Moved::No);
        assert_eq!(moved_on(&Answer::Skip, 0, Progress::Gone), Moved::AskLog);
    }
}

// ---- multi-select (owner, 2026-11-25: a typed answer joins the ticks and
// goes out with the one send). Keys from the 5.1 desk round: a digit toggles
// its row in place (Enter on a row toggles it too); the typed text pasted
// with Enter lands in the free row unticked, and one more Enter ticks it;
// Tab leaves the question (from the free row's editor: Tab to Submit, then
// Enter). The hub checks the screen between these steps.

mod multi {
    use super::*;
    use kudde_chat::question::{MultiPlan, plan_multi, row_ticked, ticks_shown, typed_row};

    fn three() -> Question {
        let mut v = q("Which fruits?", "Fruit", &["Apple", "Banana", "Mango"]);
        v["multiSelect"] = json!(true);
        readable(json!([v])).remove(0)
    }

    const TICKED: &str = "\
←  ☒ Fruit  ✔ Submit  →
Which fruits?
  1. [✔] Apple
         Crisp.
  2. [ ] Banana
         Soft.
  3. [✔] Mango
         Juicy.
❯ 4. [✔] Berries
     Submit
  5. Chat about this
";

    #[test]
    fn ticks_are_digits_alone_then_tab() {
        assert_eq!(
            plan_multi(&three(), &[2, 0, 2], false),
            Some(MultiPlan {
                ticks: keys(&["1", "3"]),
                next: keys(&["tab"]),
            })
        );
    }

    #[test]
    fn with_a_typed_answer_tab_reaches_submit_from_the_editor() {
        assert_eq!(
            plan_multi(&three(), &[0], true),
            Some(MultiPlan {
                ticks: keys(&["1"]),
                next: keys(&["tab", "enter"]),
            })
        );
        assert_eq!(
            plan_multi(&three(), &[], true),
            Some(MultiPlan {
                ticks: vec![],
                next: keys(&["tab", "enter"]),
            })
        );
    }

    #[test]
    fn no_digit_ever_names_the_free_row_or_chat_about_this() {
        let m = plan_multi(&three(), &[0, 1, 2], true).unwrap();
        assert!(!m.ticks.contains(&"4".to_string()));
        assert!(!m.ticks.contains(&"5".to_string()));
        assert!(!m.next.iter().any(|k| k.chars().all(|c| c.is_ascii_digit())));
    }

    #[test]
    fn nothing_or_out_of_range_or_single_choice_is_no_plan() {
        let q = three();
        assert_eq!(plan_multi(&q, &[], false), None);
        assert_eq!(plan_multi(&q, &[3], true), None);
        let single = readable(json!([database()])).remove(0);
        assert_eq!(plan_multi(&single, &[0], true), None);
        assert_eq!(question::plan(&q, &Answer::Other), None);
    }

    #[test]
    fn a_row_reads_ticked_or_not_from_the_screen() {
        assert_eq!(row_ticked(TICKED, 1), Some(true));
        assert_eq!(row_ticked(TICKED, 2), Some(false));
        assert_eq!(row_ticked(TICKED, 4), Some(true));
        assert_eq!(row_ticked(TICKED, 5), None);
        assert_eq!(row_ticked("> hello\n", 1), None);
    }

    #[test]
    fn the_dialog_at_the_bottom_wins_over_older_text() {
        let screen = format!("● Earlier: 2. [✔] Banana\n{TICKED}");
        assert_eq!(row_ticked(&screen, 2), Some(false));
    }

    #[test]
    fn the_free_row_is_read_once_it_holds_the_text() {
        let q = three();
        assert_eq!(typed_row(TICKED, &q, "Berries"), Some(true));
        let unticked = TICKED.replace("4. [✔] Berries", "4. [ ] Berries");
        assert_eq!(typed_row(&unticked, &q, "Berries"), Some(false));
        // Not there yet: the row still says "Type something".
        let before = TICKED.replace("4. [✔] Berries", "4. [ ] Type something");
        assert_eq!(typed_row(&before, &q, "Berries"), None);
        // A long answer is matched on its start.
        let long = "Berries and a spoon of yoghurt, only if it is plain and cold";
        let wrapped = TICKED.replace(
            "Berries",
            "Berries and a spoon of yoghurt, only\n   if it is plain and cold",
        );
        assert_eq!(typed_row(&wrapped, &q, long), Some(true));
    }

    #[test]
    fn the_screen_must_show_exactly_the_planned_ticks() {
        let q = three();
        assert!(ticks_shown(TICKED, &q, &[0, 2], true));
        assert!(ticks_shown(TICKED, &q, &[2, 0], true));
        assert!(!ticks_shown(TICKED, &q, &[0, 2], false));
        assert!(!ticks_shown(TICKED, &q, &[0], true));
        assert!(!ticks_shown(TICKED, &q, &[0, 1, 2], true));
        assert!(!ticks_shown("> hello\n", &q, &[0], false));
    }
}

//! Claude Code's multiple-choice question (AskUserQuestion) answered from
//! the chat (chat-questions design D1 to D4, D10): the call as the log holds
//! it, the keys that answer one step of it, and what the terminal's screen
//! says about it. No I/O: the hub makes the calls.
//!
//! The keys are the dialog's as the 5.1 desk round found them (design
//! section B). The plan presses digits, never counts arrows: focus may start
//! on an earlier choice.

use crate::Agent;
use crate::locate::Lookup;
use crate::send::{Seen, Target};
use serde_json::Value;

/// The tool's name in the log.
pub const TOOL: &str = "AskUserQuestion";

/// The text the dialog's review step shows (design section B).
const REVIEW: &str = "Submit answers";

/// Questions and options per call the card takes (the tool allows 1 to 4).
const MAX_QUESTIONS: usize = 4;
const MAX_OPTIONS: usize = 4;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Choice {
    pub label: String,
    pub description: String,
    pub preview: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Question {
    pub text: String,
    pub header: String,
    pub multi: bool,
    pub options: Vec<Choice>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Shape {
    /// The card can take the answer.
    Readable(Vec<Question>),
    /// The card shows these question texts with "Open the terminal" (D10).
    Unreadable { texts: Vec<String> },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    /// No result for the call yet.
    Pending,
    /// A result: answered, at the desk or from the card.
    Answered,
    /// A result with `is_error`: declined (Esc).
    Declined,
}

/// One AskUserQuestion call of the log.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Asked {
    pub id: String,
    pub shape: Shape,
    pub state: State,
}

impl Asked {
    /// The headers, for the folded line ("Answered: Database").
    pub fn headers(&self) -> String {
        match &self.shape {
            Shape::Readable(qs) => qs
                .iter()
                .map(|q| q.header.trim())
                .filter(|h| !h.is_empty())
                .collect::<Vec<_>>()
                .join(", "),
            Shape::Unreadable { .. } => String::new(),
        }
    }
}

/// The call's input as the card may show it; unknown fields are ignored.
pub fn read_input(input: &Value) -> Shape {
    let list = input["questions"].as_array();
    let texts = || {
        list.into_iter()
            .flatten()
            .filter_map(|q| q["question"].as_str().map(str::to_owned))
            .collect::<Vec<_>>()
    };
    let Some(list) = list.filter(|l| !l.is_empty() && l.len() <= MAX_QUESTIONS) else {
        return Shape::Unreadable { texts: texts() };
    };
    let mut out = Vec::new();
    for q in list {
        let (Some(text), Some(options)) = (q["question"].as_str(), q["options"].as_array()) else {
            return Shape::Unreadable { texts: texts() };
        };
        if options.is_empty() || options.len() > MAX_OPTIONS {
            return Shape::Unreadable { texts: texts() };
        }
        let mut choices = Vec::new();
        for o in options {
            let Some(label) = o["label"].as_str().filter(|l| !l.trim().is_empty()) else {
                return Shape::Unreadable { texts: texts() };
            };
            choices.push(Choice {
                label: label.to_owned(),
                description: o["description"].as_str().unwrap_or("").to_owned(),
                preview: o["preview"].as_str().map(str::to_owned),
            });
        }
        out.push(Question {
            text: text.to_owned(),
            header: q["header"].as_str().unwrap_or("").to_owned(),
            multi: q["multiSelect"] == true,
            options: choices,
        });
    }
    Shape::Readable(out)
}

/// Prints the question hook's record of session `$1` (design D1), or
/// "missing"; only a plain session id is ever read. The hook script is
/// `contrib/claude-code/kudde-question-hook.sh`.
pub const HOOK_READ_SCRIPT: &str = r#"case $1 in ''|*[!0-9a-f-]*) echo missing; exit 0;; esac
f="${XDG_STATE_HOME:-$HOME/.local/state}/kudde/questions/$1.json"
if [ -f "$f" ]; then head -c 1048576 "$f"; else echo missing; fi"#;

/// The call the question hook saved: its id, its session and its shape.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Record {
    pub id: String,
    pub session: String,
    pub shape: Shape,
}

/// The hook's record as [HOOK_READ_SCRIPT] prints it; none when there is
/// none, it is not an AskUserQuestion call, or it lacks its id or session.
pub fn read_record(out: &str) -> Option<Record> {
    let v: Value = serde_json::from_str(out.trim()).ok()?;
    if v["tool_name"] != TOOL {
        return None;
    }
    let id = v["tool_use_id"].as_str().filter(|s| !s.is_empty())?;
    let session = v["session_id"].as_str().filter(|s| !s.is_empty())?;
    Some(Record {
        id: id.to_owned(),
        session: session.to_owned(),
        shape: read_input(&v["tool_input"]),
    })
}

/// What a tap on the card answers for one question.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Answer {
    /// The option at this index (0-based).
    Option(usize),
    /// "Something else": focuses the dialog's free-text row; the text then
    /// goes through `terminal.send`.
    Other,
    Skip,
    /// The review step's "Submit answers".
    Submit,
    /// A multi-select question's ticked options (0-based).
    Multi(Vec<usize>),
}

/// The digit that moves the dialog's focus to row `n` (1-based); none above 9.
fn digit(n: usize) -> Option<String> {
    (1..=9).contains(&n).then(|| n.to_string())
}

/// Whether `q` shows option previews: the dialog then lays the options out
/// beside the preview, and a digit only moves the focus.
fn has_previews(q: &Question) -> bool {
    q.options.iter().any(|o| o.preview.is_some())
}

/// The `terminal.keys` names that give `answer` to `q` (design D3, section
/// B as the 5.1 desk round found it); none when the answer does not fit the
/// question, and none for a multi-select question ([plan_multi]).
pub fn plan(q: &Question, answer: &Answer) -> Option<Vec<String>> {
    let n = q.options.len();
    match answer {
        // Without previews a digit alone answers and moves on; an Enter
        // after it would answer the next question's focused option.
        Answer::Option(i) if !q.multi && *i < n && !has_previews(q) => Some(vec![digit(i + 1)?]),
        Answer::Option(i) if !q.multi && *i < n => Some(vec![digit(i + 1)?, "enter".into()]),
        Answer::Option(_) => None,
        // The free-text row follows the options; its digit opens it for
        // typing. On a multi-select question own words go with the ticks.
        Answer::Other if !q.multi => Some(vec![digit(n + 1)?]),
        Answer::Other => None,
        Answer::Skip => Some(vec!["esc".into()]),
        Answer::Submit => Some(vec!["enter".into()]),
        Answer::Multi(_) => None,
    }
}

/// The first key of a note on option `i` (D9): its digit, which only moves
/// the focus on a question with previews; then `n` and the note, each after
/// the screen shows the step before ([focused], [notes_open]). None where
/// the dialog takes no notes (no previews, multi-select) or out of range.
pub fn plan_note(q: &Question, i: usize) -> Option<Vec<String>> {
    (!q.multi && has_previews(q) && i < q.options.len())
        .then(|| digit(i + 1))
        .flatten()
        .map(|d| vec![d])
}

/// The dialog's focus is on row `row` (1-based): the last pointer on the
/// screen ("❯ 2. Cards") is that row's.
pub fn focused(screen: &str, row: usize) -> bool {
    let screen = fold(screen);
    screen
        .rfind('\u{276F}')
        .is_some_and(|at| screen[at..].starts_with(&format!("\u{276F} {row}. ")))
}

/// The notes field of a preview question is open: the screen shows its
/// "Notes:" line, no longer with the hint to press n.
pub fn notes_open(screen: &str) -> bool {
    let screen = fold(screen);
    screen.contains("Notes:") && !screen.contains("press n to add notes")
}

/// A multi-select answer in steps the hub checks the screen between: the
/// [ticks]; with a typed answer, the text through `terminal.send` (paste
/// and Enter put it in the free row unticked) and an Enter when the screen
/// shows that row unticked; once [ticks_shown] holds, [next] leaves the
/// question (to the next one, or to the review step of a one-question
/// call, where Enter sends). Owner, 2026-11-25: a typed answer joins the
/// ticks and goes out with the one send.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MultiPlan {
    /// One digit per ticked option: a digit toggles its row in place.
    pub ticks: Vec<String>,
    /// Off the question: Tab; from the free row's editor, Tab moves to
    /// Submit and Enter takes it.
    pub next: Vec<String>,
}

/// The plan for ticking `ticked` on a multi-select question, with a typed
/// answer when `text`; none for a single-choice question, an option out of
/// range, or nothing at all to send.
pub fn plan_multi(q: &Question, ticked: &[usize], text: bool) -> Option<MultiPlan> {
    let n = q.options.len();
    if !q.multi || (ticked.is_empty() && !text) || ticked.iter().any(|i| *i >= n) {
        return None;
    }
    let mut ticked = ticked.to_vec();
    ticked.sort_unstable();
    ticked.dedup();
    let ticks = ticked
        .into_iter()
        .map(|i| digit(i + 1))
        .collect::<Option<Vec<_>>>()?;
    let next = if text {
        vec!["tab".into(), "enter".into()]
    } else {
        vec!["tab".into()]
    };
    Some(MultiPlan { ticks, next })
}

/// Whether the multi-select row `row` (1-based) shows ticked, as the last
/// such row on the screen draws it ("1. [✔] Apple", "2. [ ] Banana"); none
/// when the screen has no such row.
pub fn row_ticked(screen: &str, row: usize) -> Option<bool> {
    let screen = fold(screen);
    let on = screen.rfind(&format!("{row}. [\u{2714}]"));
    let off = screen.rfind(&format!("{row}. [ ]"));
    match (on, off) {
        (None, None) => None,
        (Some(_), None) => Some(true),
        (None, Some(_)) => Some(false),
        (Some(a), Some(b)) => Some(a > b),
    }
}

/// Whether the free row after `q`'s options shows ticked, once the screen
/// shows `text` in it (matched on its first 20 characters); none before.
pub fn typed_row(screen: &str, q: &Question, text: &str) -> Option<bool> {
    let row = q.options.len() + 1;
    let folded = fold(screen);
    let start: String = fold(text).chars().take(20).collect();
    let on = format!("{row}. [\u{2714}] {start}");
    let off = format!("{row}. [ ] {start}");
    match (folded.rfind(&on), folded.rfind(&off)) {
        (None, None) => None,
        (Some(_), None) => Some(true),
        (None, Some(_)) => Some(false),
        (Some(a), Some(b)) => Some(a > b),
    }
}

/// The screen shows `q`'s rows ticked exactly as planned: the options in
/// `ticked`, and the free row after them when `typed`.
pub fn ticks_shown(screen: &str, q: &Question, ticked: &[usize], typed: bool) -> bool {
    let n = q.options.len();
    (0..n).all(|i| row_ticked(screen, i + 1) == Some(ticked.contains(&i)))
        && row_ticked(screen, n + 1) == Some(typed)
}

/// Whether the multi-select keys are confirmed on a real terminal; until
/// then a multi-select question shows "Open the terminal" (spec).
pub const MULTI_CONFIRMED: bool = false;

/// `s` with box-drawing characters left out and every run of white space
/// (line breaks included) one space.
pub fn fold(s: &str) -> String {
    let kept: String = s.chars().map(|c| if is_box(c) { ' ' } else { c }).collect();
    kept.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn is_box(c: char) -> bool {
    matches!(c, '\u{2500}'..='\u{257F}' | '\u{2580}'..='\u{259F}')
}

/// The screen shows `q`: its text and every option's label.
pub fn shows(screen: &str, q: &Question) -> bool {
    shows_folded(&fold(screen), q)
}

fn shows_folded(screen: &str, q: &Question) -> bool {
    let text = fold(&q.text);
    !text.is_empty()
        && screen.contains(&text)
        && q.options.iter().all(|o| screen.contains(&fold(&o.label)))
}

/// Where the dialog is, as the screen shows it (design D4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Progress {
    /// On this question (0-based).
    Question(usize),
    /// On the review step of a call with several questions.
    Review,
    /// Neither: the dialog is gone, or another one shows.
    Gone,
}

pub fn progress(screen: &str, qs: &[Question]) -> Progress {
    let screen = fold(screen);
    // A call with several questions has a review step, and so does one
    // with a multi-select question (5.1); it lists the questions with their
    // answers, so it may hold their texts too.
    if (qs.len() > 1 || qs.iter().any(|q| q.multi)) && screen.contains(REVIEW) {
        return Progress::Review;
    }
    // Of two questions found (one text inside the other), the longer one.
    qs.iter()
        .enumerate()
        .filter(|(_, q)| shows_folded(&screen, q))
        .max_by_key(|(_, q)| fold(&q.text).len())
        .map_or(Progress::Gone, |(i, _)| Progress::Question(i))
}

/// Whether `answer` for question `step` may start with the screen at `p`:
/// on that question; the review step for Submit; the dialog anywhere for Skip.
pub fn before(answer: &Answer, step: usize, p: Progress) -> bool {
    match answer {
        Answer::Option(_) | Answer::Other | Answer::Multi(_) => p == Progress::Question(step),
        Answer::Submit => p == Progress::Review,
        Answer::Skip => p != Progress::Gone,
    }
}

/// What the screen after a step says about it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Moved {
    /// On the next question or the review step: done.
    Yes,
    /// Not yet (or never: a rebound key).
    No,
    /// The dialog is gone: done once the log holds the call's result.
    AskLog,
}

pub fn moved_on(answer: &Answer, step: usize, p: Progress) -> Moved {
    match (answer, p) {
        (_, Progress::Gone) => Moved::AskLog,
        (Answer::Submit | Answer::Skip, _) => Moved::No,
        (_, Progress::Question(i)) if i == step => Moved::No,
        _ => Moved::Yes,
    }
}

/// Why an answer sends nothing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refusal {
    /// The status is no longer blocked: "Already answered at the desk."
    Answered,
    /// Another process, agent or session, or none: "The question changed."
    Changed,
}

/// The check before an answer's keys (design D2): the agent shown is in
/// front, in the session shown, and still blocked. On success, the target
/// the next checks compare against.
pub fn check(shown: Agent, session: &str, seen: &Seen) -> Result<Target, Refusal> {
    let (Some(agent), Some(pid)) = (seen.agent, seen.pid) else {
        return Err(Refusal::Changed);
    };
    let s = match seen.lookup {
        Lookup::Found { session, .. } | Lookup::Empty { session } => session.as_str(),
        Lookup::Missed(_) | Lookup::None => return Err(Refusal::Changed),
    };
    if agent != shown || s != session {
        return Err(Refusal::Changed);
    }
    if seen.status != Some("blocked") {
        return Err(Refusal::Answered);
    }
    Ok(Target {
        agent,
        pid,
        proc_start: seen.proc_start,
        session: s.to_owned(),
    })
}

/// A later check against `target` (between the keys of one answer).
pub fn check_again(target: &Target, seen: &Seen) -> Result<(), Refusal> {
    let t = check(target.agent, &target.session, seen)?;
    if t.pid != target.pid || t.proc_start != target.proc_start {
        return Err(Refusal::Changed);
    }
    Ok(())
}

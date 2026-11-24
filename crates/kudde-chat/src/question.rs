//! Claude Code's multiple-choice question (AskUserQuestion) answered from
//! the chat (chat-questions design D1 to D4, D10): the call as the log holds
//! it, the keys that answer one step of it, and what the terminal's screen
//! says about it. No I/O: the hub makes the calls.
//!
//! The keys are the dialog's defaults read from the program (design section
//! B); the phone check confirms them. The plan presses digits, never counts
//! arrows: focus may start on an earlier choice.

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

/// The `terminal.keys` names that give `answer` to `q` (design D3); none
/// when the answer does not fit the question.
pub fn plan(q: &Question, answer: &Answer) -> Option<Vec<String>> {
    let n = q.options.len();
    match answer {
        Answer::Option(i) if !q.multi && *i < n => Some(vec![digit(i + 1)?, "enter".into()]),
        Answer::Option(_) => None,
        // The free-text row follows the options. On a multi-select question
        // own words go with the ticks ([plan_multi]).
        Answer::Other if !q.multi => Some(vec![digit(n + 1)?]),
        Answer::Other => None,
        Answer::Skip => Some(vec!["esc".into()]),
        Answer::Submit => Some(vec!["enter".into()]),
        // Low confidence (design section B): each ticked option is focused
        // and toggled, then the submit row after the free-text row. The card
        // offers it only once the phone check confirms it.
        Answer::Multi(ticked) if q.multi && !ticked.is_empty() => {
            let mut ticked = ticked.clone();
            ticked.sort_unstable();
            ticked.dedup();
            let mut keys = Vec::new();
            for i in ticked {
                if i >= n {
                    return None;
                }
                keys.push(digit(i + 1)?);
                keys.push("enter".into());
            }
            keys.push(digit(n + 2)?);
            keys.push("enter".into());
            Some(keys)
        }
        Answer::Multi(_) => None,
    }
}

/// A multi-select answer in two key runs around the typed text: the ticks
/// and the free row's digit [before] it, the submit row [after] it. The
/// text goes between them through `terminal.send` (paste and Enter, which
/// takes it as one more answer). Owner, 2026-11-25: a typed answer joins
/// the ticks and goes out with the one send. Low confidence (design section
/// B): confirmed by the phone check.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MultiPlan {
    pub before: Vec<String>,
    pub after: Vec<String>,
}

/// The keys for ticking `ticked` on a multi-select question, with a typed
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
    let mut before = Vec::new();
    for i in ticked {
        before.push(digit(i + 1)?);
        before.push("enter".into());
    }
    if text {
        before.push(digit(n + 1)?);
    }
    Some(MultiPlan {
        before,
        after: vec![digit(n + 2)?, "enter".into()],
    })
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
    // Only a call with several questions has a review step; it lists the
    // questions with one answer each, so it may hold their texts too.
    if qs.len() > 1 && screen.contains(REVIEW) {
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

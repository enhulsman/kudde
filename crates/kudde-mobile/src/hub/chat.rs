//! The chat view of the agent in a terminal (issue 17): finds its log with the
//! lookup script, tails it by byte offset and parses it with kudde-chat, all
//! through `terminal.get` and `machine.exec`; sends with `terminal.send`.
//! What to look up and read is decided by [`ChatCore`]; this file makes the
//! calls. The screen polls after each event for the terminal
//! ([`Chat::wait_change`]) and on a timer between them, only while the chat
//! or the terminal view needs it (design D5).

use super::HubCore;
use super::chat_core::{ChatCore, Kind, Read, Row, Term};
use base64::Engine;
use herdr_proto::rpc::{CallError, Client, Stream, StreamItem};
use kudde_chat::locate::{self, Lookup};
use kudde_chat::question::{self, Answer, Moved, Progress, Shape, State};
use kudde_chat::send::{self, Refusal, Seen};
use kudde_chat::{Agent, Health, Image, Item, image, tail};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Fetched images kept for scrolling back and forth; dropped all at once past this.
const IMAGE_CACHE: usize = 48_000_000;

/// An image in the conversation, fetched with [`Chat::image`] when shown.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatImage {
    /// `log:<session>:<line>:<n>`, `file:<line>:<path>` (the row's line: a
    /// reused path in a later row is another image), `inline:<base64>` or
    /// `missing`.
    pub key: String,
    pub media_type: String,
}

/// An image the person attaches from the phone.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatUpload {
    pub name: String,
    pub data: Vec<u8>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatCall {
    pub name: String,
    pub title: String,
    pub input: String,
    pub output: String,
    pub done: bool,
    pub failed: bool,
    pub images: Vec<ChatImage>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatRow {
    /// The row's identity (design D3): kept while the log grows and is read
    /// back, so the screen keys its list by it.
    pub key: String,
    /// "user", "said", "tools", "note" or "agent".
    pub kind: String,
    pub text: String,
    /// For "agent": who sent it (a subagent's task, or a session's name).
    pub from: String,
    pub images: Vec<ChatImage>,
    pub calls: Vec<ChatCall>,
}

/// One option of a question card (chat-questions).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ChatOption {
    pub label: String,
    pub description: String,
    pub preview: Option<String>,
}

/// One question of a call, as the log holds it, never cut.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ChatAsk {
    pub text: String,
    pub header: String,
    pub multi: bool,
    /// The card can take its answer; false for a multi-select question until
    /// its keys are confirmed (the card says "Open the terminal").
    pub answerable: bool,
    pub options: Vec<ChatOption>,
}

/// The question the agent waits on (design D1): shown as a card while the
/// log holds the call with no result, the status is blocked and the agent
/// and session are the ones shown.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ChatQuestion {
    /// The call's id, passed back with an answer.
    pub id: String,
    /// False: a shape the card cannot take; it shows [`ChatQuestion::texts`]
    /// with "Open the terminal".
    pub readable: bool,
    pub questions: Vec<ChatAsk>,
    pub texts: Vec<String>,
    /// The question the screen shows (0-based); the count of questions on
    /// the review step.
    pub step: u32,
    /// The dialog shows its review step ("Submit answers").
    pub review: bool,
    /// No hook record exists for the session: the card says how to answer
    /// from the phone (the question hook on the desk).
    #[uniffi(default = false)]
    pub hook_missing: bool,
}

/// What a tap on the card answers.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatChoice {
    /// The option at this index (0-based).
    Pick {
        index: u32,
    },
    /// "Something else": the person's own words.
    Other {
        text: String,
    },
    Skip,
    /// "Send answers" on the review step.
    Submit,
    /// A multi-select question's ticked options, and the person's own words,
    /// which join them (owner, 2026-11-25).
    Multi {
        indices: Vec<u32>,
        text: Option<String>,
    },
    /// "Choose this" in a preview sheet: the option, with a note when one
    /// was typed (design D9).
    Note {
        index: u32,
        text: String,
    },
}

/// What became of an answer, for the card.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatAnswer {
    /// The screen moved on, or the dialog closed and the log holds the result.
    Done,
    /// "Already answered at the desk. Nothing was sent."
    AlreadyAnswered,
    /// "The question changed. Nothing was sent."
    Changed,
    /// Keys went (or may have): "Could not confirm the answer arrived. Check
    /// the terminal." Never sent again.
    Unconfirmed,
    /// "Not connected. Nothing was sent."
    NotConnected,
    /// Nothing was sent: a check's call failed, or an answer the card should
    /// not have offered. The card says "Could not confirm the answer
    /// arrived. Check the terminal." (owner, 2026-11-24).
    Failed { reason: String },
    /// Sending the kept answers stopped after `sent` of them were typed
    /// (design D11): "Typed N of M answers, then stopped. Check the
    /// terminal." Nothing more is sent.
    Stopped { sent: u32 },
}

/// The free-text answer's limit, in characters (spec).
pub(crate) const TEXT_MAX: usize = 2000;
/// How often the screen is looked at after a step before it is unconfirmed,
/// and the wait between looks.
const CONFIRM_TRIES: usize = 6;
const CONFIRM_GAP: Duration = Duration::from_millis(500);
/// `terminal.keys` and `terminal.send` wait for ~3 s of quiet at the desk.
const KEYS_WAIT: Duration = Duration::from_secs(20);

/// The lookup result (spec: the lookup's four results).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatFound {
    Found,
    Empty,
    Missed,
    None,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatHealth {
    Ok,
    /// Readable, with parts left out.
    Degraded,
    /// Not readable any more: keep what was read, show the terminal button.
    Broken {
        reason: String,
    },
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatPoll {
    pub found: ChatFound,
    /// The desk answered a lookup for this terminal, now or before. False
    /// while the machine is still connecting: `found` then decides nothing.
    pub answered: bool,
    /// "claude" or "codex"; empty when no log is held.
    pub agent: String,
    pub title: String,
    pub version: String,
    /// The terminal's status: "working", "idle", "done", "blocked", ...
    /// (empty when not looked at).
    pub status: String,
    pub health: ChatHealth,
    /// A read that failed, shown over the chat until one succeeds.
    pub problem: String,
    /// The agent whose log is shown has exited: read-only.
    pub exited: bool,
    /// The agent shown is in front and may take a message now.
    pub can_send: bool,
    /// Older rows exist in the log: [`Chat::older`] reads them.
    pub cut: bool,
    pub rows: Vec<ChatRow>,
    /// The context gauge (design D8): tokens in use, and the window when the
    /// log names it (Codex).
    pub gauge_used: Option<u64>,
    pub gauge_window: Option<u64>,
    pub gauge_percent: Option<u32>,
    /// Claude Code: the texts it holds queued, not taken in yet (design
    /// D10): a pending message among them reads "Queued".
    pub queued: Vec<String>,
    /// Milliseconds per stage, for the log: "get 40 locate 50 tail 61 total 151".
    pub timings: String,
    /// The question card, from [`Chat::poll`] only.
    #[uniffi(default = None)]
    pub question: Option<ChatQuestion>,
}

/// Why a send typed nothing; the draft stays.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatRefusal {
    /// "The agent is waiting for you in the terminal" (blocked, or no status).
    Waiting,
    /// "The agent has exited".
    Exited,
    /// "This is a different session now".
    Changed,
    /// Not connected, a failed upload or call: the reason, for the chat.
    Failed { reason: String },
}

/// What became of a send, for the chat.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatSent {
    /// The desk typed it.
    Typed,
    /// It may be typed (no answer in time, or the connection went): followed as sent.
    Unsure,
    /// Nothing was typed; the draft comes back.
    Refused { refusal: ChatRefusal },
}

#[derive(uniffi::Object)]
pub struct Chat {
    core: Arc<HubCore>,
    machine: String,
    address: String,
    state: Mutex<ChatCore>,
    /// Fetched images by key, for scrolling back and forth.
    images: Mutex<HashMap<String, Vec<u8>>>,
    /// Codex image files by path, with the file time they were read at.
    files: Mutex<HashMap<String, (String, Vec<u8>)>>,
    /// This terminal's events, subscribed on the first wait.
    events: tokio::sync::Mutex<Option<Stream>>,
    /// What the last message was typed into, for [`Chat::enter_again`].
    sent_to: Mutex<Option<send::Target>>,
    /// The question call and where its dialog was last seen.
    step: Mutex<Option<(String, Progress)>>,
    /// The hook's call the card may answer: its record matched the session,
    /// the log and the screen at the last poll (design D1).
    held: Mutex<Option<question::Asked>>,
    /// Multi-select answers may be sent: shut until the phone check confirms
    /// their keys ([`question::MULTI_CONFIRMED`]).
    multi_ok: std::sync::atomic::AtomicBool,
}

impl Chat {
    pub(crate) fn new(core: Arc<HubCore>, machine: String, address: String) -> Self {
        Chat {
            core,
            machine,
            address,
            state: Mutex::new(ChatCore::new()),
            images: Mutex::new(HashMap::new()),
            files: Mutex::new(HashMap::new()),
            events: tokio::sync::Mutex::new(None),
            sent_to: Mutex::new(None),
            step: Mutex::new(None),
            held: Mutex::new(None),
            multi_ok: std::sync::atomic::AtomicBool::new(question::MULTI_CONFIRMED),
        }
    }
}

fn exec_stdout(v: &Value) -> Option<String> {
    let b = base64::engine::general_purpose::STANDARD
        .decode(v["stdout"].as_str()?)
        .ok()?;
    Some(String::from_utf8_lossy(&b).into_owned())
}

/// What `terminal.get` says, as the core reads it.
fn term_of(t: &Value) -> Term {
    let fg = &t["foreground"];
    let argv: Vec<&str> = fg["argv"]
        .as_array()
        .map(|a| a.iter().filter_map(Value::as_str).collect())
        .unwrap_or_default();
    Term {
        agent: locate::agent_in_front(fg["name"].as_str().unwrap_or(""), &argv),
        pid: fg["pid"].as_i64(),
        status: t["status"]["state"].as_str().map(str::to_owned),
    }
}

/// `session` is the log's: a `log:` key names its session, so an image of an
/// earlier session (after `/clear`) is never fetched from the current log.
/// `line` is the row's first line, which a file image's key carries.
fn chat_images(images: &[Image], session: &str) -> Vec<ChatImage> {
    images
        .iter()
        .map(|m| match m {
            Image::InLog {
                line,
                n,
                media_type,
            } => ChatImage {
                key: format!("log:{session}:{line}:{n}"),
                media_type: media_type.clone(),
            },
            Image::Inline { media_type, data } => ChatImage {
                key: format!("inline:{data}"),
                media_type: media_type.clone(),
            },
            Image::File { path, line } => ChatImage {
                key: format!("file:{line}:{path}"),
                media_type: String::new(),
            },
            Image::FileWithFallback { path, line, copy } => {
                let Image::InLog {
                    line: copy_line, n, ..
                } = copy.as_ref()
                else {
                    return ChatImage {
                        key: "missing".into(),
                        media_type: String::new(),
                    };
                };
                ChatImage {
                    key: format!("copy:{}", json!([session, line, path, copy_line, n])),
                    media_type: String::new(),
                }
            }
            Image::Missing => ChatImage {
                key: "missing".into(),
                media_type: String::new(),
            },
        })
        .collect()
}

fn chat_row(r: &Row, session: &str) -> ChatRow {
    let row = |kind: &str, text: &str| ChatRow {
        key: format!("{}:{}", r.key.0, r.key.1),
        kind: kind.into(),
        text: text.into(),
        from: String::new(),
        images: vec![],
        calls: vec![],
    };
    match &r.item {
        Item::User { text, images } => ChatRow {
            images: chat_images(images, session),
            ..row("user", text)
        },
        Item::Assistant { text } => row("said", text),
        Item::AgentMessage { from, text } => ChatRow {
            from: from.clone(),
            ..row("agent", text)
        },
        Item::Note { text } => row("note", text),
        Item::Update { text } => row("update", text),
        Item::Tools { calls } => ChatRow {
            calls: calls
                .iter()
                .map(|c| ChatCall {
                    name: c.name.clone(),
                    title: c.title.clone(),
                    input: c.input.clone(),
                    output: c.output.clone(),
                    done: c.done,
                    failed: c.failed,
                    images: chat_images(&c.images, session),
                })
                .collect(),
            ..row("tools", "")
        },
    }
}

/// A card that answers nothing: the question texts known, "Open the
/// terminal" (design D1, D10).
fn refused(id: &str, shape: &Shape) -> ChatQuestion {
    let texts = match shape {
        Shape::Readable(qs) => qs.iter().map(|q| q.text.clone()).collect(),
        Shape::Unreadable { texts } => texts.clone(),
    };
    ChatQuestion {
        id: if id.is_empty() {
            "waiting".into()
        } else {
            id.to_owned()
        },
        readable: false,
        questions: vec![],
        texts,
        step: 0,
        review: false,
        hook_missing: false,
    }
}

/// What the desk holds for the session: the hook's record, the reader's
/// "missing" (no hook wrote one), or nothing readable.
enum Hooked {
    Record(question::Record),
    Missing,
    Unknown,
}

/// The card's record of `asked`, the dialog at `at`.
fn chat_question(asked: &question::Asked, at: Progress) -> ChatQuestion {
    let (readable, questions, texts) = match &asked.shape {
        Shape::Readable(qs) => (
            true,
            qs.iter()
                .map(|q| ChatAsk {
                    text: q.text.clone(),
                    header: q.header.clone(),
                    multi: q.multi,
                    answerable: !q.multi || question::MULTI_CONFIRMED,
                    options: q
                        .options
                        .iter()
                        .map(|o| ChatOption {
                            label: o.label.clone(),
                            description: o.description.clone(),
                            preview: o.preview.clone(),
                        })
                        .collect(),
                })
                .collect::<Vec<_>>(),
            vec![],
        ),
        Shape::Unreadable { texts } => (false, vec![], texts.clone()),
    };
    let (step, review) = match at {
        Progress::Question(i) => (i.min(questions.len().saturating_sub(1)) as u32, false),
        Progress::Review => (questions.len() as u32, true),
        Progress::Gone => (0, false),
    };
    ChatQuestion {
        id: asked.id.clone(),
        readable,
        questions,
        texts,
        step,
        review,
        hook_missing: false,
    }
}

/// The message text for images put at `paths`: each path on a line of its
/// own, then the text.
fn with_images(paths: &[String], text: &str) -> String {
    let mut lines = paths.to_vec();
    lines.push(text.to_owned());
    lines.join("\n").trim().to_owned()
}

/// The `machine.exec` argv that prints the image behind `key`; a `log:` key
/// only for the open log (`session`, `path`); a file's with the time of the
/// copy `held` ("" for none).
fn image_argv(key: &str, log: Option<(&str, &str)>, held: &str) -> Option<Value> {
    if let Some(r) = key.strip_prefix("log:") {
        let mut parts = r.rsplitn(3, ':');
        let (n, line, session) = (parts.next()?, parts.next()?, parts.next()?);
        let (open, path) = log?;
        if session != open {
            return None;
        }
        Some(json!(["sh", "-c", image::LOG_SCRIPT, "sh", path, line, n]))
    } else {
        let path = file_path(key)?;
        Some(json!(["sh", "-c", image::FILE_SCRIPT, "sh", path, held]))
    }
}

/// The ordered sources of a paired image, restricted to the open session.
fn image_sources(key: &str, open: Option<&str>) -> Option<(String, String)> {
    let (session, line, path, copy_line, n): (String, u64, String, u64, u32) =
        serde_json::from_str(key.strip_prefix("copy:")?).ok()?;
    if Some(session.as_str()) != open {
        return None;
    }
    Some((
        format!("file:{line}:{path}"),
        format!("log:{session}:{copy_line}:{n}"),
    ))
}

/// The path of a `file:<line>:<path>` key.
fn file_path(key: &str) -> Option<&str> {
    key.strip_prefix("file:")?.split_once(':').map(|(_, p)| p)
}

/// The time of the copy of `path` held, "" for none.
fn held_time(held: &HashMap<String, (String, Vec<u8>)>, path: &str) -> String {
    held.get(path).map(|(t, _)| t.clone()).unwrap_or_default()
}

/// [`image::FILE_SCRIPT`]'s reply taken into the files held by path and
/// time: the bytes sent, or the copy held when only its time came back.
fn file_image(
    held: &mut HashMap<String, (String, Vec<u8>)>,
    path: &str,
    reply: Option<(String, Option<Vec<u8>>)>,
) -> Option<Vec<u8>> {
    match reply {
        Some((time, Some(bytes))) => {
            held.insert(path.to_owned(), (time, bytes.clone()));
            Some(bytes)
        }
        Some((time, None)) => held
            .get(path)
            .filter(|(t, _)| *t == time)
            .map(|(_, b)| b.clone()),
        None => {
            held.remove(path);
            None
        }
    }
}

/// The agent in front may take a message: idle, done, or working (it
/// queues). Not blocked (a dialog), not unreported (a start-up prompt), not a
/// shell (it would run the text).
fn may_send(t: &Term) -> bool {
    t.agent.is_some() && matches!(t.status.as_deref(), Some("idle" | "done" | "working"))
}

/// What became of a `terminal.send`.
#[derive(Debug, PartialEq, Eq)]
enum Sent {
    Typed,
    /// No reply in time, or the connection went while waiting: it may be
    /// typed, so it is followed as sent, never given back as a draft.
    Unsure,
    /// The core refused it (its `if` did not hold): nothing was typed.
    Refused(String),
}

fn send_outcome(r: &Result<Value, CallError>) -> Sent {
    match r {
        Ok(_) => Sent::Typed,
        Err(CallError::OutcomeUnknown | CallError::Lost) => Sent::Unsure,
        Err(e) => Sent::Refused(e.to_string()),
    }
}

/// Enter is pressed once more only into what the message was typed into:
/// the same process and session, and an agent that may take it now. It goes
/// as a `terminal.send` of one space, never as a bare `terminal.keys` Enter:
/// the core refuses a send to a blocked program at the moment it types, so
/// the Enter can never answer a dialog that opened after this check, and the
/// checked status goes as its `if` where the core has one (idle, working).
/// Not an empty send: Claude Code keeps the message in its box after an
/// empty paste (checked at the desk, 2026-11-18); a trailing space changes
/// nothing the agent reads.
fn enter_again_params(address: &str, sent_to: Option<&send::Target>, seen: &Seen) -> Option<Value> {
    let guard = send::check_again(sent_to?, seen).ok()?;
    let mut params = json!({"terminal": address, "text": " "});
    if let Some(st) = guard {
        params["if"] = json!(st);
    }
    Some(params)
}

fn refusal(r: Refusal) -> ChatRefusal {
    match r {
        Refusal::Waiting => ChatRefusal::Waiting,
        Refusal::Exited => ChatRefusal::Exited,
        Refusal::Changed => ChatRefusal::Changed,
    }
}

/// One read of the log through the tail script: an error says why (the
/// output was cut, the read timed out, the file went away).
async fn read_chunk(c: &Client, r: &Read) -> Result<tail::Chunk, String> {
    let mut argv = vec![
        json!("sh"),
        json!("-c"),
        json!(tail::SCRIPT),
        json!("sh"),
        json!(r.path),
        json!(r.start),
    ];
    if let Some(e) = r.end {
        argv.push(json!(e.to_string()));
    }
    let got = c
        .call_timeout(
            "machine.exec",
            json!({"argv": argv, "timeout": 25}),
            Duration::from_secs(30),
        )
        .await
        .map_err(|e| format!("Couldn't read the log: {e}"))?;
    if got["truncated"] == true {
        return Err("Couldn't read the log: the output was cut".into());
    }
    if got["timed_out"] == true {
        return Err("Couldn't read the log: it took too long".into());
    }
    let out = exec_stdout(&got).unwrap_or_default();
    if out.trim() == "missing" {
        return Err("The log is gone".into());
    }
    tail::parse_chunk(&out)
        .map(tail::with_barrier)
        .ok_or_else(|| "Couldn't read the log: unexpected output".into())
}

/// The lookup script's result for the agent in front, and the process start
/// it read; `None` when the desk did not answer.
async fn look_up(c: &Client, t: &Term) -> (Option<Lookup>, Option<u64>) {
    let (Some(agent), Some(pid)) = (t.agent, t.pid) else {
        return (Some(Lookup::None), None);
    };
    let got = c
        .call_timeout(
            "machine.exec",
            json!({"argv": ["sh", "-c", locate::SCRIPT, "sh", pid.to_string()], "timeout": 20}),
            Duration::from_secs(30),
        )
        .await;
    match got.ok().as_ref().and_then(exec_stdout) {
        Some(out) => {
            let probe = locate::parse_probe(&out);
            (Some(locate::decide_for(agent, &probe)), probe.proc_start)
        }
        None => (None, None),
    }
}

/// Stage timings for the log, in the order they ran.
#[derive(Default)]
struct Times(String);

impl Times {
    fn mark(&mut self, stage: &str, since: Instant) {
        if !self.0.is_empty() {
            self.0.push(' ');
        }
        self.0
            .push_str(&format!("{stage} {}", since.elapsed().as_millis()));
    }
}

#[uniffi::export]
impl Chat {
    /// Looks up and reads what is new. `event`: a status, turn or title
    /// event of the terminal came (see [`Chat::wait_change`]). Blocks: call
    /// it off the main thread.
    pub fn poll(&self, event: bool) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(self.poll_async(event))
    }

    /// For the terminal view (design D5): looks the log up and reads its
    /// recent part only when [`ChatCore::needs_watch`] says so, to decide the
    /// switch; otherwise one `terminal.get`. Blocks.
    pub fn watch(&self, event: bool) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let began = Instant::now();
            let mut times = Times::default();
            let Some(conn) = self.core.live_conn(&self.machine) else {
                return self.view("", false, Times("not connected".into()), None);
            };
            let c = conn.client;
            let Ok(t) = c
                .call("terminal.get", json!({"terminal": self.address}))
                .await
                .map(|t| term_of(&t))
            else {
                return self.view("", false, times, None);
            };
            self.state.lock().unwrap().watch_see(&t);
            if self.state.lock().unwrap().needs_watch(&t, event) {
                let at = Instant::now();
                let (l, _) = look_up(&c, &t).await;
                times.mark("locate", at);
                self.state
                    .lock()
                    .unwrap()
                    .take_watch_lookup(&t, l, Instant::now());
                for _ in 0..2 {
                    let Some(r) = self.state.lock().unwrap().plan_read() else {
                        break;
                    };
                    let at = Instant::now();
                    let got = read_chunk(&c, &r).await;
                    times.mark("tail", at);
                    if !self.state.lock().unwrap().take_read(got, Instant::now()) {
                        break;
                    }
                }
            }
            times.mark("total", began);
            let status = t.status.clone().unwrap_or_default();
            self.view(&status, may_send(&t), times, None)
        })
    }

    /// What is held, with no call: the last view drawn at once on return
    /// (design D12); `None` before the first lookup.
    pub fn held(&self) -> Option<ChatPoll> {
        self.state.lock().unwrap().lookup()?;
        Some(self.view("", false, Times::default(), None))
    }

    /// Reads the part of the log before the oldest row held and parses it
    /// together with what is held. Blocks.
    pub fn older(&self) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let began = Instant::now();
            let mut times = Times::default();
            if let Some(conn) = self.core.live_conn(&self.machine) {
                loop {
                    let Some(r) = self.state.lock().unwrap().plan_older() else {
                        break;
                    };
                    let got = read_chunk(&conn.client, &r).await;
                    if !self.state.lock().unwrap().take_older(got) {
                        break;
                    }
                }
            }
            times.mark("older", began);
            self.view("", false, times, None)
        })
    }

    /// The chat left the screen (terminal view, another terminal, the app
    /// in the background): it makes no reads until polled again, and then
    /// looks up and reads only what was written since (design D12).
    pub fn leave(&self) {
        self.state.lock().unwrap().leave();
    }

    /// Waits up to `timeout_ms` for a status, turn or title event of this
    /// terminal; true when one came. Blocks.
    pub fn wait_change(&self, timeout_ms: u32) -> bool {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let mut events = self.events.lock().await;
            if events.is_none() {
                let Some(conn) = self.core.live_conn(&self.machine) else {
                    drop(events);
                    tokio::time::sleep(Duration::from_millis(timeout_ms.into())).await;
                    return false;
                };
                match conn
                    .client
                    .subscribe(json!({"terminal": self.address}))
                    .await
                {
                    Ok((_, stream)) => *events = Some(stream),
                    // Waits anyway: returning at once would make the poll loop spin.
                    Err(_) => {
                        drop(events);
                        tokio::time::sleep(Duration::from_millis(timeout_ms.into())).await;
                        return false;
                    }
                }
            }
            let stream = events.as_mut().unwrap();
            let wait = async {
                loop {
                    match stream.recv().await {
                        StreamItem::Data(d) => {
                            if !String::from_utf8_lossy(&d).contains("heartbeat") {
                                return Some(true);
                            }
                        }
                        StreamItem::Closed(_) | StreamItem::Lost => return None,
                    }
                }
            };
            match tokio::time::timeout(Duration::from_millis(timeout_ms.into()), wait).await {
                Ok(Some(changed)) => changed,
                Ok(None) => {
                    *events = None;
                    drop(events);
                    tokio::time::sleep(Duration::from_millis(u64::from(timeout_ms).min(1000)))
                        .await;
                    false
                }
                Err(_) => false,
            }
        })
    }

    /// Sends `text` as the person's message (design D4).
    pub fn send(&self, text: String) -> ChatSent {
        self.send_with_images(text, vec![])
    }

    /// Checks the target, puts each image beside the terminal
    /// (`terminal.put`), checks again, and sends the message with the
    /// images' paths first, one per line: Claude Code attaches a pasted line
    /// that is an image path as the image itself.
    pub fn send_with_images(&self, text: String, images: Vec<ChatUpload>) -> ChatSent {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let no = |refusal: ChatRefusal| ChatSent::Refused { refusal };
            let failed = |reason: String| no(ChatRefusal::Failed { reason });
            let Some(conn) = self.core.live_conn(&self.machine) else {
                return failed("Not connected".into());
            };
            let c = conn.client;
            let Some((agent, session)) = self.state.lock().unwrap().shown() else {
                return no(ChatRefusal::Changed);
            };
            let target = match self.check(&c).await {
                Ok((t, start, l)) => match send::first_check(agent, &session, &seen(&t, start, &l))
                {
                    Ok(target) => target,
                    Err(r) => return no(refusal(r)),
                },
                Err(e) => return failed(e),
            };
            let mut lines = Vec::new();
            for up in images {
                let data = base64::engine::general_purpose::STANDARD.encode(&up.data);
                let put = c
                    .call_timeout(
                        "terminal.put",
                        json!({"terminal": self.address, "name": up.name, "data": data}),
                        Duration::from_secs(60),
                    )
                    .await;
                match put.as_ref().ok().and_then(|r| r["path"].as_str()) {
                    Some(p) => lines.push(p.to_owned()),
                    None => {
                        return failed(format!(
                            "Couldn't send the image: {}",
                            put.err().map(|e| e.to_string()).unwrap_or_default()
                        ));
                    }
                }
            }
            let message = with_images(&lines, &text);
            // Uploads take time: check again right before typing, and let the
            // core refuse if the status changed since (its `if`).
            let guard = match self.check(&c).await {
                Ok((t, start, l)) => match send::check_again(&target, &seen(&t, start, &l)) {
                    Ok(g) => g,
                    Err(r) => return no(refusal(r)),
                },
                Err(e) => return failed(e),
            };
            let mut params = json!({"terminal": self.address, "text": message});
            if let Some(st) = guard {
                params["if"] = json!(st);
            }
            match send_outcome(&c.call("terminal.send", params).await) {
                sent @ (Sent::Typed | Sent::Unsure) => {
                    self.state.lock().unwrap().sent(&message, Instant::now());
                    *self.sent_to.lock().unwrap() = Some(target);
                    if sent == Sent::Typed {
                        ChatSent::Typed
                    } else {
                        ChatSent::Unsure
                    }
                }
                Sent::Refused(e) => failed(e),
            }
        })
    }

    /// Presses Enter once more for a message the agent left in its input
    /// box, after the same checks as a send, against the process and
    /// session it was typed into; true when pressed. Blocks.
    pub fn enter_again(&self) -> bool {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let Some(conn) = self.core.live_conn(&self.machine) else {
                return false;
            };
            let c = conn.client;
            let Ok((t, start, l)) = self.check(&c).await else {
                return false;
            };
            let Some(params) = enter_again_params(
                &self.address,
                self.sent_to.lock().unwrap().as_ref(),
                &seen(&t, start, &l),
            ) else {
                return false;
            };
            send_outcome(&c.call("terminal.send", params).await) == Sent::Typed
        })
    }

    /// Answers the question card's call `id` at question `step` (design D2):
    /// checks the terminal and its screen, presses the step's keys in one
    /// `terminal.keys` call, and reads the screen (and the log) to confirm.
    /// Call it under the same lock as [`Chat::poll`]: it reads the log too.
    /// Blocks.
    pub fn answer_question(&self, id: String, step: u32, choice: ChatChoice) -> ChatAnswer {
        let core = self.core.clone();
        core.runtime().block_on(self.answer_async(id, step, choice))
    }

    /// Sends the card's kept answers to call `id`, one per question in
    /// order, from question `from` on (design D11); see
    /// [`Chat::send_answers_async`]. Same lock as [`Chat::answer_question`].
    /// Blocks.
    pub fn send_answers(&self, id: String, answers: Vec<ChatChoice>, from: u32) -> ChatAnswer {
        let core = self.core.clone();
        core.runtime()
            .block_on(self.send_answers_async(id, answers, from))
    }

    /// The bytes of one image from [`ChatImage::key`], fetched from the desk
    /// the first time; `None` when it is gone or cannot be read. Blocks.
    pub fn image(&self, key: String) -> Option<Vec<u8>> {
        if key.starts_with("file:") || key.starts_with("copy:") {
            let core = self.core.clone();
            return core.runtime().block_on(self.fetch_image(&key));
        }
        if let Some(b) = self.images.lock().unwrap().get(&key) {
            return Some(b.clone());
        }
        let core = self.core.clone();
        let got = core.runtime().block_on(self.fetch_image(&key))?;
        let mut cache = self.images.lock().unwrap();
        if cache.values().map(Vec::len).sum::<usize>() + got.len() > IMAGE_CACHE {
            cache.clear();
        }
        cache.insert(key, got.clone());
        Some(got)
    }
}

fn seen<'a>(t: &'a Term, proc_start: Option<u64>, l: &'a Lookup) -> Seen<'a> {
    Seen {
        agent: t.agent,
        pid: t.pid,
        proc_start,
        status: t.status.as_deref(),
        lookup: l,
    }
}

/// The keys of one answer: one `terminal.keys` call, or a multi-select
/// plan run in checked steps.
enum Planned {
    Keys(Vec<String>),
    Multi(question::MultiPlan),
    /// A note on a preview option: the digit first ([question::plan_note]).
    Note(Vec<String>),
}

/// One answer from the card, checked on its own: what it answers, the text
/// it types (own words, a note, a multi-select typed answer), and whether
/// that text is a note.
struct Given {
    answer: Answer,
    typed: Option<String>,
    note: bool,
}

/// The card's choice as an answer; refused (nothing sent) when blank, too
/// long or not offered.
fn given(choice: &ChatChoice, multi_ok: bool) -> Result<Given, ChatAnswer> {
    let failed = |r: &str| Err(ChatAnswer::Failed { reason: r.into() });
    let plain = |answer| {
        Ok(Given {
            answer,
            typed: None,
            note: false,
        })
    };
    match choice {
        ChatChoice::Pick { index } => plain(Answer::Option(*index as usize)),
        ChatChoice::Other { text } if text.trim().is_empty() => failed("Type your answer"),
        ChatChoice::Other { text } if text.chars().count() > TEXT_MAX => {
            failed("Too long: 2000 characters at most")
        }
        ChatChoice::Other { text } => Ok(Given {
            answer: Answer::Other,
            typed: Some(text.clone()),
            note: false,
        }),
        ChatChoice::Skip => plain(Answer::Skip),
        ChatChoice::Submit => plain(Answer::Submit),
        ChatChoice::Multi { .. } if !multi_ok => failed("Open the terminal"),
        ChatChoice::Note { index, text } if text.trim().is_empty() => {
            plain(Answer::Option(*index as usize))
        }
        ChatChoice::Note { text, .. } if text.chars().count() > TEXT_MAX => {
            failed("Too long: 2000 characters at most")
        }
        ChatChoice::Note { index, text } => Ok(Given {
            answer: Answer::Option(*index as usize),
            typed: Some(text.clone()),
            note: true,
        }),
        ChatChoice::Multi { indices, text } => {
            let typed = text.clone().filter(|t| !t.trim().is_empty());
            if typed.as_ref().is_some_and(|t| t.chars().count() > TEXT_MAX) {
                return failed("Too long: 2000 characters at most");
            }
            if indices.is_empty() && typed.is_none() {
                return failed("Nothing to send");
            }
            Ok(Given {
                answer: Answer::Multi(indices.iter().map(|i| *i as usize).collect()),
                typed,
                note: false,
            })
        }
    }
}

/// The keys of answer `g` to question `step`; none when it does not fit.
fn plan_for(qs: &[question::Question], step: usize, g: &Given) -> Option<Planned> {
    let q = qs.get(step)?;
    match &g.answer {
        Answer::Option(i) if g.note => question::plan_note(q, *i).map(Planned::Note),
        Answer::Multi(ticked) => {
            question::plan_multi(q, ticked, g.typed.is_some()).map(Planned::Multi)
        }
        Answer::Skip | Answer::Submit => None,
        a => question::plan(q, a).map(Planned::Keys),
    }
}

impl Chat {
    /// One check of the send target: the terminal and a fresh lookup.
    async fn check(&self, c: &Client) -> Result<(Term, Option<u64>, Lookup), String> {
        let t = c
            .call("terminal.get", json!({"terminal": self.address}))
            .await
            .map_err(|e| format!("{e}"))?;
        let t = term_of(&t);
        let (l, start) = look_up(c, &t).await;
        let l = l.ok_or_else(|| "The desk did not answer".to_string())?;
        Ok((t, start, l))
    }

    /// The terminal's screen as text.
    async fn screen(&self, c: &Client) -> Result<String, String> {
        let got = c
            .call("terminal.read", json!({"terminal": self.address}))
            .await
            .map_err(|e| format!("{e}"))?;
        got["text"]
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| "The screen could not be read".to_string())
    }

    /// The hook's record of `session`, read on the desk; none when there is
    /// none or it cannot be read.
    async fn record(&self, c: &Client, session: &str) -> Hooked {
        let Ok(got) = c
            .call_timeout(
                "machine.exec",
                json!({"argv": ["sh", "-c", question::HOOK_READ_SCRIPT, "sh", session], "timeout": 10}),
                Duration::from_secs(15),
            )
            .await
        else {
            return Hooked::Unknown;
        };
        match exec_stdout(&got) {
            Some(out) if out.trim() == "missing" => Hooked::Missing,
            Some(out) => question::read_record(&out).map_or(Hooked::Unknown, Hooked::Record),
            None => Hooked::Unknown,
        }
    }

    /// The card for this poll (design D1). Answerable only from the hook's
    /// record of the session shown, for a call with no result in the log,
    /// with the status blocked and the screen showing that dialog; the step
    /// is the screen's. A screen the card does not know keeps the step seen
    /// last for the call already held. Otherwise, while a question waits
    /// (the status kind, or a call in the log with no result), the card
    /// says "Open the terminal" with what it knows of the question.
    async fn question_for(&self, c: &Client, t: &Term, kind: Option<&str>) -> Option<ChatQuestion> {
        let forget = || {
            *self.step.lock().unwrap() = None;
            *self.held.lock().unwrap() = None;
        };
        let waiting =
            t.status.as_deref() == Some("blocked") && matches!(kind, None | Some("question"));
        let (session, logged) = {
            let st = self.state.lock().unwrap();
            match st.shown() {
                Some((Agent::Claude, session)) if waiting && st.shows(t) => {
                    (session, st.question())
                }
                _ => {
                    drop(st);
                    forget();
                    return None;
                }
            }
        };
        let hooked = self.record(c, &session).await;
        let missing = matches!(hooked, Hooked::Missing);
        let fresh = match hooked {
            Hooked::Record(r) => Some(r),
            _ => None,
        }
        .filter(|r| {
            r.session == session
                && self
                    .state
                    .lock()
                    .unwrap()
                    .asked(&r.id)
                    .is_none_or(|a| a.state == State::Pending)
        });
        let Some(r) = fresh else {
            forget();
            if kind != Some("question") && logged.is_none() {
                return None;
            }
            let mut card = match &logged {
                Some(a) => refused(&a.id, &a.shape),
                None => refused("", &Shape::Unreadable { texts: vec![] }),
            };
            card.hook_missing = missing;
            return Some(card);
        };
        let asked = question::Asked {
            id: r.id,
            shape: r.shape,
            state: State::Pending,
        };
        let Shape::Readable(qs) = &asked.shape else {
            forget();
            return Some(chat_question(&asked, Progress::Question(0)));
        };
        let seen = match self.screen(c).await {
            Ok(screen) => question::progress(&screen, qs),
            Err(_) => Progress::Gone,
        };
        let kept = match self.step.lock().unwrap().as_ref() {
            Some((id, p)) if *id == asked.id => Some(*p),
            _ => None,
        };
        let at = match (seen, kept) {
            (Progress::Gone, Some(p)) => p,
            (Progress::Gone, None) => {
                forget();
                return Some(refused(&asked.id, &asked.shape));
            }
            (p, _) => p,
        };
        *self.step.lock().unwrap() = Some((asked.id.clone(), at));
        let card = chat_question(&asked, at);
        *self.held.lock().unwrap() = Some(asked);
        Some(card)
    }

    pub(crate) async fn answer_async(
        &self,
        id: String,
        step: u32,
        choice: ChatChoice,
    ) -> ChatAnswer {
        let step = step as usize;
        let g = match given(&choice, self.multi_ok()) {
            Ok(g) => g,
            Err(a) => return a,
        };
        // From what is held, no call: the call must still be the one the
        // hook's record gave the card (design D1).
        let (agent, session, qs) = match self.held_call(&id) {
            Ok(h) => h,
            Err(a) => return a,
        };
        let planned = match &g.answer {
            Answer::Submit if qs.len() < 2 => None,
            Answer::Submit | Answer::Skip => question::plan(&qs[0], &g.answer).map(Planned::Keys),
            _ => plan_for(&qs, step, &g),
        };
        let Some(planned) = planned else {
            return ChatAnswer::Changed;
        };
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return ChatAnswer::NotConnected;
        };
        let c = conn.client;
        let before = match self
            .checked(&c, agent, &session, &qs, |p| {
                question::before(&g.answer, step, p)
            })
            .await
        {
            Ok(s) => s,
            Err(a) => return a,
        };
        // From here keys may have gone: nothing is sent again.
        self.one_step(&c, &id, &qs, step, &g, planned, &before, true)
            .await
    }

    /// Sends the card's kept answers (design D11): from question `from` on,
    /// each after the target check and once the screen shows it, then, where
    /// the dialog has a review step, Enter once that step lists what was
    /// typed. Halfway it stops with [`ChatAnswer::Stopped`] and the count of
    /// answers typed; it goes on later only from the question the screen
    /// then shows, and only when that is question `from`.
    pub(crate) async fn send_answers_async(
        &self,
        id: String,
        answers: Vec<ChatChoice>,
        from: u32,
    ) -> ChatAnswer {
        let failed = |r: &str| ChatAnswer::Failed { reason: r.into() };
        let from = from as usize;
        let mut gs = Vec::with_capacity(answers.len());
        for a in &answers {
            match given(a, self.multi_ok()) {
                // In the kept answers Skip is "Skip question": no answer, Tab past it.
                Ok(g) if g.answer == Answer::Skip => gs.push(Given {
                    answer: Answer::Pass,
                    typed: None,
                    note: false,
                }),
                Ok(g) if g.answer != Answer::Submit => gs.push(g),
                Ok(_) => return failed("Not an answer"),
                Err(a) => return a,
            }
        }
        if gs.iter().all(|g| g.answer == Answer::Pass) {
            return failed("Nothing to send");
        }
        let (agent, session, qs) = match self.held_call(&id) {
            Ok(h) => h,
            Err(a) => return a,
        };
        if gs.len() != qs.len() || from > qs.len() {
            return failed("The answers do not fit the question");
        }
        let mut plans = Vec::with_capacity(qs.len());
        for (i, g) in gs.iter().enumerate() {
            match plan_for(&qs, i, g) {
                Some(p) => plans.push(p),
                None => return failed("The answers do not fit the question"),
            }
        }
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return ChatAnswer::NotConnected;
        };
        let c = conn.client;
        let stopped = |i: usize| ChatAnswer::Stopped { sent: i as u32 };
        for (i, planned) in plans.into_iter().enumerate().skip(from) {
            let before = match self
                .checked(&c, agent, &session, &qs, |p| p == Progress::Question(i))
                .await
            {
                Ok(s) => s,
                Err(a) if i == from => return a,
                Err(_) => return stopped(i),
            };
            match self
                .one_step(&c, &id, &qs, i, &gs[i], planned, &before, false)
                .await
            {
                ChatAnswer::Done => {}
                a if i == 0 => return a,
                _ => return stopped(i),
            }
        }
        let n = qs.len();
        if !question::has_review(&qs) {
            // The one question's step closed the dialog (its result is in the log).
            return ChatAnswer::Done;
        }
        // Nothing pressed in this send yet when every answer was typed before.
        let refused = |a: ChatAnswer| if from == n { a } else { stopped(n) };
        if let Err(a) = self.target_ok(&c, agent, &session).await {
            return refused(a);
        }
        let expected: Vec<Option<Vec<String>>> = qs
            .iter()
            .zip(&gs)
            .map(|(q, g)| {
                (g.answer != Answer::Pass)
                    .then(|| question::expected_answer(q, &g.answer, g.typed.as_deref()))
            })
            .collect();
        if self
            .screen_until(&c, |s| question::review_matches(s, &qs, &expected))
            .await
            .is_none()
        {
            return refused(ChatAnswer::Changed);
        }
        if !self.press(&c, &["enter".to_string()]).await {
            return stopped(n);
        }
        match self.confirm(&c, &id, &qs, n - 1, &Answer::Submit).await {
            ChatAnswer::Done => ChatAnswer::Done,
            _ => stopped(n),
        }
    }

    fn multi_ok(&self) -> bool {
        self.multi_ok.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// The held call `id`: the agent and session shown and its questions.
    fn held_call(&self, id: &str) -> Result<(Agent, String, Vec<question::Question>), ChatAnswer> {
        let held = self.held.lock().unwrap().clone();
        let st = self.state.lock().unwrap();
        let Some((agent, session)) = st.shown() else {
            return Err(ChatAnswer::Changed);
        };
        let open = held.filter(|a| st.asked(&a.id).is_none_or(|l| l.state == State::Pending));
        match open {
            Some(a) if a.id == id => match a.shape {
                Shape::Readable(qs) => Ok((agent, session, qs)),
                Shape::Unreadable { .. } => Err(ChatAnswer::Failed {
                    reason: "Open the terminal".into(),
                }),
            },
            _ => Err(match st.asked(id) {
                Some(a) if a.state != State::Pending => ChatAnswer::AlreadyAnswered,
                _ => ChatAnswer::Changed,
            }),
        }
    }

    /// The send target is unchanged and still blocked (design D2).
    async fn target_ok(&self, c: &Client, agent: Agent, session: &str) -> Result<(), ChatAnswer> {
        match self.check(c).await {
            Ok((t, start, l)) => match question::check(agent, session, &seen(&t, start, &l)) {
                Ok(_) => Ok(()),
                Err(question::Refusal::Answered) => Err(ChatAnswer::AlreadyAnswered),
                Err(question::Refusal::Changed) => Err(ChatAnswer::Changed),
            },
            Err(e) => Err(ChatAnswer::Failed { reason: e }),
        }
    }

    /// The target check, then the screen, which must be where `at` says.
    async fn checked(
        &self,
        c: &Client,
        agent: Agent,
        session: &str,
        qs: &[question::Question],
        at: impl Fn(Progress) -> bool,
    ) -> Result<String, ChatAnswer> {
        self.target_ok(c, agent, session).await?;
        match self.screen(c).await {
            Ok(s) if at(question::progress(&s, qs)) => Ok(s),
            Ok(_) => Err(ChatAnswer::Changed),
            Err(e) => Err(ChatAnswer::Failed { reason: e }),
        }
    }

    /// One question's keys, the screen showing `before`, and the screen
    /// moving on. `submit`: a one-question multi-select call then sends from
    /// its review step too (one tap answers it).
    #[allow(clippy::too_many_arguments)]
    async fn one_step(
        &self,
        c: &Client,
        id: &str,
        qs: &[question::Question],
        step: usize,
        g: &Given,
        planned: Planned,
        before: &str,
        submit: bool,
    ) -> ChatAnswer {
        let keys = match planned {
            Planned::Keys(keys) => keys,
            Planned::Note(digit) => {
                let (Answer::Option(i), Some(text)) = (&g.answer, g.typed.as_deref()) else {
                    return ChatAnswer::Changed;
                };
                return self.answer_note(c, id, qs, step, *i, text, &digit).await;
            }
            Planned::Multi(mut m) => {
                let Answer::Multi(ticked) = &g.answer else {
                    return ChatAnswer::Changed;
                };
                // Only the rows the screen shows otherwise: a digit toggles its
                // row, so a retry or a desk tick must not undo one (5.4 review).
                let Some(ticks) = question::toggles(before, &qs[step], ticked) else {
                    return ChatAnswer::Changed;
                };
                m.ticks = ticks;
                return self
                    .answer_multi(c, id, qs, step, ticked, g.typed.as_deref(), m, submit)
                    .await;
            }
        };
        if !self.press(c, &keys).await {
            return ChatAnswer::Unconfirmed;
        }
        if let Some(text) = &g.typed {
            // The text and its Enter go into the free-text row only once the
            // screen shows it focused on this question (design D6; 5.4 review).
            let row = qs[step].options.len() + 1;
            if self
                .screen_until(c, |s| {
                    question::progress(s, qs) == Progress::Question(step)
                        && question::focused(s, row)
                })
                .await
                .is_none()
            {
                return ChatAnswer::Unconfirmed;
            }
            if !self.type_forced(c, text).await {
                return ChatAnswer::Unconfirmed;
            }
        }
        self.confirm(c, id, qs, step, &g.answer).await
    }

    /// One `terminal.keys` call; false when it may not have arrived.
    async fn press(&self, c: &Client, keys: &[String]) -> bool {
        c.call_timeout(
            "terminal.keys",
            json!({"terminal": self.address, "keys": keys}),
            KEYS_WAIT,
        )
        .await
        .is_ok()
    }

    /// The text pasted with its Enter, even though the agent is blocked.
    async fn type_forced(&self, c: &Client, text: &str) -> bool {
        c.call_timeout(
            "terminal.send",
            json!({"terminal": self.address, "text": text, "force": true}),
            KEYS_WAIT,
        )
        .await
        .is_ok()
    }

    /// The screen once `ok` holds for it, read up to [CONFIRM_TRIES] times
    /// [CONFIRM_GAP] apart (a redraw may lag the keys); none if it never does.
    async fn screen_until(&self, c: &Client, ok: impl Fn(&str) -> bool) -> Option<String> {
        for i in 0..CONFIRM_TRIES {
            if i > 0 {
                tokio::time::sleep(CONFIRM_GAP).await;
            }
            if let Ok(s) = self.screen(c).await
                && ok(&s)
            {
                return Some(s);
            }
        }
        None
    }

    /// A note on a preview option (design D9): the digit moves the focus;
    /// once the screen shows that option focused, `n`; once it shows the
    /// notes field, the note with force, whose Enter saves it and answers.
    #[allow(clippy::too_many_arguments)]
    async fn answer_note(
        &self,
        c: &Client,
        id: &str,
        qs: &[question::Question],
        step: usize,
        i: usize,
        text: &str,
        digit: &[String],
    ) -> ChatAnswer {
        let here = |s: &str| question::progress(s, qs) == Progress::Question(step);
        if !self.press(c, digit).await {
            return ChatAnswer::Unconfirmed;
        }
        if self
            .screen_until(c, |s| here(s) && question::focused(s, i + 1))
            .await
            .is_none()
        {
            return ChatAnswer::Unconfirmed;
        }
        if !self.press(c, &["n".to_string()]).await {
            return ChatAnswer::Unconfirmed;
        }
        if self
            .screen_until(c, |s| here(s) && question::notes_open(s))
            .await
            .is_none()
        {
            return ChatAnswer::Unconfirmed;
        }
        if !self.type_forced(c, text).await {
            return ChatAnswer::Unconfirmed;
        }
        self.confirm(c, id, qs, step, &Answer::Option(i)).await
    }

    /// A multi-select answer (design section B, 5.1 desk round): the ticks;
    /// with own words, the text and, while its row shows unticked, an Enter;
    /// once the screen shows exactly the planned ticks, off the question; a
    /// call of one question then sends from its review step. Each step
    /// waits for the screen it depends on; a screen that never comes stops
    /// the answer, and nothing is sent again.
    #[allow(clippy::too_many_arguments)]
    async fn answer_multi(
        &self,
        c: &Client,
        id: &str,
        qs: &[question::Question],
        step: usize,
        ticked: &[usize],
        typed: Option<&str>,
        m: question::MultiPlan,
        submit: bool,
    ) -> ChatAnswer {
        let q = &qs[step];
        let free = q.options.len() + 1;
        let here = |s: &str| question::progress(s, qs) == Progress::Question(step);
        if !m.ticks.is_empty() && !self.press(c, &m.ticks).await {
            return ChatAnswer::Unconfirmed;
        }
        // The option rows as planned; without own words the free row unticked.
        let Some(mut s) = self
            .screen_until(c, |s| {
                here(s)
                    && question::options_ticked(s, q, ticked)
                    && (typed.is_some() || question::row_ticked(s, free) == Some(false))
            })
            .await
        else {
            return ChatAnswer::Unconfirmed;
        };
        if let Some(text) = typed {
            // Already in its row and ticked (a send that stopped after it): not again.
            if question::typed_row(&s, q, text) != Some(true) {
                if question::typed_row(&s, q, text).is_none() {
                    if !self.type_forced(c, text).await {
                        return ChatAnswer::Unconfirmed;
                    }
                    let Some(after) = self
                        .screen_until(c, |s| here(s) && question::typed_row(s, q, text).is_some())
                        .await
                    else {
                        return ChatAnswer::Unconfirmed;
                    };
                    s = after;
                }
                if question::typed_row(&s, q, text) == Some(false)
                    && !self.press(c, &["enter".to_string()]).await
                {
                    return ChatAnswer::Unconfirmed;
                }
                let Some(after) = self
                    .screen_until(c, |s| {
                        here(s)
                            && question::typed_row(s, q, text) == Some(true)
                            && question::ticks_shown(s, q, ticked, true)
                    })
                    .await
                else {
                    return ChatAnswer::Unconfirmed;
                };
                s = after;
            }
        }
        // Leaving the question, from where the screen shows the focus: on the
        // unnumbered button row ("Submit", or "Next" before another question;
        // Ezra's try) Enter; in the free row's editor Tab, then Enter once the
        // screen shows the button row in focus (5.4 review); on an option row
        // Tab alone.
        let _ = m.next;
        if question::submit_focused(&s) {
            if !self.press(c, &["enter".to_string()]).await {
                return ChatAnswer::Unconfirmed;
            }
        } else if question::focused(&s, free) {
            if !self.press(c, &["tab".to_string()]).await {
                return ChatAnswer::Unconfirmed;
            }
            if self
                .screen_until(c, |s| here(s) && question::submit_focused(s))
                .await
                .is_none()
            {
                return ChatAnswer::Unconfirmed;
            }
            if !self.press(c, &["enter".to_string()]).await {
                return ChatAnswer::Unconfirmed;
            }
        } else if !self.press(c, &["tab".to_string()]).await {
            return ChatAnswer::Unconfirmed;
        }
        if qs.len() > 1 || !submit {
            return self
                .confirm(c, id, qs, step, &Answer::Multi(ticked.to_vec()))
                .await;
        }
        // One question: its review step, "Submit answers" in focus.
        if self
            .screen_until(c, |s| question::progress(s, qs) == Progress::Review)
            .await
            .is_none()
        {
            return ChatAnswer::Unconfirmed;
        }
        if !self.press(c, &["enter".to_string()]).await {
            return ChatAnswer::Unconfirmed;
        }
        self.confirm(c, id, qs, step, &Answer::Submit).await
    }

    /// Whether the step arrived: the screen moved on, or the dialog is gone
    /// and the log holds the call's result.
    async fn confirm(
        &self,
        c: &Client,
        id: &str,
        qs: &[question::Question],
        step: usize,
        answer: &Answer,
    ) -> ChatAnswer {
        for i in 0..CONFIRM_TRIES {
            if i > 0 {
                tokio::time::sleep(CONFIRM_GAP).await;
            }
            let Ok(s) = self.screen(c).await else {
                continue;
            };
            match question::moved_on(answer, step, question::progress(&s, qs)) {
                Moved::Yes => return ChatAnswer::Done,
                Moved::No => {}
                Moved::AskLog => {
                    let r = self.state.lock().unwrap().plan_read();
                    if let Some(r) = r {
                        let got = read_chunk(c, &r).await;
                        self.state.lock().unwrap().take_read(got, Instant::now());
                    }
                    let closed = self
                        .state
                        .lock()
                        .unwrap()
                        .asked(id)
                        .is_some_and(|a| a.state != State::Pending);
                    if closed {
                        return ChatAnswer::Done;
                    }
                }
            }
        }
        ChatAnswer::Unconfirmed
    }

    /// Holds `tail_out` (the tail script's output) as the log of `session`
    /// at `path`, for the agent in front as `pid`; the log's end.
    #[cfg(test)]
    pub(crate) fn seed(&self, path: &str, session: &str, pid: i64, tail_out: &str) -> u64 {
        let t = Term {
            agent: Some(Agent::Claude),
            pid: Some(pid),
            status: None,
        };
        let mut st = self.state.lock().unwrap();
        st.take_lookup(
            &t,
            Some(Lookup::Found {
                path: path.into(),
                session: session.into(),
            }),
            Instant::now(),
        );
        let chunk = tail::parse_chunk(tail_out).expect("a tail output");
        let end = chunk.next;
        st.take_read(Ok(chunk), Instant::now());
        end
    }

    /// Holds the log's call with no result as if the hook's record had
    /// given it (the answer tests start from a held call).
    #[cfg(test)]
    pub(crate) fn hold_for_test(&self) {
        let a = self.state.lock().unwrap().question();
        *self.held.lock().unwrap() = a;
    }

    #[cfg(test)]
    pub(crate) fn set_multi_for_test(&self, on: bool) {
        self.multi_ok
            .store(on, std::sync::atomic::Ordering::Relaxed);
    }

    #[cfg(test)]
    pub(crate) async fn poll_for_test(&self) -> ChatPoll {
        self.poll_async(false).await
    }

    async fn poll_async(&self, event: bool) -> ChatPoll {
        let began = Instant::now();
        let mut times = Times::default();
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return self.view("", false, Times("not connected".into()), None);
        };
        let c = conn.client;
        let kind;
        let t = match c
            .call("terminal.get", json!({"terminal": self.address}))
            .await
        {
            Ok(t) => {
                kind = t["status"]["kind"].as_str().map(str::to_owned);
                term_of(&t)
            }
            Err(_) => {
                self.state
                    .lock()
                    .unwrap()
                    .take_read(Err("terminal".into()), Instant::now());
                return self.view("", false, times, None);
            }
        };
        times.mark("get", began);
        let now = Instant::now();
        let lookup = self.state.lock().unwrap().needs_lookup(&t, event, now);
        if lookup {
            let at = Instant::now();
            let (l, _) = look_up(&c, &t).await;
            times.mark("locate", at);
            self.state
                .lock()
                .unwrap()
                .take_lookup(&t, l, Instant::now());
        }
        self.state.lock().unwrap().see(&t, Instant::now());
        // A return reads the log's size first, then the new lines.
        for _ in 0..2 {
            let Some(r) = self.state.lock().unwrap().plan_read() else {
                break;
            };
            let at = Instant::now();
            let got = read_chunk(&c, &r).await;
            times.mark("tail", at);
            if !self.state.lock().unwrap().take_read(got, Instant::now()) {
                break;
            }
        }
        let question = self.question_for(&c, &t, kind.as_deref()).await;
        times.mark("total", began);
        let status = t.status.clone().unwrap_or_default();
        self.view(&status, may_send(&t), times, question)
    }

    fn view(
        &self,
        status: &str,
        in_front: bool,
        times: Times,
        question: Option<ChatQuestion>,
    ) -> ChatPoll {
        let st = self.state.lock().unwrap();
        let v = st.view();
        let session = st.shown().map(|(_, s)| s).unwrap_or_default();
        let found = match v.found {
            Kind::Found => ChatFound::Found,
            Kind::Empty => ChatFound::Empty,
            Kind::Missed => ChatFound::Missed,
            Kind::None => ChatFound::None,
        };
        let health = match &v.health {
            Health::Ok => ChatHealth::Ok,
            Health::Degraded { .. } => ChatHealth::Degraded,
            Health::Broken { reason } => ChatHealth::Broken {
                reason: reason.clone(),
            },
        };
        let can_send = in_front
            && matches!(found, ChatFound::Found | ChatFound::Empty)
            && !v.exited
            && !matches!(health, ChatHealth::Broken { .. });
        ChatPoll {
            found,
            answered: v.answered,
            agent: match v.agent {
                Some(Agent::Claude) => "claude".into(),
                Some(Agent::Codex) => "codex".into(),
                None => String::new(),
            },
            title: v.title.unwrap_or_default(),
            version: v.version.unwrap_or_default(),
            status: status.to_owned(),
            health,
            problem: v.problem.unwrap_or_default(),
            exited: v.exited,
            can_send,
            cut: v.cut,
            rows: v.rows.iter().map(|r| chat_row(r, &session)).collect(),
            gauge_used: v.gauge.map(|g| g.used),
            gauge_window: v.gauge.and_then(|g| g.window),
            gauge_percent: v.gauge.and_then(|g| g.percent()),
            queued: v.queued.clone(),
            timings: times.0,
            question,
        }
    }

    async fn fetch_image(&self, key: &str) -> Option<Vec<u8>> {
        self.fetch_image_checked(key).await.ok().flatten()
    }

    // Ok(None) is reserved for a positively confirmed absent file. Every
    // failed or indeterminate recheck must preserve the picture on the phone.
    async fn fetch_image_checked(&self, key: &str) -> Result<Option<Vec<u8>>, ()> {
        let b64 = base64::engine::general_purpose::STANDARD;
        if let Some(data) = key.strip_prefix("inline:") {
            return b64.decode(data).map(Some).map_err(|_| ());
        }
        let log = {
            let st = self.state.lock().unwrap();
            let session = st.shown().map(|(_, s)| s);
            let path = match st.lookup() {
                Some(Lookup::Found { path, .. }) => Some(path.clone()),
                _ => None,
            };
            session.zip(path)
        };
        if key.starts_with("copy:") {
            let (file, copy) =
                image_sources(key, log.as_ref().map(|(s, _)| s.as_str())).ok_or(())?;
            if let Some(bytes) = Box::pin(self.fetch_image_checked(&file)).await? {
                return Ok(Some(bytes));
            }
            if let Some(bytes) = self.images.lock().unwrap().get(&copy).cloned() {
                return Ok(Some(bytes));
            }
            let bytes = Box::pin(self.fetch_image_checked(&copy)).await?.ok_or(())?;
            let mut cache = self.images.lock().unwrap();
            if cache.values().map(Vec::len).sum::<usize>() + bytes.len() > IMAGE_CACHE {
                cache.clear();
            }
            cache.insert(copy, bytes.clone());
            return Ok(Some(bytes));
        }
        let file = file_path(key);
        let held = file
            .map(|p| held_time(&self.files.lock().unwrap(), p))
            .unwrap_or_default();
        let mut argv = image_argv(
            key,
            log.as_ref().map(|(s, p)| (s.as_str(), p.as_str())),
            &held,
        )
        .ok_or(())?;
        if file.is_some() {
            argv.as_array_mut().ok_or(())?.push(json!("checked"));
        }
        let c = self.core.live_conn(&self.machine).ok_or(())?.client;
        let got = c
            .call_timeout(
                "machine.exec",
                json!({"argv": argv, "timeout": 30}),
                Duration::from_secs(40),
            )
            .await
            .map_err(|_| ())?;
        if got["truncated"] == true
            || got["timed_out"] == true
            || got.get("code").is_some_and(|code| code.as_i64() != Some(0))
        {
            return Err(());
        }
        let out = b64
            .decode(got["stdout"].as_str().ok_or(())?)
            .map_err(|_| ())?;
        match file {
            Some(path) => {
                let mut files = self.files.lock().unwrap();
                if out == b"missing\n" {
                    files.remove(path);
                    return Ok(None);
                }
                let reply = image::file_reply(&out).ok_or(())?;
                let (mtime, size) = reply.0.split_once(' ').ok_or(())?;
                mtime.parse::<i64>().map_err(|_| ())?;
                let size = size.trim().parse::<usize>().map_err(|_| ())?;
                if size == 0
                    || size as u64 > image::FILE_MAX
                    || reply.1.as_ref().is_some_and(|bytes| bytes.len() != size)
                {
                    return Err(());
                }
                if files.values().map(|(_, b)| b.len()).sum::<usize>() > IMAGE_CACHE {
                    files.clear();
                }
                file_image(&mut files, path, Some(reply))
                    .map(Some)
                    .ok_or(())
            }
            None => image::decode(&out).map(Some).ok_or(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codex_copy_keeps_file_first_and_rejects_another_session() {
        let key = chat_images(
            &[Image::FileWithFallback {
                path: "/work/website:a.png".into(),
                line: 12,
                copy: Box::new(Image::InLog {
                    line: 24,
                    n: 0,
                    media_type: String::new(),
                }),
            }],
            "session-a",
        )
        .remove(0)
        .key;
        assert_eq!(
            image_sources(&key, Some("session-a")),
            Some((
                "file:12:/work/website:a.png".into(),
                "log:session-a:24:0".into()
            ))
        );
        assert!(image_sources(&key, Some("session-b")).is_none());
        assert!(image_sources(&key, None).is_none());
    }

    #[test]
    fn image_rechecks_recreated_file_and_separates_copy_cache() {
        use herdr_proto::wire;
        use tokio::io::AsyncWriteExt;
        struct NoDial;
        impl crate::link::Dialer for NoDial {
            fn dial(&self, _: crate::link::Target) -> crate::link::Dialing {
                Box::pin(async { Err(crate::link::DialError::Rejected) })
            }
        }
        let dir = std::env::current_dir()
            .unwrap()
            .join("../../target/mobile-image-cache-test");
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("website.png");
        let _ = std::fs::remove_file(&file);
        let log = dir.join("invented.jsonl");
        std::fs::write(&log, "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"output\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,Y29weQ==\"}]}}\n").unwrap();
        let rt = tokio::runtime::Runtime::new().unwrap();
        let hub;
        {
            let _enter = rt.enter();
            hub = HubCore::start(dir.clone(), Arc::new(NoDial));
            let (app, remote) = tokio::io::duplex(65536);
            let (r, w) = tokio::io::split(app);
            let client = Client::start(r, w);
            let mut live = super::super::Live::paused();
            live.epoch = 1;
            hub.state
                .lock()
                .unwrap()
                .live
                .insert("workstation".into(), live);
            let conn = super::super::Conn {
                epoch: 1,
                client,
                sizes: tokio::sync::watch::channel(Arc::new(HashMap::new())).1,
            };
            hub.conns.lock().unwrap().insert(
                "workstation".into(),
                tokio::sync::watch::channel(Some(conn)).0,
            );
            rt.spawn(async move {
                let (mut r, mut w) = tokio::io::split(remote);
                while let Ok(Some(frame)) = wire::read_frame(&mut r).await {
                    if frame.channel != 0 || frame.kind != wire::DATA { continue; }
                    let req: Value = serde_json::from_slice(&frame.payload).unwrap();
                    assert_eq!(req["method"], "machine.exec");
                    let args: Vec<_> = req["params"]["argv"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
                    let out = std::process::Command::new(args[0]).args(&args[1..]).output().unwrap();
                    assert!(out.status.success());
                    let reply = json!({"jsonrpc":"2.0","id":req["id"],"result":{"stdout":base64::engine::general_purpose::STANDARD.encode(out.stdout),"truncated":false}});
                    if w.write_all(&wire::encode(0, wire::DATA, reply.to_string().as_bytes())).await.is_err() { break; }
                }
            });
        }
        let chat = Chat::new(hub.clone(), "workstation".into(), "t1".into());
        chat.state.lock().unwrap().take_lookup(
            &Term {
                agent: Some(Agent::Codex),
                ..Default::default()
            },
            Some(Lookup::Found {
                path: log.to_str().unwrap().into(),
                session: "session-a".into(),
            }),
            Instant::now(),
        );
        let key = chat_images(
            &[Image::FileWithFallback {
                path: file.to_str().unwrap().into(),
                line: 1,
                copy: Box::new(Image::InLog {
                    line: 0,
                    n: 0,
                    media_type: String::new(),
                }),
            }],
            "session-a",
        )[0]
        .key
        .clone();
        assert_eq!(chat.image(key.clone()).unwrap(), b"copy");
        std::fs::write(&file, b"file").unwrap();
        assert_eq!(
            chat.image(key.clone()).unwrap(),
            b"file",
            "recreated file wins over a cached copy"
        );
        std::fs::write(&file, b"new-file").unwrap();
        assert_eq!(chat.image(key.clone()).unwrap(), b"new-file");
        std::fs::remove_file(&file).unwrap();
        assert_eq!(chat.image(key.clone()).unwrap(), b"copy");
        assert!(!chat.images.lock().unwrap().contains_key(&key));
        assert_eq!(
            chat.images
                .lock()
                .unwrap()
                .get("log:session-a:0:0")
                .unwrap(),
            b"copy"
        );
        hub.shutdown();
    }

    #[test]
    fn failed_file_rechecks_never_restore_a_cached_copy() {
        use herdr_proto::wire;
        use tokio::io::AsyncWriteExt;
        struct NoDial;
        impl crate::link::Dialer for NoDial {
            fn dial(&self, _: crate::link::Target) -> crate::link::Dialing {
                Box::pin(async { Err(crate::link::DialError::Rejected) })
            }
        }
        let dir = std::env::current_dir()
            .unwrap()
            .join("../../target/mobile-image-failed-recheck-test");
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("website.png");
        let _ = std::fs::remove_file(&file);
        let log = dir.join("invented.jsonl");
        std::fs::write(&log, "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"output\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,Y29weQ==\"}]}}\n").unwrap();
        let rt = tokio::runtime::Runtime::new().unwrap();
        let failure = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hub;
        {
            let _enter = rt.enter();
            hub = HubCore::start(dir.clone(), Arc::new(NoDial));
            let (app, remote) = tokio::io::duplex(65536);
            let (r, w) = tokio::io::split(app);
            let client = Client::start(r, w);
            let mut live = super::super::Live::paused();
            live.epoch = 1;
            hub.state
                .lock()
                .unwrap()
                .live
                .insert("workstation".into(), live);
            let conn = super::super::Conn {
                epoch: 1,
                client,
                sizes: tokio::sync::watch::channel(Arc::new(HashMap::new())).1,
            };
            hub.conns.lock().unwrap().insert(
                "workstation".into(),
                tokio::sync::watch::channel(Some(conn)).0,
            );
            let failure = failure.clone();
            rt.spawn(async move {
                let (mut r, mut w) = tokio::io::split(remote);
                while let Ok(Some(frame)) = wire::read_frame(&mut r).await {
                    if frame.channel != 0 || frame.kind != wire::DATA { continue; }
                    let req: Value = serde_json::from_slice(&frame.payload).unwrap();
                    assert_eq!(req["method"], "machine.exec");
                    let args: Vec<_> = req["params"]["argv"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
                    let mode = failure.load(std::sync::atomic::Ordering::SeqCst);
                    let result = match mode {
                        1 => Some(json!({"stdout":"", "code":1, "truncated":false})),
                        2 => Some(json!({"stdout":"", "code":null, "timed_out":true, "truncated":false})),
                        3 => Some(json!({"stdout":"", "code":0, "truncated":true})),
                        4 => Some(json!({"stdout":"", "code":0, "truncated":false})),
                        6 => Some(json!({"stdout":base64::engine::general_purpose::STANDARD.encode(b"123 10\npartial"), "code":0, "truncated":false})),
                        7 => Some(json!({"stdout":base64::engine::general_purpose::STANDARD.encode(b"missing\n"), "code":null, "timed_out":true, "truncated":false})),
                        8 => Some(json!({"stdout":base64::engine::general_purpose::STANDARD.encode(b"bad-header\n"), "code":0, "truncated":false})),
                        5 => {
                            let reply = json!({"jsonrpc":"2.0","id":req["id"],"error":{"code":-32000,"message":"read failed"}});
                            w.write_all(&wire::encode(0, wire::DATA, reply.to_string().as_bytes())).await.unwrap();
                            continue;
                        }
                        _ => None,
                    };
                    if let Some(result) = result {
                        let reply = json!({"jsonrpc":"2.0","id":req["id"],"result":result});
                        w.write_all(&wire::encode(0, wire::DATA, reply.to_string().as_bytes())).await.unwrap();
                        continue;
                    }
                    let out = std::process::Command::new(args[0]).args(&args[1..]).output().unwrap();
                    let reply = json!({"jsonrpc":"2.0","id":req["id"],"result":{"stdout":base64::engine::general_purpose::STANDARD.encode(out.stdout),"code":out.status.code(),"timed_out":false,"truncated":false}});
                    if w.write_all(&wire::encode(0, wire::DATA, reply.to_string().as_bytes())).await.is_err() { break; }
                }
            });
        }
        let chat = Chat::new(hub.clone(), "workstation".into(), "t1".into());
        chat.state.lock().unwrap().take_lookup(
            &Term {
                agent: Some(Agent::Codex),
                ..Default::default()
            },
            Some(Lookup::Found {
                path: log.to_str().unwrap().into(),
                session: "session-a".into(),
            }),
            Instant::now(),
        );
        let key = chat_images(
            &[Image::FileWithFallback {
                path: file.to_str().unwrap().into(),
                line: 1,
                copy: Box::new(Image::InLog {
                    line: 0,
                    n: 0,
                    media_type: String::new(),
                }),
            }],
            "session-a",
        )[0]
        .key
        .clone();
        assert_eq!(chat.image(key.clone()).unwrap(), b"copy");
        std::fs::write(&file, b"file").unwrap();
        assert_eq!(
            chat.image(key.clone()).unwrap(),
            b"file",
            "recreated file wins over a cached copy"
        );
        std::fs::write(&file, b"new-file").unwrap();
        assert_eq!(chat.image(key.clone()).unwrap(), b"new-file");
        std::fs::remove_file(&file).unwrap();
        assert_eq!(chat.image(key.clone()).unwrap(), b"copy");
        assert!(!chat.images.lock().unwrap().contains_key(&key));
        assert_eq!(
            chat.images
                .lock()
                .unwrap()
                .get("log:session-a:0:0")
                .unwrap(),
            b"copy"
        );
        std::fs::write(&file, b"current-file").unwrap();
        assert_eq!(chat.image(key.clone()).unwrap(), b"current-file");
        for mode in 1..=8 {
            failure.store(mode, std::sync::atomic::Ordering::SeqCst);
            assert_eq!(chat.image(key.clone()), None, "failed recheck mode {mode}");
        }
        failure.store(0, std::sync::atomic::Ordering::SeqCst);
        // A non-file and an oversized file are read failures, not absence.
        std::fs::remove_file(&file).unwrap();
        std::fs::create_dir(&file).unwrap();
        assert_eq!(chat.image(key.clone()), None, "directory is not absent");
        std::fs::remove_dir(&file).unwrap();
        std::fs::write(&file, vec![0; image::FILE_MAX as usize + 1]).unwrap();
        assert_eq!(chat.image(key.clone()), None, "oversized is not absent");
        std::fs::remove_file(&file).unwrap();
        assert_eq!(
            chat.image(key.clone()).unwrap(),
            b"copy",
            "confirmed absent"
        );
        hub.conns.lock().unwrap().remove("workstation");
        assert_eq!(chat.image(key), None, "no connection is not absence");
        hub.shutdown();
    }

    #[test]
    fn image_paths_go_first_one_per_line() {
        let p = vec!["/f/t1/a.png".to_owned(), "/f/t1/b.png".to_owned()];
        assert_eq!(
            with_images(&p, "What is this?"),
            "/f/t1/a.png\n/f/t1/b.png\nWhat is this?"
        );
        assert_eq!(with_images(&p[..1], ""), "/f/t1/a.png", "an image alone");
        assert_eq!(with_images(&[], "hi"), "hi");
    }

    #[test]
    fn commands_are_sent_as_typed() {
        assert_eq!(with_images(&[], "/clear"), "/clear");
        assert_eq!(with_images(&[], "!ls"), "!ls");
        assert_eq!(
            with_images(&[], "/compact keep the API notes"),
            "/compact keep the API notes"
        );
    }

    #[test]
    fn an_image_key_names_what_to_run() {
        let open = Some(("s1", "/logs/s.jsonl"));
        let a = image_argv("log:s1:2048:1", open, "").unwrap();
        assert_eq!(a[4], "/logs/s.jsonl");
        assert_eq!((a[5].as_str(), a[6].as_str()), (Some("2048"), Some("1")));
        assert_eq!(a[2], image::LOG_SCRIPT);
        let f = image_argv("file:812:/work/a b:c.png", None, "1731590000 2048").unwrap();
        assert_eq!(
            (f[2].as_str(), f[4].as_str(), f[5].as_str()),
            (
                Some(image::FILE_SCRIPT),
                Some("/work/a b:c.png"),
                Some("1731590000 2048")
            )
        );
        assert!(
            image_argv("log:s1:2048:1", None, "").is_none(),
            "no log open yet"
        );
        assert!(image_argv("missing", None, "").is_none());
        let keys = chat_images(
            &[
                Image::InLog {
                    line: 9,
                    n: 0,
                    media_type: "image/png".into(),
                },
                Image::File {
                    path: "/x.png".into(),
                    line: 812,
                },
                Image::File {
                    path: "/x.png".into(),
                    line: 990,
                },
            ],
            "s1",
        );
        assert_eq!(
            keys[1].key, "file:812:/x.png",
            "each record's own key: a reused path is a new image"
        );
        assert_eq!(
            keys[2].key, "file:990:/x.png",
            "the same path viewed again in the same folded row"
        );
        assert!(
            image_argv(&keys[0].key, open, "").is_some()
                && image_argv(&keys[1].key, None, "").is_some()
        );
    }

    #[test]
    fn a_codex_image_is_held_by_path_and_file_time() {
        let mut held = HashMap::new();
        let got = |t: &str, b: Option<&[u8]>| Some((t.to_owned(), b.map(<[u8]>::to_vec)));
        assert_eq!(
            file_image(&mut held, "/s.png", got("1 3", Some(b"old"))),
            Some(b"old".to_vec())
        );
        assert_eq!(held_time(&held, "/s.png"), "1 3");
        assert_eq!(
            file_image(&mut held, "/s.png", got("1 3", None)),
            Some(b"old".to_vec()),
            "unchanged: only its time came"
        );
        assert_eq!(
            file_image(&mut held, "/s.png", got("2 3", Some(b"new"))),
            Some(b"new".to_vec()),
            "written again"
        );
        assert_eq!(file_image(&mut held, "/s.png", None), None, "gone");
        assert_eq!(held_time(&held, "/s.png"), "");
        assert_eq!(
            file_image(&mut HashMap::new(), "/t.png", got("1 3", None)),
            None,
            "a time not held, no bytes"
        );
    }

    #[test]
    fn an_image_of_an_earlier_session_is_not_read_from_the_current_log() {
        // After /clear the new log can have a line at the same offset.
        assert!(image_argv("log:s1:2048:1", Some(("s2", "/logs/s2.jsonl")), "").is_none());
    }

    fn get(name: &str, argv: &[&str], state: Option<&str>) -> Value {
        json!({"foreground": {"name": name, "argv": argv, "pid": 7}, "status": {"state": state}})
    }

    #[test]
    fn the_terminal_is_read_as_agent_process_and_status() {
        let t = term_of(&get(
            "node",
            &[
                "node",
                "/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js",
            ],
            Some("idle"),
        ));
        assert_eq!(
            (t.agent, t.pid, t.status.as_deref()),
            (Some(Agent::Claude), Some(7), Some("idle"))
        );
        let shell = term_of(&get("zsh", &["zsh"], None));
        assert_eq!((shell.agent, shell.status), (None, None));
    }

    #[test]
    fn sends_only_to_an_agent_that_can_take_it() {
        let t = |name: &str, state: Option<&str>| term_of(&get(name, &[name], state));
        assert!(may_send(&t("claude", Some("idle"))));
        assert!(may_send(&t("codex", Some("working"))));
        assert!(may_send(&t("claude", Some("done"))));
        assert!(
            !may_send(&t("claude", Some("blocked"))),
            "a dialog would take the text"
        );
        assert!(
            !may_send(&t("claude", None)),
            "no status yet: a start-up prompt"
        );
        assert!(!may_send(&t("zsh", Some("idle"))), "a shell would run it");
    }

    #[test]
    fn a_send_whose_outcome_is_unknown_is_not_given_back_as_a_draft() {
        // A timeout or a lost connection after the send went out: the
        // message may be typed, so the bubble stays and follows the log.
        assert_eq!(send_outcome(&Ok(json!({}))), Sent::Typed);
        assert_eq!(send_outcome(&Err(CallError::OutcomeUnknown)), Sent::Unsure);
        assert_eq!(send_outcome(&Err(CallError::Lost)), Sent::Unsure);
        let no = CallError::Rpc {
            kind: Some("conflict".into()),
            message: "status is working".into(),
            hint: None,
        };
        assert_eq!(
            send_outcome(&Err(no)),
            Sent::Refused("status is working".into()),
            "the core said no"
        );
    }

    #[test]
    fn the_second_enter_goes_only_to_the_process_and_session_sent_to() {
        let s1 = Lookup::Found {
            path: "/l/s1.jsonl".into(),
            session: "s1".into(),
        };
        let s2 = Lookup::Found {
            path: "/l/s2.jsonl".into(),
            session: "s2".into(),
        };
        let to = send::Target {
            agent: Agent::Claude,
            pid: 7,
            proc_start: Some(100),
            session: "s1".into(),
        };
        let seen = |agent, pid, status, l| Seen {
            agent,
            pid: Some(pid),
            proc_start: Some(100),
            status,
            lookup: l,
        };
        let claude = Some(Agent::Claude);
        let press =
            |sent_to: Option<&send::Target>, seen: &Seen| enter_again_params("t3", sent_to, seen);
        assert_eq!(
            press(Some(&to), &seen(claude, 7, Some("idle"), &s1)),
            Some(json!({"terminal": "t3", "text": " ", "if": "idle"})),
            "a send, not a key: the core refuses it if a dialog opened or the status changed"
        );
        assert_eq!(
            press(Some(&to), &seen(claude, 7, Some("working"), &s1)),
            Some(json!({"terminal": "t3", "text": " ", "if": "working"}))
        );
        assert_eq!(
            press(Some(&to), &seen(claude, 7, Some("done"), &s1)),
            Some(json!({"terminal": "t3", "text": " "})),
            "the core has no if for done; it still refuses a dialog"
        );
        assert_eq!(
            press(None, &seen(claude, 7, Some("idle"), &s1)),
            None,
            "nothing sent"
        );
        assert_eq!(
            press(Some(&to), &seen(claude, 7, Some("blocked"), &s1)),
            None,
            "a dialog would take it"
        );
        assert_eq!(
            press(Some(&to), &seen(claude, 8, Some("idle"), &s1)),
            None,
            "another process"
        );
        assert_eq!(
            press(Some(&to), &seen(claude, 7, Some("idle"), &s2)),
            None,
            "another session"
        );
        assert_eq!(
            press(Some(&to), &seen(None, 7, Some("idle"), &s1)),
            None,
            "exited into the shell"
        );
    }

    #[test]
    fn a_row_key_names_its_line_and_index() {
        let r = Row {
            key: (2048, 1),
            item: Item::Note {
                text: "Interrupted".into(),
            },
        };
        let c = chat_row(&r, "s1");
        assert_eq!(
            (c.key.as_str(), c.kind.as_str(), c.text.as_str()),
            ("2048:1", "note", "Interrupted")
        );
    }
}

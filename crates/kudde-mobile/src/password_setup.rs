//! The password way of first setup (first-run-walkthrough design D12): sign in
//! once with the account's password, run exactly the Phone key variant of the
//! setup command through `sh -s`, sign out, keep nothing.
//!
//! The host key is settled first: both the probe and the setup connect pinned
//! to the confirmed fingerprint, and [`HostKeyCheck`] refuses any other key
//! during key exchange, before a user name or password is sent. The session
//! asks for one `exec` and nothing else: no pty, environment, agent or X11
//! forwarding, no port forwarding.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client::{self, Handle, KeyboardInteractiveAuthResponse};
use russh::{ChannelMsg, MethodKind};
use zeroize::Zeroizing;

use crate::ssh::{HostKeyCheck, ReachKind, SshError, connect_error, setup_command};

/// Time to dial (connect and key exchange), as `link.rs` allows, and in all.
const DIAL: Duration = Duration::from_secs(20);
const TOTAL: Duration = Duration::from_secs(60);
/// Rounds of keyboard-interactive before giving up.
const MAX_ROUNDS: usize = 3;

/// The account's password, held only for one call: wiped on drop, never
/// printed (`Debug` says `<redacted>`), no `Display`.
#[derive(uniffi::Object)]
pub struct SetupPassword {
    text: Zeroizing<String>,
}

#[uniffi::export]
impl SetupPassword {
    #[uniffi::constructor]
    pub fn new(text: String) -> Arc<Self> {
        Arc::new(Self {
            text: Zeroizing::new(text),
        })
    }
}

impl std::fmt::Debug for SetupPassword {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("<redacted>")
    }
}

/// What the probe found out about signing in with a password.
#[derive(Debug, uniffi::Enum)]
pub enum ProbeOutcome {
    /// The machine offers `password`.
    Password,
    /// It offers only `keyboard-interactive`; the first prompt's text.
    KeyboardInteractive {
        prompt: String,
    },
    /// Neither (or `none` was accepted): no password to give.
    NoPasswordMethod,
    /// The machine presented another host key; nothing was sent.
    HostKeyChanged {
        presented: String,
    },
    NetworkOff,
    Unreachable {
        kind: ReachKind,
        detail: String,
    },
}

/// How a password setup ended.
#[derive(Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SetupOutcome {
    Done,
    WrongPassword,
    NoPasswordMethod,
    /// More than a password is asked for (a code, an echoing prompt, several
    /// prompts, partial success): nothing was answered.
    MoreThanPassword,
    /// Dropped or out of time after signing in: whether the key went in is not known.
    Dropped,
    /// The script ended with this status.
    ScriptFailed {
        code: u32,
    },
    HostKeyChanged {
        presented: String,
    },
    NetworkOff,
    Unreachable {
        kind: ReachKind,
        detail: String,
    },
}

#[derive(Clone, Copy, Debug)]
pub struct SetupLimits {
    pub dial_ms: u64,
    pub total_ms: u64,
}

impl Default for SetupLimits {
    fn default() -> Self {
        Self {
            dial_ms: DIAL.as_millis() as u64,
            total_ms: TOTAL.as_millis() as u64,
        }
    }
}

enum Early {
    Changed(String),
    NetworkOff,
    Unreachable(ReachKind, String),
}

impl From<Early> for SetupOutcome {
    fn from(e: Early) -> Self {
        match e {
            Early::Changed(presented) => SetupOutcome::HostKeyChanged { presented },
            Early::NetworkOff => SetupOutcome::NetworkOff,
            Early::Unreachable(kind, detail) => SetupOutcome::Unreachable { kind, detail },
        }
    }
}

impl From<Early> for ProbeOutcome {
    fn from(e: Early) -> Self {
        match e {
            Early::Changed(presented) => ProbeOutcome::HostKeyChanged { presented },
            Early::NetworkOff => ProbeOutcome::NetworkOff,
            Early::Unreachable(kind, detail) => ProbeOutcome::Unreachable { kind, detail },
        }
    }
}

/// Connects pinned to `pinned`; nothing is sent before the key matches.
async fn dial(
    host: &str,
    port: u16,
    pinned: &str,
    limit: Duration,
) -> Result<Handle<HostKeyCheck>, Early> {
    let presented = Arc::new(Mutex::new(None));
    let handler = HostKeyCheck {
        pinned: Some(pinned.to_owned()),
        accept_unpinned: false,
        presented: presented.clone(),
    };
    let config = Arc::new(client::Config::default());
    let connected = tokio::time::timeout(limit, client::connect(config, (host, port), handler))
        .await
        .map_err(|_| Early::Unreachable(ReachKind::NoAnswer, "timed out".into()))?;
    match connected {
        Ok(handle) => Ok(handle),
        Err(e) => {
            let seen = presented.lock().unwrap().clone();
            if let Some((fingerprint, _)) = seen
                && fingerprint != pinned
            {
                return Err(Early::Changed(fingerprint));
            }
            Err(match connect_error(e) {
                SshError::NetworkPermissionOff => Early::NetworkOff,
                SshError::Connect { kind, detail } => Early::Unreachable(kind, detail),
                other => Early::Unreachable(ReachKind::Other, other.to_string()),
            })
        }
    }
}

fn gone() -> Early {
    Early::Unreachable(ReachKind::Other, "the connection closed".into())
}

async fn offered(handle: &mut Handle<HostKeyCheck>, user: &str) -> Result<Offer, Early> {
    match handle.authenticate_none(user).await.map_err(|_| gone())? {
        client::AuthResult::Success => Ok(Offer::Nothing),
        client::AuthResult::Failure {
            remaining_methods, ..
        } => Ok(if remaining_methods.contains(&MethodKind::Password) {
            Offer::Password
        } else if remaining_methods.contains(&MethodKind::KeyboardInteractive) {
            Offer::Keyboard
        } else {
            Offer::Nothing
        }),
    }
}

enum Offer {
    Password,
    Keyboard,
    Nothing,
}

async fn probe_async(
    host: &str,
    port: u16,
    user: &str,
    pinned: &str,
    limits: SetupLimits,
) -> ProbeOutcome {
    let run = async {
        let mut handle = dial(host, port, pinned, Duration::from_millis(limits.dial_ms)).await?;
        let outcome = match offered(&mut handle, user).await? {
            Offer::Password => ProbeOutcome::Password,
            Offer::Nothing => ProbeOutcome::NoPasswordMethod,
            Offer::Keyboard => match handle
                .authenticate_keyboard_interactive_start(user, None)
                .await
                .map_err(|_| gone())?
            {
                KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                    ProbeOutcome::KeyboardInteractive {
                        prompt: prompts
                            .first()
                            .map(|p| p.prompt.clone())
                            .unwrap_or_default(),
                    }
                }
                _ => ProbeOutcome::NoPasswordMethod,
            },
        };
        let _ = handle
            .disconnect(russh::Disconnect::ByApplication, "", "en")
            .await;
        Ok::<_, Early>(outcome)
    };
    match tokio::time::timeout(Duration::from_millis(limits.total_ms), run).await {
        Ok(Ok(outcome)) => outcome,
        Ok(Err(early)) => early.into(),
        Err(_) => ProbeOutcome::Unreachable {
            kind: ReachKind::NoAnswer,
            detail: "timed out".into(),
        },
    }
}

/// Signs in. `Ok(None)` is signed in; `Ok(Some(outcome))` an ending.
async fn sign_in(
    handle: &mut Handle<HostKeyCheck>,
    user: &str,
    password: &SetupPassword,
) -> Result<Option<SetupOutcome>, Early> {
    let wrong = |partial: bool| {
        Some(if partial {
            SetupOutcome::MoreThanPassword
        } else {
            SetupOutcome::WrongPassword
        })
    };
    match offered(handle, user).await? {
        Offer::Nothing => Ok(Some(SetupOutcome::NoPasswordMethod)),
        Offer::Password => {
            let result = handle
                .authenticate_password(user, (*password.text).clone())
                .await
                .map_err(|_| gone())?;
            Ok(match result {
                client::AuthResult::Success => None,
                client::AuthResult::Failure {
                    partial_success, ..
                } => wrong(partial_success),
            })
        }
        Offer::Keyboard => {
            let mut sent = false;
            let mut response = handle
                .authenticate_keyboard_interactive_start(user, None)
                .await
                .map_err(|_| gone())?;
            for _ in 0..MAX_ROUNDS {
                match response {
                    KeyboardInteractiveAuthResponse::Success => return Ok(None),
                    KeyboardInteractiveAuthResponse::Failure {
                        partial_success, ..
                    } => return Ok(wrong(partial_success)),
                    KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                        let answers = if prompts.is_empty() {
                            Vec::new()
                        } else if !sent && prompts.len() == 1 && !prompts[0].echo {
                            sent = true;
                            vec![(*password.text).clone()]
                        } else {
                            return Ok(Some(SetupOutcome::MoreThanPassword));
                        };
                        response = handle
                            .authenticate_keyboard_interactive_respond(answers)
                            .await
                            .map_err(|_| gone())?;
                    }
                }
            }
            match response {
                KeyboardInteractiveAuthResponse::Success => Ok(None),
                KeyboardInteractiveAuthResponse::Failure {
                    partial_success, ..
                } => Ok(wrong(partial_success)),
                _ => Ok(Some(SetupOutcome::MoreThanPassword)),
            }
        }
    }
}

/// One `exec` of `sh -s`, the script on standard input, then end of input.
/// Standard output and error are drained and dropped.
async fn run_script(handle: &mut Handle<HostKeyCheck>, script: &str) -> SetupOutcome {
    let Ok(mut channel) = handle.channel_open_session().await else {
        return SetupOutcome::Dropped;
    };
    if channel.exec(true, "sh -s").await.is_err()
        || channel.data(script.as_bytes()).await.is_err()
        || channel.eof().await.is_err()
    {
        return SetupOutcome::Dropped;
    }
    let mut exit = None;
    while let Some(msg) = channel.wait().await {
        match msg {
            ChannelMsg::ExitStatus { exit_status } => exit = Some(exit_status),
            ChannelMsg::Failure => return SetupOutcome::Dropped,
            _ => {}
        }
    }
    match exit {
        Some(0) => SetupOutcome::Done,
        Some(code) => SetupOutcome::ScriptFailed { code },
        None => SetupOutcome::Dropped,
    }
}

#[allow(clippy::too_many_arguments)]
async fn setup_async(
    host: &str,
    port: u16,
    user: &str,
    pinned: &str,
    password: &SetupPassword,
    script: &str,
    limits: SetupLimits,
) -> SetupOutcome {
    let signed_in = AtomicBool::new(false);
    let run = async {
        let mut handle = dial(host, port, pinned, Duration::from_millis(limits.dial_ms)).await?;
        if let Some(ended) = sign_in(&mut handle, user, password).await? {
            let _ = handle
                .disconnect(russh::Disconnect::ByApplication, "", "en")
                .await;
            return Ok(ended);
        }
        signed_in.store(true, Ordering::Relaxed);
        let outcome = run_script(&mut handle, script).await;
        let _ = handle
            .disconnect(russh::Disconnect::ByApplication, "", "en")
            .await;
        Ok::<_, Early>(outcome)
    };
    match tokio::time::timeout(Duration::from_millis(limits.total_ms), run).await {
        Ok(Ok(outcome)) => outcome,
        // A drop after signing in cannot say whether the key went in.
        Ok(Err(_)) if signed_in.load(Ordering::Relaxed) => SetupOutcome::Dropped,
        Ok(Err(early)) => early.into(),
        Err(_) if signed_in.load(Ordering::Relaxed) => SetupOutcome::Dropped,
        Err(_) => SetupOutcome::Unreachable {
            kind: ReachKind::NoAnswer,
            detail: "timed out".into(),
        },
    }
}

fn runtime() -> Result<tokio::runtime::Runtime, SshError> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| SshError::Connect {
            kind: ReachKind::Other,
            detail: e.to_string(),
        })
}

/// Finds out, pinned to `pinned`, what sign-in the machine offers.
#[uniffi::export]
pub fn probe_password_methods(
    host: String,
    port: u16,
    user: String,
    pinned: String,
) -> ProbeOutcome {
    match runtime() {
        Ok(rt) => rt.block_on(probe_async(
            &host,
            port,
            &user,
            &pinned,
            SetupLimits::default(),
        )),
        Err(e) => ProbeOutcome::Unreachable {
            kind: ReachKind::Other,
            detail: e.to_string(),
        },
    }
}

/// Installs the phone key with the password, once: one connection, no retry.
#[uniffi::export]
pub fn setup_with_password(
    host: String,
    port: u16,
    user: String,
    pinned: String,
    password: Arc<SetupPassword>,
    spki: Vec<u8>,
    comment: String,
) -> Result<SetupOutcome, SshError> {
    setup_with_password_limits(
        host,
        port,
        user,
        pinned,
        password,
        spki,
        comment,
        SetupLimits::default(),
    )
}

/// [`setup_with_password`] with other time limits (tests use short ones).
#[allow(clippy::too_many_arguments)]
pub fn setup_with_password_limits(
    host: String,
    port: u16,
    user: String,
    pinned: String,
    password: Arc<SetupPassword>,
    spki: Vec<u8>,
    comment: String,
    limits: SetupLimits,
) -> Result<SetupOutcome, SshError> {
    let script = setup_command(spki, comment, None)?;
    Ok(runtime()?.block_on(setup_async(
        &host, port, &user, &pinned, &password, &script, limits,
    )))
}

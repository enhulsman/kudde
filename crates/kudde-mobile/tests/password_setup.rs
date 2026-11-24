//! The password way (first-run-walkthrough design D12) against an in-process
//! `russh::server`: a throwaway sshd cannot check passwords without root. Each
//! test sets the methods the fake machine offers, its prompts and the exit
//! status of what it is asked to run, and reads what the machine received.

use std::borrow::Cow;
use std::net::SocketAddr;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use kudde_mobile::{
    ProbeOutcome, ReachKind, SetupLimits, SetupOutcome, SetupPassword,
    host_key_fingerprint_command, probe_password_methods, setup_command, setup_with_password,
    setup_with_password_limits,
};
use p256::ecdsa::SigningKey;
use p256::pkcs8::EncodePublicKey;
use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::ssh_key::{HashAlg, PrivateKey};
use russh::server::{Auth, Msg, Response, Server as _, Session};
use russh::{Channel, ChannelId, MethodKind, MethodSet};
use tokio::net::TcpListener;

const GOOD: &str = "sentinel-Hunter2-sentinel";
const COMMENT: &str = "kudde";

fn host_key(seed: u8) -> PrivateKey {
    PrivateKey::from(Ed25519Keypair::from_seed(&[seed; 32]))
}

fn fingerprint(key: &PrivateKey) -> String {
    key.public_key().fingerprint(HashAlg::Sha256).to_string()
}

fn phone_spki() -> Vec<u8> {
    let mut bytes = [0x22u8; 32];
    bytes[31] = 9;
    SigningKey::from_slice(&bytes)
        .unwrap()
        .verifying_key()
        .to_public_key_der()
        .unwrap()
        .into_vec()
}

#[derive(Clone, Copy, PartialEq)]
enum Exit {
    /// Exit status n, with text on stderr.
    Status(u32),
    /// Close the connection when the input ends, before any exit status.
    Drop,
    /// Never answer the command.
    Hang,
}

#[derive(Clone)]
struct Script {
    methods: Vec<MethodKind>,
    none_accepts: bool,
    /// Keyboard-interactive rounds: each round's prompts (text, echo).
    rounds: Vec<Vec<(&'static str, bool)>>,
    /// Refuse the password with partial success (more is needed).
    partial: bool,
    exit: Exit,
}

impl Script {
    fn password() -> Self {
        Script {
            methods: vec![MethodKind::Password],
            none_accepts: false,
            rounds: vec![],
            partial: false,
            exit: Exit::Status(0),
        }
    }
    fn kbd(rounds: Vec<Vec<(&'static str, bool)>>) -> Self {
        Script {
            methods: vec![MethodKind::KeyboardInteractive],
            rounds,
            ..Script::password()
        }
    }
}

#[derive(Default)]
struct Seen {
    users: Vec<String>,
    auth_calls: u32,
    passwords: Vec<String>,
    answers: Vec<Vec<String>>,
    execs: Vec<String>,
    stdin: Vec<u8>,
    eof: bool,
    closed: bool,
    disconnected: bool,
}

#[derive(Default)]
struct Counters {
    pty: AtomicU32,
    env: AtomicU32,
    agent: AtomicU32,
    x11: AtomicU32,
    forward: AtomicU32,
}

#[derive(Clone)]
struct Fake {
    script: Script,
    seen: Arc<Mutex<Seen>>,
    counters: Arc<Counters>,
    round: usize,
    session: bool,
}

impl Drop for Fake {
    fn drop(&mut self) {
        if self.session {
            self.seen.lock().unwrap().disconnected = true;
        }
    }
}

impl russh::server::Server for Fake {
    type Handler = Fake;
    fn new_client(&mut self, _: Option<SocketAddr>) -> Fake {
        let mut handler = self.clone();
        handler.round = 0;
        handler.session = true;
        handler
    }
}

impl Fake {
    fn count(&self) {
        self.seen.lock().unwrap().auth_calls += 1;
    }
    fn reject(&self) -> Auth {
        Auth::Reject {
            proceed_with_methods: None,
            partial_success: self.script.partial,
        }
    }
}

impl russh::server::Handler for Fake {
    type Error = russh::Error;

    async fn auth_none(&mut self, user: &str) -> Result<Auth, Self::Error> {
        self.count();
        self.seen.lock().unwrap().users.push(user.into());
        Ok(if self.script.none_accepts {
            Auth::Accept
        } else {
            Auth::reject()
        })
    }

    async fn auth_password(&mut self, user: &str, password: &str) -> Result<Auth, Self::Error> {
        self.count();
        {
            let mut seen = self.seen.lock().unwrap();
            seen.users.push(user.into());
            seen.passwords.push(password.into());
        }
        Ok(if password == GOOD && !self.script.partial {
            Auth::Accept
        } else {
            self.reject()
        })
    }

    async fn auth_keyboard_interactive<'a>(
        &'a mut self,
        user: &str,
        _submethods: &str,
        response: Option<Response<'a>>,
    ) -> Result<Auth, Self::Error> {
        self.count();
        if let Some(response) = response {
            let answers: Vec<String> = response
                .map(|b| String::from_utf8_lossy(&b).into_owned())
                .collect();
            let accepted = answers.iter().any(|a| a == GOOD);
            self.seen.lock().unwrap().answers.push(answers);
            if accepted && self.round >= self.script.rounds.len() {
                return Ok(Auth::Accept);
            }
            if self.round >= self.script.rounds.len() {
                return Ok(self.reject());
            }
        } else {
            self.seen.lock().unwrap().users.push(user.into());
        }
        let prompts = self.script.rounds[self.round].clone();
        self.round += 1;
        Ok(Auth::Partial {
            name: Cow::Borrowed(""),
            instructions: Cow::Borrowed(""),
            prompts: Cow::Owned(
                prompts
                    .into_iter()
                    .map(|(p, echo)| (Cow::Borrowed(p), echo))
                    .collect(),
            ),
        })
    }

    async fn channel_open_session(
        &mut self,
        _channel: Channel<Msg>,
        reply: russh::server::ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        reply.accept().await;
        Ok(())
    }

    async fn exec_request(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.seen
            .lock()
            .unwrap()
            .execs
            .push(String::from_utf8_lossy(data).into_owned());
        session.channel_success(channel)?;
        Ok(())
    }

    async fn data(
        &mut self,
        _channel: ChannelId,
        data: &[u8],
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.seen.lock().unwrap().stdin.extend_from_slice(data);
        Ok(())
    }

    async fn channel_eof(
        &mut self,
        channel: ChannelId,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.seen.lock().unwrap().eof = true;
        match self.script.exit {
            Exit::Status(code) => {
                session.extended_data(channel, 1, b"stderr says: not for the app\n".to_vec())?;
                session.exit_status_request(channel, code)?;
                session.eof(channel)?;
                session.close(channel)?;
            }
            Exit::Drop => return Err(russh::Error::Disconnect),
            Exit::Hang => {}
        }
        Ok(())
    }

    async fn channel_close(
        &mut self,
        _channel: ChannelId,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.seen.lock().unwrap().closed = true;
        Ok(())
    }

    async fn pty_request(
        &mut self,
        channel: ChannelId,
        _: &str,
        _: u32,
        _: u32,
        _: u32,
        _: u32,
        _: &[(russh::Pty, u32)],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.counters.pty.fetch_add(1, Ordering::Relaxed);
        session.channel_failure(channel)?;
        Ok(())
    }

    async fn env_request(
        &mut self,
        channel: ChannelId,
        _: &str,
        _: &str,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.counters.env.fetch_add(1, Ordering::Relaxed);
        session.channel_failure(channel)?;
        Ok(())
    }

    async fn agent_request(
        &mut self,
        channel: ChannelId,
        session: &mut Session,
    ) -> Result<bool, Self::Error> {
        self.counters.agent.fetch_add(1, Ordering::Relaxed);
        let _ = (channel, session);
        Ok(false)
    }

    async fn x11_request(
        &mut self,
        channel: ChannelId,
        _: bool,
        _: &str,
        _: &str,
        _: u32,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.counters.x11.fetch_add(1, Ordering::Relaxed);
        session.channel_failure(channel)?;
        Ok(())
    }

    async fn tcpip_forward(
        &mut self,
        _: &str,
        _: &mut u32,
        _: &mut Session,
    ) -> Result<bool, Self::Error> {
        self.counters.forward.fetch_add(1, Ordering::Relaxed);
        Ok(false)
    }
}

struct Machine {
    port: u16,
    pinned: String,
    seen: Arc<Mutex<Seen>>,
    counters: Arc<Counters>,
    runtime: Option<tokio::runtime::Runtime>,
}

impl Drop for Machine {
    fn drop(&mut self) {
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

/// A machine whose host key is seed `key_seed`; `pinned` is the fingerprint of
/// seed 1, the key the phone confirmed.
fn machine(script: Script, key_seed: u8) -> Machine {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
        .unwrap();
    let seen = Arc::new(Mutex::new(Seen::default()));
    let counters = Arc::new(Counters::default());
    let mut methods = MethodSet::empty();
    for m in &script.methods {
        methods.push(*m);
    }
    let config = Arc::new(russh::server::Config {
        keys: vec![host_key(key_seed)],
        methods,
        auth_rejection_time: Duration::from_millis(1),
        auth_rejection_time_initial: Some(Duration::ZERO),
        inactivity_timeout: Some(Duration::from_secs(30)),
        ..Default::default()
    });
    let mut fake = Fake {
        script,
        seen: seen.clone(),
        counters: counters.clone(),
        round: 0,
        session: false,
    };
    let listener = runtime.block_on(TcpListener::bind("127.0.0.1:0")).unwrap();
    let port = listener.local_addr().unwrap().port();
    runtime.spawn(async move {
        let _ = fake.run_on_socket(config, &listener).await;
    });
    Machine {
        port,
        pinned: fingerprint(&host_key(1)),
        seen,
        counters,
        runtime: Some(runtime),
    }
}

fn setup(m: &Machine, password: &str) -> SetupOutcome {
    setup_with_password(
        "127.0.0.1".into(),
        m.port,
        "alice".into(),
        m.pinned.clone(),
        SetupPassword::new(password.into()),
        phone_spki(),
        COMMENT.into(),
    )
    .unwrap()
}

fn probe(m: &Machine) -> ProbeOutcome {
    probe_password_methods("127.0.0.1".into(), m.port, "alice".into(), m.pinned.clone())
}

fn assert_no_sentinel(what: &str, text: &str) {
    assert!(!text.contains(GOOD), "{what} holds the password: {text}");
}

// The probe.

#[test]
fn the_probe_reads_the_offered_methods() {
    let both = Script {
        methods: vec![MethodKind::Password, MethodKind::KeyboardInteractive],
        ..Script::kbd(vec![vec![("Password: ", false)]])
    };
    assert!(matches!(probe(&machine(both, 1)), ProbeOutcome::Password));

    let kbd = machine(Script::kbd(vec![vec![("Verification code: ", false)]]), 1);
    match probe(&kbd) {
        ProbeOutcome::KeyboardInteractive { prompt } => assert_eq!(prompt, "Verification code: "),
        other => panic!("{other:?}"),
    }
    assert!(
        kbd.seen.lock().unwrap().answers.is_empty(),
        "the probe answers nothing"
    );

    let key_only = Script {
        methods: vec![MethodKind::PublicKey],
        ..Script::password()
    };
    assert!(matches!(
        probe(&machine(key_only, 1)),
        ProbeOutcome::NoPasswordMethod
    ));
    let none = Script {
        none_accepts: true,
        ..Script::password()
    };
    assert!(matches!(
        probe(&machine(none, 1)),
        ProbeOutcome::NoPasswordMethod
    ));
}

#[test]
fn a_probe_that_meets_another_host_key_sends_no_user_name() {
    let m = machine(Script::password(), 2);
    match probe(&m) {
        ProbeOutcome::HostKeyChanged { presented } => {
            assert_eq!(presented, fingerprint(&host_key(2)))
        }
        other => panic!("{other:?}"),
    }
    let seen = m.seen.lock().unwrap();
    assert_eq!(seen.auth_calls, 0);
    assert!(seen.users.is_empty());
}

// The password, then exactly the Phone key variant on standard input.

#[test]
fn the_password_signs_in_then_one_exec_runs_the_phone_key_variant_from_stdin() {
    let m = machine(Script::password(), 1);
    assert!(matches!(setup(&m, GOOD), SetupOutcome::Done));
    {
        let seen = m.seen.lock().unwrap();
        assert_eq!(seen.execs, vec!["sh -s".to_owned()]);
        let expected = setup_command(phone_spki(), COMMENT.into(), None).unwrap();
        assert_eq!(String::from_utf8(seen.stdin.clone()).unwrap(), expected);
        assert!(seen.eof, "the input ended");
        assert_eq!(seen.passwords, vec![GOOD.to_owned()]);
    }
    for counter in [
        &m.counters.pty,
        &m.counters.env,
        &m.counters.agent,
        &m.counters.x11,
        &m.counters.forward,
    ] {
        assert_eq!(counter.load(Ordering::Relaxed), 0);
    }
    // The server sees the connection go away.
    for _ in 0..50 {
        if m.seen.lock().unwrap().disconnected {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(m.seen.lock().unwrap().disconnected);
}

#[test]
fn the_bytes_sent_leave_the_restricted_line_when_run_with_sh() {
    let m = machine(Script::password(), 1);
    assert!(matches!(setup(&m, GOOD), SetupOutcome::Done));
    let stdin = m.seen.lock().unwrap().stdin.clone();
    let home = std::env::temp_dir().join(format!("pw-home-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    std::fs::create_dir(&home).unwrap();
    let mut child = Command::new("sh")
        .arg("-s")
        .env_clear()
        .env("HOME", &home)
        .env("PATH", "/usr/bin:/bin")
        .stdin(Stdio::piped())
        .spawn()
        .unwrap();
    use std::io::Write as _;
    child.stdin.take().unwrap().write_all(&stdin).unwrap();
    assert!(child.wait().unwrap().success());
    let file = std::fs::read_to_string(home.join(".ssh/authorized_keys")).unwrap();
    let line = kudde_mobile::authorized_keys_line(phone_spki(), COMMENT.into()).unwrap();
    assert_eq!(file.lines().filter(|l| *l == line).count(), 1);
    let _ = std::fs::remove_dir_all(&home);
}

// Keyboard-interactive.

#[test]
fn keyboard_interactive_gives_the_password_to_one_non_echoing_prompt() {
    let m = machine(Script::kbd(vec![vec![("Password: ", false)]]), 1);
    assert!(matches!(setup(&m, GOOD), SetupOutcome::Done));
    assert_eq!(m.seen.lock().unwrap().answers, vec![vec![GOOD.to_owned()]]);
}

#[test]
fn an_empty_round_first_gets_nothing_and_the_password_goes_once() {
    let m = machine(Script::kbd(vec![vec![], vec![("Password: ", false)]]), 1);
    assert!(matches!(setup(&m, GOOD), SetupOutcome::Done));
    let answers = m.seen.lock().unwrap().answers.clone();
    let sent = answers.iter().flatten().filter(|a| *a == GOOD).count();
    assert_eq!(sent, 1);
    assert!(answers[0].is_empty(), "the empty round got no answer");
}

#[test]
fn a_prompt_after_the_password_stops_with_no_answer() {
    let m = machine(
        Script::kbd(vec![
            vec![("Password: ", false)],
            vec![("Verification code: ", false)],
        ]),
        1,
    );
    assert!(matches!(setup(&m, GOOD), SetupOutcome::MoreThanPassword));
    let seen = m.seen.lock().unwrap();
    assert_eq!(
        seen.answers,
        vec![vec![GOOD.to_owned()]],
        "nothing for the code"
    );
    assert!(seen.execs.is_empty());
}

#[test]
fn an_echoing_prompt_two_prompts_and_partial_success_stop_with_no_answer() {
    for rounds in [
        vec![vec![("Username: ", true)]],
        vec![vec![("Password: ", false), ("Code: ", false)]],
    ] {
        let m = machine(Script::kbd(rounds), 1);
        assert!(matches!(setup(&m, GOOD), SetupOutcome::MoreThanPassword));
        assert!(m.seen.lock().unwrap().answers.is_empty());
    }
    let partial = Script {
        partial: true,
        ..Script::password()
    };
    let m = machine(partial, 1);
    assert!(matches!(setup(&m, GOOD), SetupOutcome::MoreThanPassword));
    assert!(m.seen.lock().unwrap().execs.is_empty());
}

// Refusals and endings.

#[test]
fn a_wrong_password_runs_nothing() {
    let m = machine(Script::password(), 1);
    assert!(matches!(setup(&m, "not it"), SetupOutcome::WrongPassword));
    assert!(m.seen.lock().unwrap().execs.is_empty());
}

#[test]
fn no_password_method_is_reported_without_sending_one() {
    let key_only = Script {
        methods: vec![MethodKind::PublicKey],
        ..Script::password()
    };
    let m = machine(key_only, 1);
    assert!(matches!(setup(&m, GOOD), SetupOutcome::NoPasswordMethod));
    assert!(m.seen.lock().unwrap().passwords.is_empty());
}

#[test]
fn a_host_key_other_than_the_pinned_one_ends_before_any_authentication() {
    let m = machine(Script::password(), 2);
    match setup(&m, GOOD) {
        SetupOutcome::HostKeyChanged { presented } => {
            assert_eq!(presented, fingerprint(&host_key(2)))
        }
        other => panic!("{other:?}"),
    }
    let seen = m.seen.lock().unwrap();
    assert_eq!(seen.auth_calls, 0);
    assert!(seen.users.is_empty() && seen.passwords.is_empty());
}

#[test]
fn exit_codes_map_to_their_outcomes_and_stderr_is_never_shown() {
    for (exit, code) in [
        (Exit::Status(0), 0),
        (Exit::Status(1), 1),
        (Exit::Status(3), 3),
    ] {
        let m = machine(
            Script {
                exit,
                ..Script::password()
            },
            1,
        );
        let outcome = setup(&m, GOOD);
        match (code, &outcome) {
            (0, SetupOutcome::Done) => {}
            (c, SetupOutcome::ScriptFailed { code }) if c == *code => {}
            _ => panic!("exit {code}: {outcome:?}"),
        }
        assert!(!format!("{outcome:?}").contains("stderr says"));
    }
    let dropped = machine(
        Script {
            exit: Exit::Drop,
            ..Script::password()
        },
        1,
    );
    assert!(matches!(setup(&dropped, GOOD), SetupOutcome::Dropped));
}

#[test]
fn one_connection_per_call_and_no_retry() {
    let m = machine(Script::password(), 1);
    let _ = setup(&m, "wrong");
    assert_eq!(m.seen.lock().unwrap().passwords.len(), 1);
}

#[test]
fn a_drop_before_authentication_maps_to_its_reach_kind() {
    // Nothing listens.
    let closed = {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
    let outcome = setup_with_password(
        "127.0.0.1".into(),
        closed,
        "alice".into(),
        "SHA256:x".into(),
        SetupPassword::new(GOOD.into()),
        phone_spki(),
        COMMENT.into(),
    )
    .unwrap();
    assert!(matches!(
        outcome,
        SetupOutcome::Unreachable {
            kind: ReachKind::Refused,
            ..
        }
    ));
    match probe_password_methods("127.0.0.1".into(), closed, "a".into(), "SHA256:x".into()) {
        ProbeOutcome::Unreachable { kind, .. } => assert_eq!(kind, ReachKind::Refused),
        other => panic!("{other:?}"),
    }
}

// Time limits, with short limits in place of 20 and 60 seconds.

fn limits(dial_ms: u64, total_ms: u64) -> SetupLimits {
    SetupLimits { dial_ms, total_ms }
}

#[test]
fn running_out_of_time_dialling_is_no_answer() {
    // Accepts the connection and says nothing: the handshake never ends.
    let silent = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = silent.local_addr().unwrap().port();
    let outcome = setup_with_password_limits(
        "127.0.0.1".into(),
        port,
        "alice".into(),
        "SHA256:x".into(),
        SetupPassword::new(GOOD.into()),
        phone_spki(),
        COMMENT.into(),
        limits(300, 5_000),
    )
    .unwrap();
    assert!(
        matches!(
            outcome,
            SetupOutcome::Unreachable {
                kind: ReachKind::NoAnswer,
                ..
            }
        ),
        "{outcome:?}"
    );
    drop(silent);
}

#[test]
fn running_out_of_time_after_signing_in_reads_as_dropped() {
    let m = machine(
        Script {
            exit: Exit::Hang,
            ..Script::password()
        },
        1,
    );
    let outcome = setup_with_password_limits(
        "127.0.0.1".into(),
        m.port,
        "alice".into(),
        m.pinned.clone(),
        SetupPassword::new(GOOD.into()),
        phone_spki(),
        COMMENT.into(),
        limits(5_000, 700),
    )
    .unwrap();
    assert!(matches!(outcome, SetupOutcome::Dropped), "{outcome:?}");
}

// The fingerprint line and the password type.

#[test]
fn the_fingerprint_command_is_the_one_inside_the_setup_command() {
    for algorithm in ["ED25519", "ECDSA", "RSA", "DSA"] {
        let invocation = host_key_fingerprint_command(algorithm.into());
        let full = setup_command(phone_spki(), COMMENT.into(), Some(algorithm.into())).unwrap();
        assert!(
            full.ends_with(&format!("\n  {invocation}\n)")),
            "{algorithm}: {invocation}"
        );
    }
}

#[test]
fn the_password_never_appears_in_an_outcome_or_error_and_its_debug_is_redacted() {
    let password = SetupPassword::new(GOOD.into());
    assert_eq!(format!("{password:?}"), "<redacted>");
    assert_no_sentinel("password Debug", &format!("{password:?}"));
    for script in [
        Script::password(),
        Script::kbd(vec![vec![("Password: ", false)], vec![("Code: ", false)]]),
        Script {
            exit: Exit::Status(1),
            ..Script::password()
        },
    ] {
        let m = machine(script, 1);
        let outcome = setup(&m, GOOD);
        assert_no_sentinel("outcome", &format!("{outcome:?}"));
    }
    let error = setup_with_password(
        "127.0.0.1".into(),
        1,
        "alice".into(),
        "SHA256:x".into(),
        SetupPassword::new(GOOD.into()),
        vec![0x30, 0x00],
        COMMENT.into(),
    )
    .unwrap_err();
    assert_no_sentinel("error Debug", &format!("{error:?}"));
    assert_no_sentinel("error Display", &error.to_string());
}

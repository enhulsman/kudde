//! A timing harness, not a check: sends the kept answers to a question that
//! waits in a real terminal on this desk, through `herdr-core connect` as the
//! phone's SSH link runs it, and prints how long each desk call took and when
//! the folded line arrived. Ignored; run by hand with a throwaway agent:
//!
//! KUDDE_TIMING_TERMINAL=<address> KUDDE_TIMING_ANSWERS='skip;multi:0,1:tea;pick:0' \
//!   cargo test -p kudde-mobile desk_send_timing -- --ignored --nocapture
//!
//! Answers, one per question: `skip`, `pick:<n>`, `other:<text>`, or
//! `multi:<n,n>[:<text>]`. The network the phone adds is one round trip per
//! call; the harness prints the count of calls.

use super::*;
use crate::hub::chat::{Chat, ChatAnswer, ChatChoice};
use std::os::fd::OwnedFd;
use std::os::unix::net::UnixStream;
use std::process::{Command, Stdio};

struct LocalDialer;

impl Dialer for LocalDialer {
    fn dial(
        &self,
        _target: Target,
    ) -> Pin<Box<dyn Future<Output = Result<Link, DialError>> + Send>> {
        Box::pin(async move {
            let (ours, theirs) =
                UnixStream::pair().map_err(|e| DialError::Failed(e.to_string()))?;
            let mut cmd = Command::new("herdr-core");
            cmd.arg("connect");
            for (k, _) in std::env::vars().filter(|(k, _)| k.starts_with("HC_")) {
                cmd.env_remove(k);
            }
            let out = theirs
                .try_clone()
                .map_err(|e| DialError::Failed(e.to_string()))?;
            let child = cmd
                .stdin(Stdio::from(OwnedFd::from(theirs)))
                .stdout(Stdio::from(OwnedFd::from(out)))
                .stderr(Stdio::null())
                .spawn()
                .map_err(|e| DialError::Failed(e.to_string()))?;
            ours.set_nonblocking(true).unwrap();
            let s = tokio::net::UnixStream::from_std(ours)
                .map_err(|e| DialError::Failed(e.to_string()))?;
            let (r, w) = tokio::io::split(s);
            let (tx, rx) = oneshot::channel::<Exit>();
            Ok(Link {
                reader: Box::new(r),
                writer: Box::new(w),
                exit: rx,
                session: Box::new((child, tx)),
            })
        })
    }
}

fn answers_from(spec: &str) -> Vec<ChatChoice> {
    spec.split(';')
        .map(|a| {
            let mut p = a.splitn(3, ':');
            match (p.next(), p.next(), p.next()) {
                (Some("skip"), ..) => ChatChoice::Skip,
                (Some("pick"), Some(n), _) => ChatChoice::Pick {
                    index: n.parse().unwrap(),
                },
                (Some("other"), Some(t), _) => ChatChoice::Other { text: t.into() },
                (Some("multi"), Some(ns), t) => ChatChoice::Multi {
                    indices: ns.split(',').map(|n| n.parse().unwrap()).collect(),
                    text: t.map(str::to_owned),
                },
                _ => panic!("unknown answer {a}"),
            }
        })
        .collect()
}

#[test]
#[ignore = "drives a real terminal on this desk"]
fn desk_send_timing() {
    let addr = std::env::var("KUDDE_TIMING_TERMINAL").expect("KUDDE_TIMING_TERMINAL");
    let answers =
        answers_from(&std::env::var("KUDDE_TIMING_ANSWERS").expect("KUDDE_TIMING_ANSWERS"));
    let dir = scratch("desk-timing");
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let hub = {
        let _in = rt.enter();
        HubCore::start(dir.clone(), Arc::new(LocalDialer))
    };
    hub.add_machine("desk", "localhost", 22, "dev").unwrap();
    hub.confirm_host_key("desk", "SHA256:local").unwrap();
    hub.resume();
    let began = std::time::Instant::now();
    while hub.live_conn("desk").is_none() {
        assert!(began.elapsed() < Duration::from_secs(20), "never connected");
        std::thread::sleep(Duration::from_millis(50));
    }
    let chat = Chat::new(hub.clone(), "desk".into(), addr);
    let began = std::time::Instant::now();
    let q = loop {
        let p = chat.poll(false);
        if let Some(q) = p.question.filter(|q| q.readable) {
            break q;
        }
        assert!(
            began.elapsed() < Duration::from_secs(60),
            "no readable question"
        );
        std::thread::sleep(Duration::from_millis(300));
    };
    let t = std::time::Instant::now();
    chat.poll(false);
    println!("one poll while blocked: {} ms", t.elapsed().as_millis());
    chat.trace.lock().unwrap().clear();
    let sent = std::time::Instant::now();
    let a = chat.send_answers(q.id.clone(), answers, 0);
    let took = sent.elapsed();
    let trace = std::mem::take(&mut *chat.trace.lock().unwrap());
    let mut at = Duration::ZERO;
    for (what, d) in &trace {
        at += *d;
        println!("{:>6} ms  +{:>4}  {what}", at.as_millis(), d.as_millis());
    }
    println!(
        "send: {a:?} in {} ms, {} desk calls",
        took.as_millis(),
        trace.iter().filter(|(w, _)| w != "gap").count()
    );
    assert_eq!(a, ChatAnswer::Done);
    loop {
        let p = chat.poll(true);
        let line = p
            .rows
            .iter()
            .flat_map(|r| &r.calls)
            .rev()
            .find(|c| c.name == "AskUserQuestion")
            .filter(|c| c.done)
            .map(|c| c.title.clone());
        if p.question.is_none()
            && let Some(line) = line
        {
            println!(
                "folded line {line:?} at {} ms after Send",
                sent.elapsed().as_millis()
            );
            break;
        }
        assert!(sent.elapsed() < Duration::from_secs(60), "no folded line");
        std::thread::sleep(Duration::from_millis(50));
    }
    hub.shutdown();
    let _ = std::fs::remove_dir_all(&dir);
}

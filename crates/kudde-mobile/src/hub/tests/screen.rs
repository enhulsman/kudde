//! Task 8.3: the plain-text read of a terminal's screen for the tab overview
//! (spaces-navigation design D11; spec "Tiles show their screens", "No
//! picture from the desk").

use super::*;

const ADDR: &str = "t1";

fn finished() -> Value {
    agent(ADDR, "working", 0)
}

/// A connected machine whose `t1` has finished unseen.
async fn done_unseen(r: &mut Rig) -> Core {
    r.add_trusted(WS, WS_KEY);
    r.hub.resume();
    let mut core = r.core().await;
    core.connect("0.1.15", vec![finished()]).await;
    r.until_state(WS, connected).await;
    core.event(
        1,
        "status",
        ADDR,
        json!({"status": {"state": "idle", "app": "claude"}, "records": [], "reported": true}),
    )
    .await;
    core.event(2, "turn", ADDR, json!({"turn": 1})).await;
    r.until("t1 done", |s| {
        rows_of(s, WS) == [(ADDR.into(), AgentAttention::Done)]
    })
    .await;
    core
}

#[tokio::test(start_paused = true)]
async fn the_read_sends_exactly_the_plain_screen_request() {
    let mut r = rig("sc-exact");
    let mut core = done_unseen(&mut r).await;
    let hub = r.hub.clone();
    let read = tokio::spawn(async move { hub.read_screen_async(WS, ADDR).await });
    let req = core.expect("terminal.read").await;
    assert_eq!(
        req["params"],
        json!({"terminal": ADDR, "lines": 0}),
        "no caller, no scroll: {req}"
    );
    core.reply(
        &req,
        json!({"text": "$ cargo build\n   Compiling", "complete": true, "how": "screen"}),
    )
    .await;
    assert_eq!(
        read.await.unwrap().as_deref(),
        Some("$ cargo build\n   Compiling")
    );
}

#[tokio::test(start_paused = true)]
async fn the_read_leaves_the_terminal_unseen_and_attaches_nothing() {
    let mut r = rig("sc-unseen");
    let mut core = done_unseen(&mut r).await;
    let hub = r.hub.clone();
    let read = tokio::spawn(async move { hub.read_screen_async(WS, ADDR).await });
    let req = core.expect("terminal.read").await;
    core.reply(
        &req,
        json!({"text": "x", "complete": true, "how": "screen"}),
    )
    .await;
    read.await.unwrap();
    // Nothing else reached the core, and the row is still Done (not seen).
    let got = tokio::time::timeout(Duration::from_secs(10), core.request()).await;
    assert!(got.is_err(), "unexpected request: {:?}", got.unwrap());
    assert_eq!(
        rows_of(&r.hub.snapshot(), WS),
        [(ADDR.into(), AgentAttention::Done)]
    );
}

#[tokio::test(start_paused = true)]
async fn an_error_from_the_desk_gives_no_picture() {
    let mut r = rig("sc-error");
    let mut core = done_unseen(&mut r).await;
    let hub = r.hub.clone();
    let read = tokio::spawn(async move { hub.read_screen_async(WS, ADDR).await });
    let req = core.expect("terminal.read").await;
    core.refuse(&req, "not_found", "no such terminal").await;
    assert_eq!(read.await.unwrap(), None);
}

#[tokio::test(start_paused = true)]
async fn an_empty_screen_gives_no_picture() {
    let mut r = rig("sc-empty");
    let mut core = done_unseen(&mut r).await;
    for text in ["", "  \n \n"] {
        let hub = r.hub.clone();
        let read = tokio::spawn(async move { hub.read_screen_async(WS, ADDR).await });
        let req = core.expect("terminal.read").await;
        core.reply(
            &req,
            json!({"text": text, "complete": true, "how": "screen"}),
        )
        .await;
        assert_eq!(read.await.unwrap(), None, "{text:?}");
    }
}

#[tokio::test(start_paused = true)]
async fn no_connection_gives_no_picture_and_sends_nothing() {
    let r = rig("sc-offline");
    assert_eq!(r.hub.read_screen_async(WS, ADDR).await, None);
}

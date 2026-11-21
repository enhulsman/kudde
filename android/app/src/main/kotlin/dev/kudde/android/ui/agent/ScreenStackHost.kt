package dev.kudde.android.ui.agent

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Destination lifetime is separate from the seekable animation's scalar progress. */
class ScreenStackHostState<D : Any> internal constructor(
    initial: D,
    internal val animationClock: MonotonicFrameClock?,
) {
    constructor(initial: D) : this(initial, null)

    internal val seekable = SeekableTransitionState(0f)
    internal var animatedProgress: State<Float> = mutableStateOf(0f)
    val progress: Float get() = animatedProgress.value
    var running by mutableStateOf(false)
        internal set
    internal var settled by mutableStateOf(initial)
    internal var outgoing by mutableStateOf(initial)
    internal var target by mutableStateOf<D?>(initial)
    internal var motion by mutableStateOf(AgentMotion.toTerminal)
    internal var job: Job? = null
    internal var gesture = false
    // Identifies the latest operation, so cancellation of an old gesture cannot undo a new one.
    internal var revision = 0

    internal suspend fun begin(to: D?, spec: AgentMotionSpec) {
        val retainedProgress = if (running) progress else 0f
        if (!running) seekable.snapTo(0f)
        outgoing = if (running) target ?: outgoing else settled
        target = to
        motion = spec
        running = true
        seekable.seekTo(retainedProgress.coerceIn(0f, 1f), 1f)
    }

    internal suspend fun finish() {
        if (motion.progressSpec is SnapSpec<*>) seekable.snapTo(1f)
        else seekable.animateTo(1f, motion.progressSpec)
        target?.let { settled = it }
        outgoing = settled
        running = false
    }

    internal suspend fun cancel() {
        if (motion.progressSpec !is SnapSpec<*>) seekable.animateTo(0f, motion.progressSpec)
        seekable.snapTo(0f)
        target = settled
        outgoing = settled
        running = false
    }
}

/**
 * Only the resting destination is composed. During motion both lifetimes exist,
 * but an inert sibling catches all touches and the outgoing subtree has no semantics
 * or active Back handlers. The callback must read live navigation state (keeper.back).
 * A null [backTarget] represents leaving the agent; its parent supplies the home screen.
 */
@Composable
fun <D : Any> ScreenStackHost(
    top: D,
    modifier: Modifier = Modifier,
    state: ScreenStackHostState<D> = remember { ScreenStackHostState(top) },
    backTarget: () -> D? = { null },
    onBack: () -> Unit = {},
    transitionFor: (D, D?, AgentChangeReason) -> AgentMotionSpec = { from, to, reason ->
        AgentMotion.transitionFor(from, to, reason)
    },
    content: @Composable (D) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestTop by rememberUpdatedState(top)
    val latestBackTarget by rememberUpdatedState(backTarget)
    val latestBack by rememberUpdatedState(onBack)
    val latestMotion by rememberUpdatedState(transitionFor)
    val scope = rememberCoroutineScope { state.animationClock ?: EmptyCoroutineContext }
    val requested = remember(top) {
        val from = state.target ?: state.settled
        if (top == from) null else from to transitionFor(from, top, AgentMotion.reason(from, top))
    }
    val instantChange = requested?.second?.progressSpec is SnapSpec<*> && top != state.settled
    val transition = rememberTransition(state.seekable, label = "agent screen")
    state.animatedProgress = transition.animateFloat(
        transitionSpec = { state.motion.progressSpec }, label = "screen progress",
    ) { it }

    fun clearInput() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    // Registered before content: the destination's own inner Back steps take priority.
    PredictiveBackHandler { events ->
        val revision = ++state.revision
        state.job?.cancel()
        state.job = currentCoroutineContext().job
        state.gesture = true
        val from = latestTop
        val to = latestBackTarget()
        val reason = if (to == null) AgentChangeReason.Leave else AgentChangeReason.Pop
        try {
            withContext(state.animationClock ?: EmptyCoroutineContext) {
                // A cancelled gesture restores the navigation target, even if its
                // preceding enter animation had not finished yet.
                state.settled = from
                state.begin(to, latestMotion(from, to, reason))
                events.collect { event -> state.seekable.seekTo(event.progress.coerceIn(0f, 1f), 1f) }
                // Cancel never changes navigation or clears focus; commit does both.
                clearInput()
                state.finish()
                if (state.revision == revision) {
                    state.gesture = false
                    latestBack()
                }
            }
        } catch (cancelled: CancellationException) {
            if (state.revision == revision) {
                // Rollback outlives this cancelled handler, but belongs to the host
                // scope so a new change can interrupt it at its current progress.
                state.job = scope.launch {
                    state.cancel()
                    if (state.revision == revision) state.gesture = false
                }
            }
            throw cancelled
        }
    }

    LaunchedEffect(top) {
        if ((!state.running && top == state.settled) || (state.running && top == state.target)) return@LaunchedEffect
        ++state.revision
        state.gesture = false
        state.job?.cancel()
        clearInput()
        val from = requested?.first ?: state.target ?: state.settled
        val spec = requested?.second ?: latestMotion(from, top, AgentMotion.reason(from, top))
        if (spec.progressSpec is SnapSpec<*>) {
            // The placeholder is a cut in this frame; it must not await animation frames.
            state.settled = top
            state.outgoing = top
            state.target = top
            state.motion = spec
            state.running = false
        } else {
            state.job = scope.launch {
                state.begin(top, spec)
                state.finish()
            }
        }
    }
    DisposableEffect(top) {
        // Backstop for a poll deciding the base, including an instant cut. A preview
        // destination's disposal on gesture cancellation must not hide the keyboard.
        onDispose { clearInput() }
    }
    DisposableEffect(state) {
        onDispose {
            ++state.revision
            state.job?.cancel()
        }
    }

    val changing = !instantChange && (state.running || top != state.settled)
    val destinations = when {
        instantChange -> listOf(top)
        state.running -> listOfNotNull(state.outgoing, state.target).distinct()
        else -> listOf(state.settled)
    }
    val incoming = if (state.running) state.target else top
    Box(modifier.fillMaxSize().onPreviewKeyEvent { changing }) {
        for (destination in destinations) key(destination) {
            val outgoing = changing && destination != incoming
            DestinationLifecycle(active = !outgoing) {
                Box(
                    Modifier.fillMaxSize()
                        .focusProperties { canFocus = !changing || state.gesture }
                        .then(if (outgoing) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    content(destination)
                }
            }
        }
        if (changing) {
            // Sibling hit testing stops here even where both destinations have inert gaps.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            })
        }
    }
}

/** Caps the outgoing lifetime at CREATED, removing its lifecycle-aware Back callbacks. */
@Composable
private fun DestinationLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) {
        object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
    }
    fun update() {
        owner.registry.currentState = if (active) parent.lifecycle.currentState
            else minOf(parent.lifecycle.currentState, Lifecycle.State.CREATED)
    }
    // Update before descendant SideEffects register/enable their handlers.
    update()
    DisposableEffect(parent, active) {
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

package dev.kudde.android.ui.agent

import android.provider.Settings
import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.kudde.android.ui.theme.KuddeTheme
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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

internal class BackInstance(val predictive: Boolean, val rightEdge: Boolean = false) {
    val channel = kotlinx.coroutines.channels.Channel<BackEventCompat>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    var job: Job? = null
    var pressed = false
    var navigated = false
}

/** Destination lifetime is separate from the seekable animation's scalar progress. */
class ScreenStackHostState<D : Any> internal constructor(
    initial: D,
    internal var animationClock: MonotonicFrameClock?,
) {
    constructor(initial: D) : this(initial, null)

    internal val seekable = SeekableTransitionState(0f)
    internal var animatedProgress: State<Float> = mutableStateOf(0f)
    val progress: Float get() {
        val release = releaseAnimation
        val start = releaseStart
        return if (release != null && start != null) start + (1 - start) * release.value
            else animatedProgress.value
    }
    var running by mutableStateOf(false)
        internal set
    internal var settled by mutableStateOf(initial)
    internal var outgoing by mutableStateOf(initial)
    internal var target by mutableStateOf<D?>(initial)
    internal var motion by mutableStateOf(AgentMotion.toTerminal)
    internal var job: Job? = null
    internal var gesture by mutableStateOf(false)
    internal var predictive by mutableStateOf(false)
    internal var rightEdge by mutableStateOf(false)
    internal var fingerDy by mutableStateOf(0f)
    internal var releaseStart by mutableStateOf<Float?>(null)
    internal var releaseAnimation by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)
    internal var releaseVelocityDp = 0f
    internal var releaseDistanceDp = 0f
    internal var widthDp = 0f
    internal var animationScope: CoroutineScope? = null
    internal var durationScale = 1f
    internal var requestBack: (() -> Unit)? = null
    internal var disposed = false
    internal var overviewTile by mutableStateOf<Rect?>(null)

    // Identifies the latest operation, so cancellation of an old gesture cannot undo a new one.
    internal var revision = 0

    internal suspend fun begin(to: D?, spec: AgentMotionSpec, from: D? = null) {
        val retainedProgress = if (running) progress else 0f
        releaseAnimation = null
        releaseStart = null
        if (!running) seekable.snapTo(0f)
        outgoing = from ?: if (running) target ?: outgoing else settled
        target = to
        motion = spec
        running = true
        seekable.seekTo(retainedProgress.coerceIn(0f, 1f), 1f)
    }

    internal suspend fun finish() {
        if (durationScale == 0f || motion.progressSpec is SnapSpec<*>) seekable.snapTo(1f)
        else if (releaseStart != null) {
            // The remaining travel is a distinct, normalized spring channel. Its initial
            // velocity converts dp/s to fractions/s, preserving the finger's velocity.
            val animation = Animatable(0f)
            releaseAnimation = animation
            animation.animateTo(
                1f, motion.progressSpec,
                initialVelocity = if (releaseDistanceDp > 0f) releaseVelocityDp / releaseDistanceDp else 0f,
            )
            seekable.snapTo(1f)
        } else seekable.animateTo(1f, motion.progressSpec)
        target?.let { settled = it }
        outgoing = settled
        running = false
    }

    internal suspend fun cancel() {
        if (durationScale != 0f && motion.progressSpec !is SnapSpec<*>) seekable.animateTo(0f, motion.progressSpec)
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
 * A null [backTarget] represents leaving the agent; [leaveContent] draws its inert home.
 */
@Composable
fun <D : Any> ScreenStackHost(
    top: D,
    modifier: Modifier = Modifier,
    state: ScreenStackHostState<D> = remember { ScreenStackHostState(top) },
    overviewKey: dev.kudde.android.ui.agents.AgentKey? = null,
    backTarget: () -> D? = { null },
    // A rapid second Back must see the navigation mutation before recomposition.
    backSource: () -> D = { top },
    leaveContent: @Composable () -> Unit = {},
    onBack: () -> Unit = {},
    transitionFor: (D, D?, AgentChangeReason) -> AgentMotionSpec = { from, to, reason ->
        AgentMotion.transitionFor(from, to, reason)
    },
    content: @Composable (D) -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val density = LocalDensity.current
    val scrim = AgentMotion.scrim(KuddeTheme.colors.isDark)
    fun scale() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestBackTarget by rememberUpdatedState(backTarget)
    val latestBackSource by rememberUpdatedState(backSource)
    val latestBack by rememberUpdatedState(onBack)
    val latestMotion by rememberUpdatedState(transitionFor)
    val scope = rememberCoroutineScope()
    state.animationScope = scope
    val requested = remember(top) {
        val from = state.target ?: state.settled
        if (top == from) null else from to transitionFor(from, top, AgentMotion.reason(from, top)).withDurationScale(scale())
    }
    val instantChange = requested?.second?.progressSpec is SnapSpec<*> && top != state.settled
    val transition = rememberTransition(state.seekable, label = "agent screen")
    state.animatedProgress = transition.animateFloat(
        // The playhead (seekable fraction) already carries the signed-off spec; mapping it
        // through the spec again would bend a gesture's progress. Read it back linearly.
        transitionSpec = { tween(durationMillis = 1000, easing = LinearEasing) }, label = "screen progress",
    ) { it }

    fun clearInput() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    // Registered before content: the destination's own inner Back steps take priority.
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
    val backOwner = LocalLifecycleOwner.current
    suspend fun handleBack(instance: BackInstance, events: kotlinx.coroutines.flow.Flow<BackEventCompat>) {
        val revision = ++state.revision
        state.job?.cancel()
        state.job = currentCoroutineContext().job
        state.gesture = true
        state.durationScale = scale()
        val from = latestBackSource()
        val to = latestBackTarget()
        // Known at the start, so the frames before the first progress event already
        // keep the base still (a later flip would jump it from -0.12 W to 0).
        state.rightEdge = instance.rightEdge
        state.predictive = instance.predictive && (to == null || from == AgentScreen.Changes)
        var lastProgress = 0f
        var lastX = 0f
        var firstY: Float? = null
        var lastTime = 0L
        var velocity = 0f
        var hadEvents = false
        val reason = if (to == null) AgentChangeReason.Leave else AgentChangeReason.Pop
        try {
            withContext(state.animationClock ?: EmptyCoroutineContext) {
                // A cancelled gesture restores the navigation target, even if its
                // preceding enter animation had not finished yet.
                state.settled = from
                state.begin(to, latestMotion(from, to, reason).withDurationScale(state.durationScale), from)
                events.collect { event ->
                    val now = SystemClock.uptimeMillis()
                    state.rightEdge = event.swipeEdge == BackEventCompat.EDGE_RIGHT
                    if (firstY == null) firstY = event.touchY
                    state.fingerDy = (event.touchY - firstY) / density.density
                    if (hadEvents && now > lastTime) {
                        velocity = (event.touchX - lastX) / density.density * 1000f / (now - lastTime) *
                            if (state.rightEdge) -1 else 1
                    }
                    hadEvents = true
                    state.predictive = to == null || from == AgentScreen.Changes
                    lastProgress = event.progress.coerceIn(0f, 1f)
                    lastX = event.touchX
                    lastTime = now
                    if (state.durationScale != 0f) state.seekable.seekTo(lastProgress, 1f)
                }
                // The system decides: a commit finishes from where the finger is, a cancel
                // (handleOnBackCancelled) cancels this job and rolls back below.
                if (!hadEvents) state.predictive = false
                if (state.predictive && hadEvents) {
                    state.releaseStart = lastProgress
                    state.releaseVelocityDp = velocity
                    state.releaseDistanceDp = state.widthDp - AgentMotion.backShift * lastProgress
                }
                // Cancel never changes navigation or clears focus; commit does both.
                clearInput()
                state.finish()
                if (state.revision == revision) {
                    state.gesture = false
                    if (!instance.navigated) {
                        instance.navigated = true
                        latestBack()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // A press that was superseded by the next one already navigated, so no rollback.
            if (instance.pressed && !instance.navigated && !state.disposed) {
                // A committed Back cancelled before it applied (another change got in first)
                // is still the user's press: it acts on the live stack, once.
                instance.navigated = true
                latestBack()
            } else if (state.revision == revision && !instance.navigated) {
                // Rollback outlives this cancelled handler, but belongs to the host
                // scope so a new change can interrupt it at its current progress.
                state.job = scope.launch(state.animationClock ?: EmptyCoroutineContext) {
                    state.cancel()
                    if (state.revision == revision) state.gesture = false
                }
            }
            throw cancelled
        }
    }
    val handleBackLatest by rememberUpdatedState<suspend (BackInstance, kotlinx.coroutines.flow.Flow<BackEventCompat>) -> Unit>(::handleBack)
    // Same contract as PredictiveBackHandler, but a press is known synchronously: Back
    // pressed again while an earlier committed Back is still finishing completes that one
    // first, so the second press sees the updated stack instead of being swallowed.
    val backCallback = remember {
        object : OnBackPressedCallback(true) {
            var instance: BackInstance? = null
            fun launch(predictive: Boolean, rightEdge: Boolean = false): BackInstance {
                val next = BackInstance(predictive, rightEdge)
                instance = next
                next.job = scope.launch {
                    try { handleBackLatest(next, next.channel.receiveAsFlow()) } finally { if (instance === next) instance = null }
                }
                return next
            }
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                instance?.job?.cancel()
                launch(true, backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT)
            }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) { instance?.channel?.trySend(backEvent) }
            override fun handleOnBackPressed() {
                val previous = instance
                val current = if (previous == null || !previous.predictive || previous.pressed) {
                    if (previous != null && previous.pressed && !previous.navigated) {
                        previous.navigated = true
                        latestBack()
                    }
                    previous?.job?.cancel()
                    launch(false)
                } else previous
                current.pressed = true
                current.channel.close()
            }
            override fun handleOnBackCancelled() { instance?.job?.cancel(); instance = null }
        }
    }
    state.requestBack = backCallback::handleOnBackPressed
    DisposableEffect(backDispatcher, backOwner) {
        backDispatcher.addCallback(backOwner, backCallback)
        onDispose { backCallback.remove() }
    }

    // Ask for a layout when the top changes. On a phone the next frame lays out a newly
    // composed destination anyway, so this costs one extra pass per change; the route
    // tests pause the frame clock, and without it a destination composed after the last
    // traversal is never measured (no terminal grid, no Live chip). Keep it unless those
    // tests learn to draw.
    val hostView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(top) { hostView.requestLayout() }
    LaunchedEffect(top) {
        val gestureFromTop = state.running && state.gesture && top == state.outgoing
        if ((!state.running && top == state.settled) ||
            (state.running && top == state.target) || gestureFromTop) return@LaunchedEffect
        ++state.revision
        state.gesture = false
        state.predictive = false
        state.durationScale = scale()
        state.job?.cancel()
        clearInput()
        val from = requested?.first ?: state.target ?: state.settled
        val spec = (requested?.second ?: latestMotion(from, top, AgentMotion.reason(from, top))).withDurationScale(state.durationScale)
        if (spec.progressSpec is SnapSpec<*>) {
            // Reduced motion is a cut in this frame; it must not await animation frames.
            state.releaseAnimation = null
            state.releaseStart = null
            state.settled = top
            state.outgoing = top
            state.target = top
            state.motion = spec
            state.running = false
        } else {
            state.job = scope.launch(state.animationClock ?: EmptyCoroutineContext) {
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
            state.requestBack = null
            state.disposed = true
            ++state.revision
            state.job?.cancel()
        }
    }

    val changing = !instantChange && (state.running || top != state.settled)
    val destinations = when {
        instantChange -> listOf(top)
        state.running && state.gesture && state.durationScale == 0f -> listOf(state.outgoing)
        state.running -> listOf(state.outgoing, state.target).distinct()
        else -> listOf(state.settled)
    }
    val incoming = if (state.running) state.target else top
    Box(modifier.fillMaxSize()
        .onSizeChanged { state.widthDp = it.width / density.density }
        .semantics { this[ScreenStackHostStateKey] = state }
        .onPreviewKeyEvent { changing }) {
        // Popped covers draw above the returning base; lifetime order does not determine z order.
        val drawing = if (state.running && state.motion.name in listOf("popChanges", "pushOverview", "leave")) destinations.reversed() else destinations
        for (destination in drawing) key(destination) {
            val ownsInput = destination == top
            val outgoing = !ownsInput
            // Capture one coherent composition snapshot. Reading gesture live inside
            // focusProperties races the captured `changing` flag at rollback completion.
            val acceptsFocus = ownsInput && (!changing || state.gesture)
            DestinationLifecycle(active = ownsInput) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            if (changing && state.durationScale != 0f) {
                                val p = state.progress
                                var frame = state.motion.frame(p, destination == incoming, size.width, size.height, state.overviewTile)
                                if (state.gesture && state.predictive) {
                                    if (destination == state.outgoing) {
                                        val release = state.releaseStart
                                        val t = if (release == null) 0f else ((p - release) / (1 - release).coerceAtLeast(Float.MIN_VALUE)).coerceIn(0f, 1f)
                                        val card = AgentMotion.predictiveFrame(release ?: p, state.rightEdge, state.fingerDy)
                                        val direction = if (state.rightEdge) -1 else 1
                                        frame = card.copy(x = card.x * density.density * (1 - t) + direction * size.width * t,
                                            y = card.y * density.density)
                                    } else {
                                        val release = state.releaseStart
                                        val follow = release ?: p
                                        val remaining = if (release == null) 1f else
                                            (1 - (p - release) / (1 - release).coerceAtLeast(Float.MIN_VALUE)).coerceIn(0f, 1f)
                                        frame = AgentFrame(
                                            x = AgentMotion.predictiveBaseX(follow, state.rightEdge, size.width) * remaining,
                                            dim = (1 - AgentMotion.backDimRelease * follow) * remaining,
                                        )
                                    }
                                }
                                alpha = frame.alpha
                                scaleX = frame.scale; scaleY = frame.scale
                                translationX = frame.x; translationY = frame.y
                                transformOrigin = TransformOrigin(0f, 0f)
                                // Compensate the radius for a scaled layer: the visual radius is in dp.
                                shape = RoundedCornerShape((frame.radiusDp / frame.scale).dp)
                                clip = frame.radiusDp > 0f
                                if (state.gesture && state.predictive) shadowElevation = frame.radiusDp * density.density
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (changing && state.durationScale != 0f) {
                                val f = state.motion.frame(state.progress, destination == incoming, size.width, size.height, state.overviewTile)
                                val release = state.releaseStart
                                val remaining = if (release == null) 1f else
                                    (1 - (state.progress - release) / (1 - release).coerceAtLeast(Float.MIN_VALUE)).coerceIn(0f, 1f)
                                val dim = if (state.gesture && state.predictive && destination != state.outgoing)
                                    (1 - AgentMotion.backDimRelease * (release ?: state.progress)) * remaining else f.dim
                                if (dim > 0f) drawRect(scrim.copy(alpha = scrim.alpha * dim))
                            }
                        }
                        .focusProperties { canFocus = acceptsFocus }
                        .then(if (outgoing) Modifier.clearAndSetSemantics {
                            if (destination == null) this[ScreenStackLeaveBaseKey] = true
                        } else Modifier),
                ) {
                    CompositionLocalProvider(LocalAgentMotion provides AgentMotionContext(state, destination == incoming, overviewKey)) {
                        if (destination == null) leaveContent() else content(destination)
                    }
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

/** Shared geometry and progress only; navigation lifetime remains owned by the keeper. */
internal class AgentMotionContext(val host: ScreenStackHostState<*>, val incoming: Boolean, val overviewKey: dev.kudde.android.ui.agents.AgentKey?)
internal val LocalAgentMotion = staticCompositionLocalOf<AgentMotionContext?> { null }

// Internal, unspoken metadata lets route tests drive the real host, without a global
// registry or substituting fake destinations. It adds no accessibility description.
internal val ScreenStackHostStateKey = SemanticsPropertyKey<ScreenStackHostState<*>>("ScreenStackHostState")

internal val ScreenStackLeaveBaseKey = SemanticsPropertyKey<Boolean>("ScreenStackLeaveBase")

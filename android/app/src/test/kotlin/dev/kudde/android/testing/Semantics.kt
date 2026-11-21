package dev.kudde.android.testing

import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

// The merged semantics tree, which is what TalkBack is given (as ReadOnceTest
// reads it), for every Compose window on screen: the activity's and each
// Popup's own window (a menu draws in a window of its own).

/** Every window's root view, the activity's first: WindowManagerGlobal keeps them. */
fun windowRoots(): List<View> {
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getMethod("getInstance").invoke(null)
    val field = global.getDeclaredField("mViews").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    // A removed window may linger in the list for a moment: only those still attached and shown count.
    return (field.get(instance) as List<View>).filter { it.isAttachedToWindow && it.isShown }
}

/** The Compose roots found under [view], depth first. */
fun composeRoots(view: View): List<ViewRootForTest> = buildList {
    fun walk(v: View) {
        if (v is ViewRootForTest) {
            add(v)
            return
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
}

/** Every merged semantics node of every window. */
fun semanticsNodes(): List<SemanticsNode> = windowRoots().flatMap(::composeRoots).flatMap { root ->
    val all = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        all += node
        node.children.forEach(::walk)
    }
    walk(root.semanticsOwner.rootSemanticsNode)
    all
}

fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
fun SemanticsNode.descriptions(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
fun SemanticsNode.stateText(): String? = config.getOrNull(SemanticsProperties.StateDescription)
fun SemanticsNode.role() = config.getOrNull(SemanticsProperties.Role)
fun SemanticsNode.clickable(): Boolean = config.getOrNull(SemanticsActions.OnClick) != null

/** Clicks [this] as TalkBack's double tap does; false when it has no click. */
fun SemanticsNode.click(): Boolean = config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false

/** The node spoken as [description], or showing [text], in any window. */
fun nodeSaying(text: String): SemanticsNode? =
    semanticsNodes().firstOrNull { text in it.descriptions() || it.texts() == listOf(text) }

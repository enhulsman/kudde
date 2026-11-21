package dev.kudde.android.testing

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowChoreographer

/**
 * A screen capture of [content] with a Compose `Popup` window open in it (screen-stack D0).
 *
 * `captureRoboImage { }` idles the looper without a bound, which a Popup under a
 * free-running Choreographer never lets end. Here the activity launches first
 * (the launch needs the Choreographer running), then [PopupSafeClock] pauses it, a
 * few bounded frames compose the window, and the whole screen is captured.
 */
fun captureWithMenuWindow(path: String, content: @Composable () -> Unit) {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    try {
        controller.get().setContent(content = content)
        PopupSafeClock.paused()
        BoundedFrames.step(4)
        captureScreenRoboImage(path)
    } finally {
        controller.pause().stop().destroy()
        BoundedFrames.settle(minFrames = 3)
        // The next launch (the other theme) needs a running Choreographer.
        ShadowChoreographer.setPaused(false)
    }
}

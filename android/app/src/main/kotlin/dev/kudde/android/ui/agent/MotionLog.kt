package dev.kudde.android.ui.agent

import android.util.Log
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.CoroutineContext

/**
 * One logcat line per screen change: the system's animator scale as the host reads
 * it, and the scale Compose's own animations run under. Transitions twice stopped
 * animating until a restart (reports 034, 037) and it could not be reproduced; if
 * the two numbers differ the next time, Compose's scale is the cause.
 * Read with `adb logcat -s KuddeMotion`.
 */
internal object MotionLog {
    fun change(kind: String, setting: Float, context: CoroutineContext, extra: String = "") {
        val compose = context[MotionDurationScale]?.scaleFactor
        runCatching { Log.i("KuddeMotion", "$kind setting=$setting compose=$compose $extra".trimEnd()) }
    }
}

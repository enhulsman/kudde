package dev.kudde.android.ui.hint

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kudde.android.R
import dev.kudde.android.ui.agent.AgentMotion
import dev.kudde.android.ui.components.rememberScreenReaderOn
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

/**
 * Whether the title bar's hint was closed (spec "The title bar explains itself once", design D11):
 * one boolean in its own app-private preferences file, excluded from backup like the others.
 * Absent, or of another type, reads as not seen. One instance lives on KuddeApp. Main thread only.
 */
class TitleBarHintSetting(private val prefs: SharedPreferences) {
    var seen: Boolean by mutableStateOf(runCatching { prefs.getBoolean(KEY, false) }.getOrDefault(false))
        private set

    /** The person tapped the hint: it is not shown again, in a terminal or a chat. */
    fun close() {
        if (seen) return
        seen = true
        prefs.edit().putBoolean(KEY, true).apply()
    }

    companion object {
        const val FILE = "title_bar_hint"
        const val KEY = "seen"

        fun open(context: Context): TitleBarHintSetting =
            TitleBarHintSetting(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/**
 * True when the hint sits at the top of the screen's area; false when the cursor row falls under it, and it
 * sits just above the keys instead (design D11 "Where"). Positions are in pixels from the area's top;
 * [hintBottom] is where the top placement ends. A terminal with no cursor shown leaves it at the top.
 */
fun hintAtTop(cursorTop: Float?, cursorBottom: Float?, hintBottom: Float): Boolean {
    if (cursorTop == null || cursorBottom == null) return true
    return !(cursorTop < hintBottom && cursorBottom > 0f)
}

/**
 * The hint: G1 to G4 and the "Got it" button, or G6 in their place while a screen reader is on. One element,
 * read once when it appears (polite), focusable, its click named "Got it". It fades with the app's short
 * duration, and not at all with the system's animations off.
 */
@Composable
fun TitleBarHintCard(visible: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animations = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (animations) fadeIn(tween(AgentMotion.titleFadeMs)) else EnterTransition.None,
        exit = if (animations) fadeOut(tween(AgentMotion.titleFadeMs)) else ExitTransition.None,
    ) {
        HintBody(onClose)
    }
}

@Composable
private fun HintBody(onClose: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val touchExploration = rememberScreenReaderOn()
    val gotIt = stringResource(R.string.hint_got_it)
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.popover)
            .border(1.dp, c.border, shape)
            .clickable(onClickLabel = gotIt, onClick = onClose)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        BasicText(stringResource(R.string.hint_title), style = KuddeType.rowName.copy(color = c.fg))
        if (touchExploration) {
            BasicText(stringResource(R.string.hint_talkback), style = KuddeType.secondary.copy(color = c.muted))
        } else {
            BasicText(stringResource(R.string.hint_sideways), style = KuddeType.secondary.copy(color = c.muted))
            BasicText(stringResource(R.string.hint_vertical), style = KuddeType.secondary.copy(color = c.muted))
            BasicText(stringResource(R.string.hint_map), style = KuddeType.secondary.copy(color = c.muted))
        }
        BasicText(
            gotIt,
            style = KuddeType.pill.copy(color = c.fg),
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(KuddeRadius.button))
                .border(1.dp, c.input, RoundedCornerShape(KuddeRadius.button))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

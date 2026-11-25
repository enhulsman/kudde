package dev.kudde.android.ui.hint

import android.content.Context
import android.content.SharedPreferences
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

// spec "The title bar explains itself once", scenario "Stored flag": one boolean, in its own file.
@RunWith(KuddeTestRunner::class)
class TitleBarHintSettingTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun prefs(): SharedPreferences = app.getSharedPreferences(TitleBarHintSetting.FILE, Context.MODE_PRIVATE)

    @Test fun aFreshInstallHasNotSeenIt() {
        assertFalse(TitleBarHintSetting(prefs()).seen)
    }

    @Test fun anAbsentOrOtherTypedValueReadsAsNotSeen() {
        prefs().edit().putString(TitleBarHintSetting.KEY, "true").commit()
        assertFalse(TitleBarHintSetting(prefs()).seen)
    }

    @Test fun closingWritesOneBooleanAndNothingElse() {
        TitleBarHintSetting.open(app).close()
        assertEquals(mapOf(TitleBarHintSetting.KEY to true), prefs().all)
        assertEquals("title_bar_hint", TitleBarHintSetting.FILE)
    }

    @Test fun closedStaysClosedAcrossARestart() {
        TitleBarHintSetting.open(app).close()
        assertTrue(TitleBarHintSetting.open(app).seen)
    }

    @Test fun closingTwiceWritesOnce() {
        val setting = TitleBarHintSetting.open(app)
        setting.close()
        prefs().edit().clear().commit()
        setting.close()
        assertTrue("already closed in this process, nothing more is written", prefs().all.isEmpty())
    }
}

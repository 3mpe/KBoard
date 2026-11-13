// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.prefs
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Düz Application: bkz. KBoardSuggestionsTest (#30).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KBoardVoiceTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ctx.prefs().edit { remove(KBoardVoice.PREF_NOTICE_SHOWN) }
        ShadowToast.reset()
    }

    @Test
    fun firstTapOnlyShowsNoticeSecondTapSwitches() {
        // ilk dokunuş: bildirim, geçiş yok; bayrak bildirimden sonra yazılır
        assertFalse(KBoardVoice.onVoiceKey(ctx))
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(ctx.getString(R.string.kboard_voice_notice), ShadowToast.getTextOfLatestToast())
        assertTrue(ctx.prefs().getBoolean(KBoardVoice.PREF_NOTICE_SHOWN, false))
        // sonraki dokunuşlar: doğrudan geçiş, yeni bildirim yok
        assertTrue(KBoardVoice.onVoiceKey(ctx))
        assertTrue(KBoardVoice.onVoiceKey(ctx))
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishNoticeAndLabel() {
        assertEquals("Sesle yazma başka bir uygulamaya geçer (ör. Google). Geçmek için tekrar dokun.",
            ctx.getString(R.string.kboard_voice_notice))
        assertEquals("Sesle yaz (başka uygulama)", ctx.getString(R.string.spoken_description_mic))
        assertEquals("Sesle yaz (başka uygulama)", ctx.getString(R.string.voice))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishNoticeAndLabel() {
        assertEquals("Voice typing switches to another app (e.g. Google). Tap again to switch.",
            ctx.getString(R.string.kboard_voice_notice))
        assertEquals("Voice typing (other app)", ctx.getString(R.string.spoken_description_mic))
        assertEquals("Voice typing (other app)", ctx.getString(R.string.voice))
    }

    @Test
    fun micIsTheOnlyKeyPinnedByDefault() {
        val pinned = defaultPinnedToolbarPref.split(Separators.ENTRY).filter { it.endsWith(Separators.KV + "true") }
        assertEquals(listOf(ToolbarKey.VOICE.name + Separators.KV + "true"), pinned)
    }

    @Test
    fun micHiddenInPasswordField() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or variation; packageName = "test" }
            assertFalse(InputAttributes(info, false, ctx.packageName).mShouldShowVoiceInputKey, "variation $variation")
        }
    }
}

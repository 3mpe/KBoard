// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs

/**
 * Mikrofon tuşu: KBoard ses tanıma yapmaz, telefonda kurulu sesle yazma uygulamasına (ör. Google) geçer
 * (HeliBoard'un `switchToShortcutIme` yolu). İlk dokunuş yalnız bunu söyleyen kısa bir bildirim gösterir
 * ve geçmez; bildirim gösterildikten sonra her dokunuş doğrudan geçer.
 */
object KBoardVoice {
    const val PREF_NOTICE_SHOWN = "kboard_voice_notice_shown"

    /** true: sesle yazma uygulamasına geçilebilir. false: ilk dokunuş, yalnız bildirim gösterildi. */
    @JvmStatic
    fun onVoiceKey(context: Context): Boolean {
        val prefs = context.prefs()
        if (prefs.getBoolean(PREF_NOTICE_SHOWN, false)) return true
        Toast.makeText(context, R.string.kboard_voice_notice, Toast.LENGTH_LONG).show()
        prefs.edit { putBoolean(PREF_NOTICE_SHOWN, true) } // bildirim gösterildikten sonra
        return false
    }
}

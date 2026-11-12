// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.util.Locale

/** KBoard motorundan gelen önerilerin kaynak sözlüğü (`SuggestedWordInfo.sourceDict`). */
class KBoardDictionary(locale: Locale) : Dictionary(KBoardSuggestions.TYPE_KBOARD, locale) {
    override fun getSuggestions(
        composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray
    ): ArrayList<SuggestedWordInfo> = ArrayList(KBoardSuggestions.getSuggestions(composedData, ngramContext, mLocale))

    override fun isInDictionary(word: String): Boolean = false
}

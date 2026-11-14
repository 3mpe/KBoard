// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import helium314.keyboard.latin.utils.prefs

/**
 * Yalnız debug derlemesinde: mesajlaşma uygulamasındaki gibi düz bir metin alanı (otomatik düzeltme
 * açık, cümle başı büyük harf). CI'daki emülatör ekran görüntüleri bunu kullanır; kişi formundaki
 * "Ad" alanı ad türünde olduğu için klavyeler orada otomatik düzeltme yapmaz.
 *
 * Ölçüm tabanı anahtarı da buradadır (docs/reports/baseline-v0.md): tercih dosyasındaki
 * [PREF_ENGINE_OFF] true ise KBoard motoru bu süreçte kapatılır, yalnız HeliBoard'un kendi önerileri
 * ve otomatik düzeltmesi kalır. Anahtar ve onu okuyan kod yalnız debug kaynak kümesindedir; release
 * APK'da ikisi de yoktur. CI `set_prefs` ile yazar (.github/ci/taban.sh); ayarlar ekranında görünmez.
 */
class TestFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Klavye bu etkinlikle aynı süreçte çalışır; süreç yeniden başlarsa etkinlik de yeniden kurulur.
        KBoardSuggestions.setEngineOffForDebug(prefs().getBoolean(PREF_ENGINE_OFF, false))
        Log.i(TAG, "motor anahtarı: " + if (KBoardSuggestions.engineOff) "kapalı" else "açık")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            // `-e alan sifre`: parola alanı (mikrofon gizli olmalı)
            inputType = if (intent.getStringExtra("alan") == "sifre")
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "Mesaj yaz"
            textSize = 20f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        field.requestFocus()
    }

    companion object {
        private const val TAG = "KBoardTestField"
        /** Yalnız debug: true ise KBoard motoru kapalı (ölçüm tabanı). */
        const val PREF_ENGINE_OFF = "kboard_debug_engine_off"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Yalnız debug derlemesinde: mesajlaşma uygulamasındaki gibi düz bir metin alanı (otomatik düzeltme
 * açık, cümle başı büyük harf). CI'daki emülatör ekran görüntüleri bunu kullanır; kişi formundaki
 * "Ad" alanı ad türünde olduğu için klavyeler orada otomatik düzeltme yapmaz.
 */
class TestFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
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
}

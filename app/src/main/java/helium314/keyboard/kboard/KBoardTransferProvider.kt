// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import androidx.core.content.FileProvider

/** Dışa aktarılan dosyayı paylaşmak için (yalnız cache/aktarim/; bkz. res/xml/kboard_transfer_path.xml). */
class KBoardTransferProvider : FileProvider()

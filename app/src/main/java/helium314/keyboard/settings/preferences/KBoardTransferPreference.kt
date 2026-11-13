// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.kboard.KBoardTransfer
import helium314.keyboard.kboard.KBoardTransfer.Section
import helium314.keyboard.latin.R
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * KBOARD: "Dışa / içe aktar". Ayarları, kişisel sözlüğü ve (istenirse) öğrenilen kelimeleri tek
 * dosyada yeni telefona taşır; bkz. [KBoardTransfer]. Öğrenilen kelimeler dışa aktarmada
 * varsayılan olarak seçili değildir.
 */
@Composable
fun KBoardTransferPreference(icon: Int? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<KBoardTransfer.Payload?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val exportSelection = remember { mutableStateListOf(Section.SETTINGS, Section.DICTIONARY) }
    val importSelection = remember { mutableStateListOf<Section>() }

    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Paylaşım ekranından dönüldü: geçici dosya silinir (kalırsa açılışta silinir).
        KBoardTransfer.clearShareDir(ctx)
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val sections = exportSelection.toSet()
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { exportTo(ctx, uri, sections) }
            busy = false
            if (ok) Toast.makeText(ctx, ctx.getString(R.string.kboard_transfer_exported), Toast.LENGTH_LONG).show()
            else error = ctx.getString(R.string.kboard_transfer_export_error)
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val read = withContext(Dispatchers.IO) { readFrom(ctx, uri) }
            busy = false
            read.onSuccess { payload ->
                importSelection.clear()
                importSelection.addAll(Section.entries.filter { it in payload.sections })
                pendingImport = payload
            }.onFailure { e ->
                error = ctx.getString(errorText((e as? KBoardTransfer.TransferException)?.reason))
            }
        }
    }

    Preference(
        name = stringResource(R.string.kboard_transfer),
        description = stringResource(R.string.kboard_transfer_summary),
        icon = icon,
        onClick = { if (!busy) showMenu = true },
    )

    if (showMenu) {
        ConfirmationDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(stringResource(R.string.kboard_transfer)) },
            content = { Text(stringResource(R.string.kboard_transfer_message)) },
            confirmButtonText = stringResource(R.string.kboard_transfer_export),
            onConfirmed = { showExport = true },
            neutralButtonText = stringResource(R.string.kboard_transfer_import),
            onNeutral = {
                showMenu = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                openLauncher.launch(intent)
            },
        )
    }

    if (showExport) {
        ConfirmationDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.kboard_transfer_export_title)) },
            content = {
                Column {
                    SectionCheckboxes(exportSelection)
                    if (Section.LEARNED in exportSelection) {
                        Text(
                            stringResource(R.string.kboard_transfer_learned_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButtonText = stringResource(R.string.kboard_transfer_share),
            onConfirmed = {
                if (exportSelection.isEmpty()) return@ConfirmationDialog
                val sections = exportSelection.toSet()
                busy = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) { exportToShareFile(ctx, sections) }
                    busy = false
                    if (file == null) {
                        error = ctx.getString(R.string.kboard_transfer_export_error)
                        return@launch
                    }
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".kboard_transfer", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.kboard_transfer_mail_subject))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(file.name, uri)
                    val chooser = Intent.createChooser(send, ctx.getString(R.string.kboard_transfer_share))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    shareLauncher.launch(chooser)
                }
            },
            neutralButtonText = stringResource(R.string.kboard_transfer_save),
            onNeutral = {
                showExport = false
                if (exportSelection.isEmpty()) return@ConfirmationDialog
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, fileName())
                saveLauncher.launch(intent)
            },
        )
    }

    pendingImport?.let { payload ->
        ConfirmationDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.kboard_transfer_import_title)) },
            content = {
                Column {
                    SectionCheckboxes(importSelection, available = payload.sections)
                    Text(
                        stringResource(R.string.kboard_transfer_import_warning),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButtonText = stringResource(R.string.kboard_transfer_import),
            onConfirmed = {
                val sections = importSelection.toSet()
                pendingImport = null
                if (sections.isEmpty()) return@ConfirmationDialog
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { importNow(ctx, payload, sections) }
                    busy = false
                    if (ok) {
                        if (Section.SETTINGS in sections) reloadAfterSettings(ctx)
                        Toast.makeText(ctx, ctx.getString(R.string.kboard_transfer_imported), Toast.LENGTH_LONG).show()
                    } else error = ctx.getString(R.string.kboard_transfer_import_error)
                }
            },
        )
    }

    error?.let { InfoDialog(it) { error = null } }
}

@Composable
private fun SectionCheckboxes(selection: MutableList<Section>, available: Set<Section> = Section.entries.toSet()) {
    for (section in Section.entries) {
        if (section !in available) continue
        val checked = section in selection
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (checked) selection.remove(section) else selection.add(section) }
        ) {
            Checkbox(checked = checked, onCheckedChange = { if (it) selection.add(section) else selection.remove(section) })
            Text(stringResource(sectionTitle(section)))
        }
    }
}

private fun sectionTitle(section: Section) = when (section) {
    Section.SETTINGS -> R.string.kboard_transfer_section_settings
    Section.DICTIONARY -> R.string.kboard_transfer_section_dictionary
    Section.LEARNED -> R.string.kboard_transfer_section_learned
}

private fun errorText(reason: KBoardTransfer.Reason?) = when (reason) {
    KBoardTransfer.Reason.NOT_KBOARD -> R.string.kboard_transfer_error_not_kboard
    KBoardTransfer.Reason.NEWER_FORMAT -> R.string.kboard_transfer_error_newer
    KBoardTransfer.Reason.TOO_LARGE -> R.string.kboard_transfer_error_too_large
    KBoardTransfer.Reason.CORRUPT, null -> R.string.kboard_transfer_error_corrupt
}

private fun fileName(): String =
    "KBoard_aktarim_" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().time) + ".zip"

private const val TAG = "KBoardTransfer"

private fun exportTo(ctx: Context, uri: Uri, sections: Set<Section>): Boolean = try {
    val payload = KBoardTransfer.collect(ctx, sections)
    ctx.contentResolver.openOutputStream(uri, "wt")?.use { KBoardTransfer.write(it, payload, sections) } != null
} catch (e: Exception) {
    Log.w(TAG, "dışa aktarılamadı: ${e.javaClass.simpleName}") // içerik günlüğe yazılmaz
    false
}

private fun exportToShareFile(ctx: Context, sections: Set<Section>): File? = try {
    KBoardTransfer.clearShareDir(ctx)
    val dir = KBoardTransfer.shareDir(ctx).apply { mkdirs() }
    val file = File(dir, fileName())
    val payload = KBoardTransfer.collect(ctx, sections)
    file.outputStream().use { KBoardTransfer.write(it, payload, sections) }
    file
} catch (e: Exception) {
    Log.w(TAG, "paylaşım dosyası oluşturulamadı: ${e.javaClass.simpleName}")
    KBoardTransfer.clearShareDir(ctx)
    null
}

private fun readFrom(ctx: Context, uri: Uri): Result<KBoardTransfer.Payload> = try {
    val payload = ctx.contentResolver.openInputStream(uri)?.use { KBoardTransfer.read(it) }
        ?: throw KBoardTransfer.TransferException(KBoardTransfer.Reason.CORRUPT, "dosya açılamadı")
    Result.success(payload)
} catch (e: Exception) {
    Log.w(TAG, "içe aktarma dosyası reddedildi: ${(e as? KBoardTransfer.TransferException)?.reason ?: e.javaClass.simpleName}")
    Result.failure(e)
}

private fun importNow(ctx: Context, payload: KBoardTransfer.Payload, sections: Set<Section>): Boolean = try {
    if (Section.SETTINGS in sections) {
        LayoutUtilsCustom.onLayoutFileChanged()
        Settings.getInstance().stopListener()
    }
    try {
        KBoardTransfer.apply(ctx, payload, sections)
    } finally {
        if (Section.SETTINGS in sections) Settings.getInstance().startListener()
    }
    true
} catch (e: Exception) {
    Log.w(TAG, "içe aktarılamadı: ${e.javaClass.simpleName}")
    false
}

/** HeliBoard'un geri yüklemesinden sonra yaptığı yeniden yüklemeler (ana iş parçacığında). */
private fun reloadAfterSettings(ctx: Context) {
    checkVersionUpgrade(ctx)
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    ctx.getActivity()?.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

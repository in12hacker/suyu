// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.DocumentProvider
import org.yuzu.yuzu_emu.getPublicFilesDir
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ImportExportSavesFragment : DialogFragment() {
    private val context = YuzuApplication.appContext
    private val savesFolder =
        "${context.getPublicFilesDir().canonicalPath}/nand/user/save/0000000000000000"

    // Get first subfolder in saves folder (should be the user folder)
    private val savesFolderRoot = File(savesFolder).listFiles()?.firstOrNull()?.canonicalPath ?: ""
    private var lastZipCreated: File? = null

    private lateinit var startForResultExportSave: ActivityResultLauncher<Intent>
    private lateinit var documentPicker: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activityResultRegistry = requireActivity().activityResultRegistry
        startForResultExportSave = activityResultRegistry.register(
            "startForResultExportSaveKey",
            ActivityResultContracts.StartActivityForResult()
        ) {
            File(context.getPublicFilesDir().canonicalPath, "temp").deleteRecursively()
        }
        documentPicker = activityResultRegistry.register(
            "documentPickerKey",
            ActivityResultContracts.OpenDocument()
        ) {
            it?.let { uri -> importSave(uri) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return if (savesFolderRoot == "") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_export_saves)
                .setMessage("No profileID found. Launch a game first and then come back.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import/Export Saves")
                .setPositiveButton("Export") { _, _ ->
                    exportSave()
                }
                .setNeutralButton("Import") { _, _ ->
                    documentPicker.launch(arrayOf("application/zip"))
                }
                .show()
        }
    }

    /**
     * Zips the save files located in the given folder path and creates a new zip file with the current date and time.
     * @return true if the zip file is successfully created, false otherwise.
     */
    private fun zipSave(): Boolean {
        try {
            val tempFolder = File(requireContext().getPublicFilesDir().canonicalPath, "temp")
            tempFolder.mkdirs()
            val saveFolder = File(savesFolderRoot)
            val outputZipFile = File(
                tempFolder, "Yuzu saves - ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.zip"
            )
            outputZipFile.createNewFile()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
                saveFolder.walkTopDown().forEach { file ->
                    val zipFileName =
                        file.absolutePath.removePrefix(savesFolderRoot).removePrefix("/")
                    if (zipFileName == "")
                        return@forEach
                    val entry = ZipEntry("$zipFileName${(if (file.isDirectory) "/" else "")}")
                    zos.putNextEntry(entry)
                    if (file.isFile)
                        file.inputStream().use { fis -> fis.copyTo(zos) }
                }
            }
            lastZipCreated = outputZipFile
        } catch (e: Exception) {
            return false
        }
        return true
    }

    /**
     * Extracts the save files located in the given zip file and copies them to the saves folder.
     * @exception IOException if the file was being created outside of the target directory
     */
    private fun unzip(zipStream: InputStream, destDir: File): Boolean {
        val zis = ZipInputStream(BufferedInputStream(zipStream))
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            val entryName = entry.name
            val entryFile = File(destDir, entryName)
            if (!entryFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                zis.close()
                throw IOException("Entry is outside of the target dir: " + entryFile.name)
            }
            if (entry.isDirectory) {
                entryFile.mkdirs()
            } else {
                entryFile.parentFile?.mkdirs()
                entryFile.createNewFile()
                entryFile.outputStream().use { fos -> zis.copyTo(fos) }
            }
            entry = zis.nextEntry
        }
        zis.close()
        return true
    }

    /**
     * Exports the save file located in the given folder path by creating a zip file and sharing it via intent.
     */
    private fun exportSave() {
        CoroutineScope(Dispatchers.IO).launch {
            val wasZipCreated = zipSave()
            val lastZipFile = lastZipCreated
            if (!wasZipCreated || lastZipFile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to export save", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val file = DocumentFile.fromSingleUri(
                    context, DocumentsContract.buildDocumentUri(
                        DocumentProvider.AUTHORITY,
                        "${DocumentProvider.ROOT_ID}/temp/${lastZipFile.name}"
                    )
                )!!
                val intent = Intent(Intent.ACTION_SEND)
                    .setDataAndType(file.uri, "application/zip")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, file.uri)
                startForResultExportSave.launch(Intent.createChooser(intent, "Share save file"))
            }
        }
    }

    /**
     * Imports the save files contained in the zip file, and replaces any existing ones with the new save file.
     * @param zipUri The Uri of the zip file containing the save file(s) to import.
     */
    private fun importSave(zipUri: Uri) {
        val inputZip = context.contentResolver.openInputStream(zipUri)
        // A zip needs to have at least one subfolder named after a TitleId in order to be considered valid.
        var validZip = false
        val savesFolder = File(savesFolderRoot)
        val cacheSaveDir = File("${context.cacheDir.path}/saves/")
        cacheSaveDir.mkdir()

        if (inputZip == null) {
            Toast.makeText(context, context.getString(R.string.fatal_error), Toast.LENGTH_LONG)
                .show()
            return
        }

        val filterTitleId =
            FilenameFilter { _, dirName -> dirName.matches(Regex("^0100[\\dA-Fa-f]{12}$")) }

        try {
            CoroutineScope(Dispatchers.IO).launch {
                unzip(inputZip, cacheSaveDir)
                cacheSaveDir.list(filterTitleId)?.forEach { savePath ->
                    File(savesFolder, savePath).deleteRecursively()
                    File(cacheSaveDir, savePath).copyRecursively(File(savesFolder, savePath), true)
                    validZip = true
                }

                withContext(Dispatchers.Main) {
                    if (!validZip) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.save_file_invalid_zip_structure),
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.save_file_imported_success),
                        Toast.LENGTH_LONG
                    ).show()
                }

                cacheSaveDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.fatal_error), Toast.LENGTH_LONG)
                .show()
        }
    }

    companion object {
        const val TAG = "ImportExportSavesFragment"
    }
}

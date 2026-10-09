package com.ratatoskr.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.ratatoskr.shared.platform.PickedFile
import com.ratatoskr.shared.platform.PlatformFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CSV import/export through the Storage Access Framework, the system
 * file picker, so no storage permission is needed and the user chooses
 * exactly which file the app can touch. Must be constructed in the
 * activity's onCreate: activity-result launchers have to be registered
 * before the activity starts.
 */
class AndroidFiles(activity: ComponentActivity) : PlatformFiles {
    private val context: Context = activity.applicationContext
    private var pending: CompletableDeferred<Uri?>? = null

    private val openLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { finish(it) }
    private val createLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { finish(it) }

    private fun finish(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }

    private suspend fun awaitPicker(launch: () -> Unit): Uri? {
        pending?.complete(null)
        val result = CompletableDeferred<Uri?>()
        pending = result
        launch()
        return result.await()
    }

    override suspend fun pickCsvToImport(): PickedFile? {
        // Pickers label CSVs inconsistently, so accept the common types.
        val uri = awaitPicker {
            openLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        } ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = checkNotNull(context.contentResolver.openInputStream(uri)) { "Couldn't open the file" }
                .use { it.readBytes() }
            PickedFile(displayName(uri) ?: "import.csv", bytes)
        }
    }

    override suspend fun saveCsvExport(suggestedName: String, content: String): String? {
        val uri = awaitPicker { createLauncher.launch(suggestedName) } ?: return null
        withContext(Dispatchers.IO) {
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Couldn't write the file" }
                .use { it.write(content.encodeToByteArray()) }
        }
        return displayName(uri) ?: suggestedName
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

package com.ratatoskr.desktop

import com.ratatoskr.shared.platform.PickedFile
import com.ratatoskr.shared.platform.PlatformFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * PlatformFiles for desktop, using AWT's FileDialog -- on Linux that's
 * the native GTK file chooser, and the platform-native dialog on Windows
 * and macOS too, rather than Swing's JFileChooser look.
 */
class DesktopFiles(private val parent: Frame) : PlatformFiles {

    override suspend fun pickCsvToImport(): PickedFile? {
        val file = showDialog("Import from CSV", FileDialog.LOAD, null) ?: return null
        return withContext(Dispatchers.IO) { PickedFile(file.name, file.readBytes()) }
    }

    override suspend fun saveCsvExport(suggestedName: String, content: String): String? {
        val file = showDialog("Save unencrypted CSV", FileDialog.SAVE, suggestedName) ?: return null
        withContext(Dispatchers.IO) { writeOwnerOnly(file.toPath(), content) }
        return file.absolutePath
    }

    private suspend fun showDialog(title: String, mode: Int, suggestedName: String?): File? =
        withContext(Dispatchers.Main) {
            val dialog = FileDialog(parent, title, mode)
            if (suggestedName != null) dialog.file = suggestedName
            if (mode == FileDialog.LOAD) dialog.setFilenameFilter { _, name -> name.endsWith(".csv", ignoreCase = true) }
            dialog.isVisible = true // modal: returns once the user picks or cancels
            val name = dialog.file ?: return@withContext null
            File(dialog.directory, name)
        }

    /** The export holds every password in plaintext, so on filesystems
     * with POSIX permissions it's created readable by the owner only,
     * rather than with the usual world-readable default. */
    private fun writeOwnerOnly(path: Path, content: String) {
        val posix = Files.getFileStore(path.parent).supportsFileAttributeView("posix")
        if (posix) {
            val ownerOnly = PosixFilePermissions.fromString("rw-------")
            if (Files.exists(path)) {
                Files.setPosixFilePermissions(path, ownerOnly)
            } else {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerOnly))
            }
        }
        Files.writeString(path, content)
    }
}

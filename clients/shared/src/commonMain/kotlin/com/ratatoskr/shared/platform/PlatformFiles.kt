package com.ratatoskr.shared.platform

/** A file the user picked to open: its display name and raw contents. */
class PickedFile(val name: String, val bytes: ByteArray)

/**
 * File pickers for CSV import/export. Supplied by each platform's entry
 * point, the same way `appIcon` is -- commonMain has no file-dialog API of
 * its own, and each platform's picker is genuinely different (AWT/Swing on
 * desktop, the Storage Access Framework on Android, UIDocumentPicker on
 * iOS). When a platform doesn't supply one yet, the import/export buttons
 * are simply not shown.
 */
interface PlatformFiles {
    /** Shows an open dialog for a CSV file; null if the user cancelled. */
    suspend fun pickCsvToImport(): PickedFile?

    /** Shows a save dialog and writes `content` to the chosen file.
     * Returns where it was saved, or null if the user cancelled. */
    suspend fun saveCsvExport(suggestedName: String, content: String): String?
}

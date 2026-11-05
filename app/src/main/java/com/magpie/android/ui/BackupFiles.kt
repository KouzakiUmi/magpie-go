package com.magpie.android.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.magpie.android.R
import java.io.File

/** Resolve only Magpie's own timestamped backup names in its private home. */
internal fun resolveBackupExport(home: File, rawPath: String): File? {
    if (rawPath.isBlank() || rawPath.length > 512) return null
    val name = rawPath.substringAfterLast('/').substringAfterLast('\\')
    if (!name.startsWith("magpie-") || !name.endsWith(".magpie-backup")) return null
    return listOf(File(home, name), File(home, "Downloads/$name"))
        .firstOrNull { candidate -> candidate.isFile }
}

internal object BackupSharer {
    fun share(context: Context, source: File) {
        Thread {
            try {
                val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                val shared = File(dir, source.name)
                source.inputStream().use { input ->
                    shared.outputStream().use { output -> input.copyTo(output) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, source.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, context.getString(R.string.backup_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (_: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, R.string.backup_share_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

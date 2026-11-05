package com.magpie.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.util.UUID
import com.magpie.android.R
import java.io.File

/** Resolve only Magpie's own timestamped backup names in its private home. */
internal fun resolveBackupExport(home: File, rawPath: String): File? {
    if (rawPath.isBlank() || rawPath.length > 512) return null
    val name = rawPath.substringAfterLast('/').substringAfterLast('\\')
    if (!Regex("magpie-\\d{4}-\\d{2}-\\d{2}(?:-\\d+)?\\.magpie-backup").matches(name)) return null
    val downloads = File(home, "Downloads")
    val base = if (downloads.isDirectory) downloads else home
    val candidate = runCatching { File(base, name).canonicalFile }.getOrNull() ?: return null
    val basePath = runCatching { base.canonicalPath }.getOrNull() ?: return null
    return candidate.takeIf {
        it.path.startsWith("$basePath${File.separator}") && it.isFile
    }
}

internal object BackupImport {
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** Copy the selected document into the app's private import area before exposing it. */
    fun prepare(context: Context, uri: Uri): Uri? = runCatching {
        if (uri.scheme != "content") return null
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        if (displayName?.endsWith(".magpie-backup") != true) return null
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.magpie-backup")
        var total = 0L
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
        }.isSuccess
        if (!copied || total <= 0L) {
            target.delete()
            return null
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }.getOrNull()
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

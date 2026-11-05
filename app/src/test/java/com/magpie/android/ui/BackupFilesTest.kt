package com.magpie.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `resolves export in private home`() {
        val home = temporary.newFolder("home")
        val expected = File(home, "magpie-2026-11-05.magpie-backup").apply { writeText("test") }
        assertEquals(expected.canonicalFile, resolveBackupExport(home, "~/magpie-2026-11-05.magpie-backup"))
    }

    @Test
    fun `resolves export in private Downloads`() {
        val home = temporary.newFolder("home")
        val expected = File(home, "Downloads/magpie-2026-11-05-1.magpie-backup").apply {
            parentFile.mkdirs()
            writeText("test")
        }
        assertEquals(expected.canonicalFile, resolveBackupExport(home, expected.absolutePath))
    }

    @Test
    fun `prefers Downloads when both locations contain same basename`() {
        val home = temporary.newFolder("home")
        File(home, "magpie-2026-11-05-1.magpie-backup").writeText("old")
        val expected = File(home, "Downloads/magpie-2026-11-05-1.magpie-backup").apply {
            parentFile.mkdirs()
            writeText("new")
        }
        assertEquals(expected.canonicalFile, resolveBackupExport(home, expected.absolutePath))
    }

    @Test
    fun `does not fall back to stale home when Downloads exists`() {
        val home = temporary.newFolder("home")
        File(home, "magpie-2026-11-05-1.magpie-backup").writeText("old")
        File(home, "Downloads").mkdirs()
        assertNull(resolveBackupExport(home, "~/Downloads/magpie-2026-11-05-1.magpie-backup"))
    }

    @Test
    fun `rejects traversal wrong extension and missing files`() {
        val home = temporary.newFolder("home")
        File(home.parentFile, "magpie-outside.magpie-backup").writeText("outside")
        assertNull(resolveBackupExport(home, "../magpie-outside.magpie-backup"))
        assertNull(resolveBackupExport(home, "~/magpie-test.zip"))
        assertNull(resolveBackupExport(home, "~/magpie-missing.magpie-backup"))
        assertNull(resolveBackupExport(home, ""))
    }
}

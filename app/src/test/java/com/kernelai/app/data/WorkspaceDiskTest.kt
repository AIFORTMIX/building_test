package com.kernelai.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class WorkspaceDiskTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun createReadEditAndRejectStaleVersionsWithoutChangingFile() {
        val root = temporary.newFolder("workspace"); val access = WorkspaceDisk(root)
        access.mkdir("notes/daily"); access.write("notes/daily/hello.md", "# 初稿 🙂".toByteArray(), null, false)
        val before = WorkspacePaths.text(access.read("notes/daily/hello.md"))
        val edited = WorkspaceEdits.replace(before.content, "初稿", "终稿")
        access.write("notes/daily/hello.md", edited.toByteArray(), before.sha256, true)
        assertThrows(IllegalArgumentException::class.java) { access.write("notes/daily/hello.md", "错误覆盖".toByteArray(), before.sha256, true) }
        assertEquals("# 终稿 🙂", WorkspacePaths.text(access.read("notes/daily/hello.md")).content)
        assertFalse(root.walk().any { it.name.startsWith(".qingwei-") })
    }
    @Test fun existingFilesRequireExplicitVersionAndOverwrite() {
        val access = WorkspaceDisk(temporary.newFolder()); access.write("a.txt", "original".toByteArray(), null, false)
        assertThrows(IllegalArgumentException::class.java) { access.write("a.txt", "changed".toByteArray(), null, true) }
        assertThrows(IllegalArgumentException::class.java) { access.write("a.txt", "changed".toByteArray(), WorkspacePaths.hash("original".toByteArray()), false) }
        assertThrows(IllegalArgumentException::class.java) { access.write("missing.txt", "changed".toByteArray(), "previous-version", true) }
        assertEquals("original", access.read("a.txt").toString(Charsets.UTF_8))
    }
    @Test fun traversalAbsolutePathsAndInternalNamesAreRejected() {
        val root = temporary.newFolder("root"); val access = WorkspaceDisk(root)
        listOf("../outside", "/absolute", "a/../b", "a/./b", "C:/private", "a\\b", ".qingwei-write-x").forEach { path ->
            assertThrows("$path must be rejected", IllegalArgumentException::class.java) { access.write(path, byteArrayOf(1), null, false) }
        }
    }
    @Test fun symlinksCannotReadOrCreateOutsideTheWorkspace() {
        val root = temporary.newFolder("root"); val access = WorkspaceDisk(root)
        val outside = temporary.newFolder("outside"); java.io.File(outside, "secret.txt").writeText("private")
        try { Files.createSymbolicLink(java.io.File(root, "link").toPath(), outside.toPath()) }
        catch (failure: java.nio.file.FileSystemException) { org.junit.Assume.assumeNoException("Windows host does not grant symlink creation; this check also runs on Linux CI and Android", failure) }
        assertThrows(IllegalArgumentException::class.java) { access.read("link/secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { access.mkdir("link/created") }
        assertFalse(java.io.File(outside, "created").exists())
    }
    @Test fun binaryInvalidUtf8AndOversizedTextAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkspacePaths.text(byteArrayOf(0, 1)) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { WorkspacePaths.text(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePaths.text(ByteArray(WorkspacePaths.MAX_TEXT + 1)) }
        val root = temporary.newFolder(); java.io.File(root, "huge.txt").writeBytes(ByteArray(WorkspacePaths.MAX_TEXT + 1))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDisk(root).read("huge.txt") }
    }
    @Test fun exactEditsRejectMissingAmbiguousOrEmptyMatches() {
        listOf("", "missing", "repeat").forEach { old -> assertThrows(IllegalArgumentException::class.java) { WorkspaceEdits.replace("repeat repeat", old, "new") } }
        assertEquals("hello 世界", WorkspaceEdits.replace("hello world", "world", "世界"))
    }
    @Test fun directoryListingReportsTruncation() {
        val root = temporary.newFolder(); repeat(503) { java.io.File(root, "$it.txt").writeText("a") }
        val result = WorkspaceDisk(root).list(""); assertEquals(500, result.first.size); assertTrue(result.second)
    }
}

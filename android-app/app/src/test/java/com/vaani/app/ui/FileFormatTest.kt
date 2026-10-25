package com.vaani.app.ui

import com.vaani.app.ui.FileFormat.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FileFormatTest {

    @Test
    fun size() {
        assertNull(FileFormat.size(null))
        assertEquals("0 B", FileFormat.size(0))
        assertEquals("1023 B", FileFormat.size(1023))
        assertEquals("1 KB", FileFormat.size(1024))
        assertEquals("1.5 KB", FileFormat.size(1536))
        assertEquals("2.3 MB", FileFormat.size(2_400_000))
        assertEquals("150 MB", FileFormat.size(150L * 1024 * 1024))
        assertEquals("3 GB", FileFormat.size(3L * 1024 * 1024 * 1024))
    }

    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun modified() {
        assertEquals("Today 15:30", FileFormat.modified("2026-10-25T10:00:00+00:00", ist, today))
        assertEquals("Yesterday 09:00", FileFormat.modified("2026-10-24T03:30:00+00:00", ist, today))
        assertEquals("2 Mar", FileFormat.modified("2026-03-02T09:00:00+00:00", ist, today))
        assertEquals("1 Dec 2025", FileFormat.modified("2025-12-01T09:00:00+00:00", ist, today))
        // Late UTC evening is already "today" in India.
        assertEquals("Today 01:00", FileFormat.modified("2026-10-24T19:30:00+00:00", ist, today))
        assertNull(FileFormat.modified("yesterday-ish", ist, today))
        assertNull(FileFormat.modified(null, ist, today))
    }

    @Test
    fun location() {
        assertEquals("Documents › Projects", FileFormat.location("Documents/Projects/Main.java"))
        assertEquals("Downloads", FileFormat.location("Downloads/a.pdf"))
        assertEquals("", FileFormat.location("Downloads"))
    }

    @Test
    fun extensionAndKind() {
        assertEquals("PDF", FileFormat.extension("Report.Final.pdf"))
        assertEquals("XLSX", FileFormat.extension("marks.xlsx"))
        assertNull(FileFormat.extension(".gitignore"))
        assertNull(FileFormat.extension("README"))
        assertNull(FileFormat.extension("trailing."))
        assertEquals(Kind.Folder, FileFormat.kind("JavaProject", isFolder = true))
        assertEquals(Kind.Pdf, FileFormat.kind("a.PDF", isFolder = false))
        assertEquals(Kind.Code, FileFormat.kind("Main.java", isFolder = false))
        assertEquals(Kind.Image, FileFormat.kind("photo.jpeg", isFolder = false))
        assertEquals(Kind.Other, FileFormat.kind("setup.exe", isFolder = false))
    }
}

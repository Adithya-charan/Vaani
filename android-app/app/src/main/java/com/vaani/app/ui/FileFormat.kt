package com.vaani.app.ui

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Pure formatting helpers for file search results (JVM-testable, no Android APIs). */
object FileFormat {

    fun size(bytes: Long?): String? {
        if (bytes == null || bytes < 0) return null
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (value >= 100) "%.0f" else "%.1f"
        return String.format(Locale.US, text, value).removeSuffix(".0") + " " + units[unit]
    }

    /** "Today 14:05", "Yesterday 09:30", "12 Oct", or "12 Oct 2024" for other years. */
    fun modified(iso: String?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String? {
        if (iso == null) return null
        val time = try {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone)
        } catch (e: DateTimeParseException) {
            return null
        }
        val date = time.toLocalDate()
        val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
        return when {
            date == today -> "Today $clock"
            date == today.minusDays(1) -> "Yesterday $clock"
            date.year == today.year -> time.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
            else -> time.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
        }
    }

    /** "Documents/Projects/Java/Main.java" -> "Documents › Projects › Java". */
    fun location(path: String): String =
        path.split('/').dropLast(1).joinToString(" › ")

    /** Short badge text for a file name, e.g. "PDF". Null for folders or no extension. */
    fun extension(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).take(4).uppercase(Locale.US)
    }

    enum class Kind { Folder, Pdf, Document, Sheet, Slides, Image, Media, Archive, Code, Other }

    fun kind(name: String, isFolder: Boolean): Kind {
        if (isFolder) return Kind.Folder
        return when (extension(name)?.lowercase(Locale.US)) {
            "pdf" -> Kind.Pdf
            "doc", "docx", "txt", "rtf", "odt", "md" -> Kind.Document
            "xls", "xlsx", "csv", "ods" -> Kind.Sheet
            "ppt", "pptx", "odp", "key" -> Kind.Slides
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Kind.Image
            "mp3", "wav", "m4a", "mp4", "mkv", "mov", "avi" -> Kind.Media
            "zip", "rar", "7z", "tar", "gz" -> Kind.Archive
            "java", "kt", "py", "js", "ts", "c", "cpp", "h", "cs", "html", "css", "json", "xml" -> Kind.Code
            else -> Kind.Other
        }
    }
}

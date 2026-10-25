package com.vaani.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaani.app.R
import com.vaani.app.data.FoundFile
import com.vaani.app.data.SearchSummary
import com.vaani.app.ui.FileFormat.Kind
import com.vaani.app.ui.theme.VaaniTheme

private const val COLLAPSED_COUNT = 4

@Composable
fun SearchResults(files: List<FoundFile>, summary: SearchSummary?, key: String) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val visible = if (expanded) files else files.take(COLLAPSED_COUNT)

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        summary?.let { SummaryLine(it, files.size) }
        Spacer(Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                visible.forEachIndexed { i, file ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 60.dp), thickness = 0.5.dp)
                    FileRow(file)
                }
            }
        }
        if (files.size > COLLAPSED_COUNT) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "Show all ${files.size}")
            }
        }
    }
}

@Composable
private fun SummaryLine(summary: SearchSummary, shown: Int) {
    val where = summary.searchedFolders.joinToString(", ").ifEmpty { "approved folders" }
    val count = when {
        summary.totalMatches > shown -> "Top $shown of ${summary.totalMatches} matches"
        summary.totalMatches == 1 -> "1 match"
        else -> "${summary.totalMatches} matches"
    }
    Text(
        "$count · searched $where" + if (summary.truncated && summary.totalMatches <= shown) " (search stopped early)" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FileRow(file: FoundFile) {
    val details = listOfNotNull(
        FileFormat.size(file.sizeBytes),
        FileFormat.modified(file.modified),
    ).joinToString(" · ")
    val location = FileFormat.location(file.path)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(if (file.isFolder) "Folder " else "File ")
                    append(file.name)
                    if (location.isNotEmpty()) append(", in ").append(location.replace(" › ", ", "))
                    if (details.isNotEmpty()) append(", ").append(details)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(file)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (location.isNotEmpty()) {
                Text(
                    location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun kindColor(kind: Kind): Color = when (kind) {
    Kind.Folder -> Color(0xFFE8A33D)
    Kind.Pdf -> Color(0xFFD93025)
    Kind.Document -> Color(0xFF2B6CD9)
    Kind.Sheet -> Color(0xFF1E8E3E)
    Kind.Slides -> Color(0xFFE8710A)
    Kind.Image -> Color(0xFF9334E6)
    Kind.Media -> Color(0xFFC5221F)
    Kind.Archive -> Color(0xFF795548)
    Kind.Code -> Color(0xFF3A3F94)
    Kind.Other -> Color(0xFF757575)
}

@Composable
private fun KindBadge(file: FoundFile) {
    val kind = FileFormat.kind(file.name, file.isFolder)
    val color = kindColor(kind)
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == Kind.Folder) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        } else {
            Text(
                FileFormat.extension(file.name) ?: "FILE",
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SearchResultsPreview() {
    VaaniTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchResults(
                files = listOf(
                    FoundFile("JavaProject", "Documents/Projects/JavaProject", true, null, "2026-10-25T09:00:00+00:00"),
                    FoundFile("Project Report Final.pdf", "Downloads/Project Report Final.pdf", false, 2_400_000, "2026-10-24T09:00:00+00:00"),
                    FoundFile("marks.xlsx", "Desktop/College/marks.xlsx", false, 18_000, "2026-03-02T09:00:00+00:00"),
                    FoundFile("Main.java", "Documents/Projects/JavaProject/src/Main.java", false, 1_200, "2025-12-01T09:00:00+00:00"),
                    FoundFile("photo.jpg", "Desktop/photo.jpg", false, 3_100_000, "2026-10-01T09:00:00+00:00"),
                ),
                summary = SearchSummary(12, true, listOf("Documents", "Desktop", "Downloads")),
                key = "preview",
            )
        }
    }
}

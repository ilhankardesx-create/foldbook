package com.foldbook.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class NotebookBookGroup(
    val key: String,
    val title: String,
    val notes: List<BookNote>
)

@Composable
internal fun NotebookScreen(
    notes: List<BookNote>,
    books: List<LibraryBook>,
    onBack: () -> Unit,
    onDeleteNote: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedBookKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookNote?>(null) }

    val groups = remember(notes, books) {
        buildNotebookGroups(notes, books)
    }
    val selectedGroup = groups.firstOrNull { it.key == selectedBookKey }
    val selectedNote = selectedGroup?.notes?.firstOrNull { it.id == selectedNoteId }

    LaunchedEffect(selectedBookKey, selectedNoteId, groups) {
        if (selectedBookKey != null && selectedGroup == null) {
            selectedBookKey = null
            selectedNoteId = null
        } else if (selectedNoteId != null && selectedNote == null) {
            selectedNoteId = null
        }
    }

    BackHandler {
        when {
            selectedNoteId != null -> selectedNoteId = null
            selectedBookKey != null -> selectedBookKey = null
            else -> onBack()
        }
    }

    deleteCandidate?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = {
                Text(
                    text = "Not silinsin mi?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Bu not Not Defteri'nden kalıcı olarak silinecek.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteNote(note.id)
                        deleteCandidate = null
                        selectedNoteId = null
                    }
                ) {
                    Text(
                        text = "Sil",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Vazgeç")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFE8DFD0)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp)
        ) {
            NotebookTopBar(
                title = when {
                    selectedNote != null -> "Not"
                    selectedGroup != null -> selectedGroup.title
                    else -> "Not Defteri"
                },
                subtitle = when {
                    selectedNote != null -> "${selectedGroup?.title.orEmpty()} • Sayfa ${selectedNote.pageNumber}"
                    selectedGroup != null -> "${selectedGroup.notes.size} not"
                    else -> "Kitaplarına göre düzenlenmiş notların"
                },
                onBack = {
                    when {
                        selectedNoteId != null -> selectedNoteId = null
                        selectedBookKey != null -> selectedBookKey = null
                        else -> onBack()
                    }
                }
            )

            when {
                selectedNote != null && selectedGroup != null -> {
                    NotebookNoteDetail(
                        note = selectedNote,
                        bookTitle = selectedGroup.title,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "${selectedGroup.title} - Sayfa ${selectedNote.pageNumber}",
                                    selectedNote.text
                                )
                            )
                            Toast.makeText(
                                context,
                                "Not panoya kopyalandı.",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onDelete = { deleteCandidate = selectedNote }
                    )
                }

                selectedGroup != null -> {
                    NotebookBookNotes(
                        group = selectedGroup,
                        onOpenNote = { note -> selectedNoteId = note.id }
                    )
                }

                else -> {
                    NotebookBooks(
                        groups = groups,
                        onOpenBook = { group -> selectedBookKey = group.key }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(
                text = "‹",
                fontSize = 34.sp,
                lineHeight = 34.sp
            )
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 20.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E2923)
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = Color(0xFF2E2923).copy(alpha = 0.58f)
            )
        }

        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun NotebookBooks(
    groups: List<NotebookBookGroup>,
    onOpenBook: (NotebookBookGroup) -> Unit
) {
    if (groups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(28.dp)
            ) {
                Text("📝", fontSize = 52.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Not Defterin henüz boş",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Kitap okurken bir metni seçip “Not Defterine Ekle” dediğinde notların burada toplanacak.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF2E2923).copy(alpha = 0.66f)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 8.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = groups,
            key = { it.key }
        ) { group ->
            val latest = group.notes.first()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(group.key) {
                        detectTapGestures(onTap = { onOpenBook(group) })
                    },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFFBF3),
                shadowElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = group.title,
                            modifier = Modifier.weight(1f),
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${group.notes.size} not",
                            fontSize = 12.sp,
                            color = Color(0xFF2E2923).copy(alpha = 0.58f)
                        )
                    }

                    Text(
                        text = "Son not • Sayfa ${latest.pageNumber} • ${formatNoteDate(latest.createdAt)}",
                        fontSize = 12.sp,
                        color = Color(0xFF2E2923).copy(alpha = 0.55f)
                    )

                    Text(
                        text = latest.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = Color(0xFF2E2923).copy(alpha = 0.82f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookBookNotes(
    group: NotebookBookGroup,
    onOpenNote: (BookNote) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 8.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = group.notes,
            key = { it.id }
        ) { note ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(note.id) {
                        detectTapGestures(onTap = { onOpenNote(note) })
                    },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFFBF3),
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Sayfa ${note.pageNumber}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF5C4632)
                    )
                    Text(
                        text = note.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontFamily = FontFamily.Serif
                    )
                    Text(
                        text = formatNoteDate(note.createdAt),
                        fontSize = 11.sp,
                        color = Color(0xFF2E2923).copy(alpha = 0.50f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookNoteDetail(
    note: BookNote,
    bookTitle: String,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFFFFCF2),
            shadowElevation = 4.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 31.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFC96B62).copy(alpha = 0.24f))
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 46.dp, top = 24.dp, end = 24.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = bookTitle,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF3A3129)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sayfa ${note.pageNumber}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF6A5747)
                        )
                        Text(
                            text = "•",
                            fontSize = 12.sp,
                            color = Color(0xFF6A5747).copy(alpha = 0.55f)
                        )
                        Text(
                            text = formatNoteDate(note.createdAt),
                            fontSize = 12.sp,
                            color = Color(0xFF6A5747).copy(alpha = 0.72f)
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    SelectionContainer {
                        Text(
                            text = note.text,
                            fontFamily = FontFamily.Serif,
                            fontSize = 18.sp,
                            lineHeight = 29.sp,
                            color = Color(0xFF2E2923)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onCopy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Kopyala")
            }

            TextButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Notu Sil",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun buildNotebookGroups(
    notes: List<BookNote>,
    books: List<LibraryBook>
): List<NotebookBookGroup> {
    val bookTitlesByUri = books.associate { it.uri to it.title }

    return notes
        .groupBy { note ->
            note.bookUri.takeIf { it.isNotBlank() }
                ?: "legacy:${cleanFallbackBookTitle(note.bookTitle).lowercase()}"
        }
        .map { (key, groupedNotes) ->
            val first = groupedNotes.first()
            val title = bookTitlesByUri[first.bookUri]
                ?.takeIf { it.isNotBlank() }
                ?: cleanFallbackBookTitle(first.bookTitle)

            NotebookBookGroup(
                key = key,
                title = title,
                notes = groupedNotes.sortedByDescending { it.createdAt }
            )
        }
        .sortedByDescending { group ->
            group.notes.maxOfOrNull { it.createdAt } ?: 0L
        }
}

private fun cleanFallbackBookTitle(rawTitle: String): String {
    val withoutExtension = rawTitle
        .trim()
        .substringBeforeLast('.', rawTitle.trim())

    return withoutExtension
        .replace(Regex("^\\d+[\\s_-]+"), "")
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Kitap" }
}

private fun formatNoteDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return runCatching {
        SimpleDateFormat("d MMM yyyy • HH:mm", Locale.getDefault())
            .format(Date(timestamp))
    }.getOrDefault("")
}

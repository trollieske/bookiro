package com.bookrio.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.core.time.nowMillis
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.shared.platform.appDatabase
import com.bookrio.shared.platform.importBookWithPicker
import com.bookrio.shared.platform.presentPdfReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Bookrio HUD brand colours (mirrors the Android theme; kept local so :shared
// does not have to depend on :designsystem and its Compose-resources Gradle task).
private val Bg = Color(0xFF000000)
private val Panel = Color(0xFF0D0D0D)
private val Accent = Color(0xFFBEF93F)
private val Dim = Color(0xFF8C8C8C)
private val Fg = Color(0xFFF5F5F5)
private val Hairline = Color(0xFF1F1F1F)

/**
 * iOS entry screen. The library UI lives in commonMain (Compose Multiplatform);
 * opening a PDF hands off to the native page-curl reader in iosMain.
 */
@Composable
fun App() {
    val db = remember { appDatabase() }
    val scope = rememberCoroutineScope()
    val books by remember { db.bookDao().observeAll() }.collectAsState(initial = emptyList<BookEntity>())
    var message by remember { mutableStateOf<String?>(null) }

    val onImport: () -> Unit = {
        importBookWithPicker { filePath, fileName ->
            scope.launch {
                val format = formatFromName(fileName)
                if (format == FormatEntity.UNKNOWN) {
                    message = "\"$fileName\" har et format iOS-leseren ikke kjenner ennå."
                } else {
                    db.bookDao().insert(
                        BookEntity(
                            title = fileName.substringBeforeLast('.').ifBlank { fileName },
                            type = BookTypeEntity.EBOOK,
                            format = format,
                            filePath = filePath,
                            importSource = ImportSourceEntity.FILE_PICKER,
                        )
                    )
                    message = "\"$fileName\" importert."
                }
            }
        }
    }

    MaterialTheme {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(count = books.size, onImport = onImport)

                if (books.isEmpty()) {
                    EmptyLibrary(onImport = onImport)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(books, key = { it.id }) { book ->
                            BookRow(book = book, onClick = {
                                openBook(scope, db, book) { text -> message = text }
                            })
                        }
                    }
                }

                message?.let { text ->
                    Text(
                        text = text,
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Panel)
                            .clickable { message = null }
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(count: Int, onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(Accent, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("B", color = Bg, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "BOOKRIO",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text("$count bøker", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        TextButton(onClick = onImport) {
            Text("IMPORTER", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Ingen bøker ennå",
            color = Fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Importer en PDF for å prøve Apple sin innebygde sidekrøll " +
                "(UIPageViewController .pageCurl).",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onImport) {
            Text("IMPORTER BOK", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 34.dp, height = 46.dp)
                .background(Panel, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                book.format.name.take(3),
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                color = Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            if (book.author.isNotBlank()) {
                Text(book.author, color = Dim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

private fun openBook(
    scope: CoroutineScope,
    database: ShelfDatabase,
    book: BookEntity,
    onMessage: (String) -> Unit,
) {
    val path = book.filePath
    if (book.format != FormatEntity.PDF) {
        onMessage("iOS-leseren støtter PDF nå — ${book.format} kommer senere.")
        return
    }
    if (path.isNullOrBlank()) {
        onMessage("Boken mangler en lesbar fil på denne enheten.")
        return
    }
    scope.launch {
        runCatching { database.bookDao().update(book.copy(lastOpenedAt = nowMillis())) }
        val progress = runCatching { database.progressDao().getByBook(book.id) }.getOrNull()
        val presented = presentPdfReader(
            filePath = path,
            startPage = progress?.pageIndex ?: 0,
        ) { page, totalPages ->
            saveProgress(scope, database, book.id, page, totalPages)
        }
        if (!presented) onMessage("Kunne ikke åpne PDF-en.")
    }
}

private fun saveProgress(
    scope: CoroutineScope,
    database: ShelfDatabase,
    bookId: Long,
    page: Int,
    totalPages: Int,
) {
    val percent = if (totalPages > 0) page.toFloat() / totalPages.toFloat() else 0f
    scope.launch {
        runCatching {
            val prior = database.progressDao().getByBook(bookId)
            database.progressDao().insertOrReplace(
                (prior ?: ReadingProgressEntity(bookId = bookId)).copy(
                    pageIndex = page,
                    progressPercent = percent,
                    updatedAt = nowMillis(),
                ),
            )
        }
    }
}

private fun formatFromName(name: String): FormatEntity =
    when (name.substringAfterLast('.', "").lowercase()) {
        "epub" -> FormatEntity.EPUB
        "pdf" -> FormatEntity.PDF
        "mobi", "prc" -> FormatEntity.MOBI
        "azw" -> FormatEntity.AZW
        "azw3", "kf8" -> FormatEntity.AZW3
        "fb2" -> FormatEntity.FB2
        "cbz" -> FormatEntity.CBZ
        "cbr" -> FormatEntity.CBR
        "txt" -> FormatEntity.TXT
        "html", "htm", "xhtml" -> FormatEntity.HTML
        "rtf" -> FormatEntity.RTF
        "docx" -> FormatEntity.DOCX
        "md", "markdown" -> FormatEntity.MD
        "zip" -> FormatEntity.ZIP
        else -> FormatEntity.UNKNOWN
    }
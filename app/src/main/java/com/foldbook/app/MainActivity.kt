package com.foldbook.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val tracker = remember { WindowInfoTracker.getOrCreate(this@MainActivity) }
            val layoutInfo by tracker
                .windowLayoutInfo(this@MainActivity)
                .collectAsState(initial = WindowLayoutInfo(emptyList()))

            val foldingFeature = layoutInfo.displayFeatures
                .filterIsInstance<FoldingFeature>()
                .firstOrNull()

            FoldBookTheme {
                FoldBookApp(
                    hasSeparatingVerticalHinge = foldingFeature?.let {
                        it.orientation == FoldingFeature.Orientation.VERTICAL && it.isSeparating
                    } == true
                )
            }
        }
    }
}

@Composable
private fun FoldBookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color(0xFFE8DFD0),
            surface = Color(0xFFFFFBF3),
            onSurface = Color(0xFF2E2923),
            primary = Color(0xFF5C4632)
        ),
        content = content
    )
}

private data class ReaderPage(
    val chapter: String,
    val body: String
)

private data class ReaderPalette(
    val background: Color,
    val page: Color,
    val pageBack: Color,
    val text: Color
)

private val LocalReaderPalette = staticCompositionLocalOf {
    ReaderPalette(
        background = Color(0xFFE8DFD0),
        page = Color(0xFFFFFCF5),
        pageBack = Color(0xFFFFF7E8),
        text = Color(0xFF2E2923)
    )
}

private val LocalReaderFontSize = staticCompositionLocalOf { ReaderFontSize.MEDIUM }
private val LocalReaderPageCount = staticCompositionLocalOf { 0 }
private val LocalAddNote = staticCompositionLocalOf<(String, Int) -> Unit> { { _, _ -> } }
private val LocalBookHighlights = staticCompositionLocalOf<List<BookHighlight>> { emptyList() }
private val LocalAddHighlight = staticCompositionLocalOf<(String, Int, Int, Int) -> Unit> {
    { _, _, _, _ -> }
}

private fun fontSizeSp(size: ReaderFontSize): Float = when (size) {
    ReaderFontSize.SMALL -> 16f
    ReaderFontSize.MEDIUM -> 18f
    ReaderFontSize.LARGE -> 21f
}

private fun readerPalette(theme: ReaderThemeOption): ReaderPalette = when (theme) {
    ReaderThemeOption.LIGHT -> ReaderPalette(
        background = Color(0xFFE8DFD0),
        page = Color(0xFFFFFCF5),
        pageBack = Color(0xFFFFF7E8),
        text = Color(0xFF2E2923)
    )
    ReaderThemeOption.SEPIA -> ReaderPalette(
        background = Color(0xFFC9B38E),
        page = Color(0xFFF2DFC0),
        pageBack = Color(0xFFEAD4B1),
        text = Color(0xFF433523)
    )
    ReaderThemeOption.DARK -> ReaderPalette(
        background = Color(0xFF111111),
        page = Color(0xFF232323),
        pageBack = Color(0xFF1D1D1D),
        text = Color(0xFFE7E2D8)
    )
}

private fun EpubBook.toReaderPages(
    fontSize: ReaderFontSize,
    pageWidthPx: Int,
    pageHeightPx: Int,
    density: Float,
    scaledDensity: Float
): List<ReaderPage> {
    if (pageWidthPx <= 0 || pageHeightPx <= 0) return emptyList()

    val bodySizeSp = fontSizeSp(fontSize)
    val bodyTextSizePx = bodySizeSp * scaledDensity
    val bodyLineHeightPx = (bodySizeSp + 11f) * scaledDensity

    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = bodyTextSizePx
        typeface = Typeface.SERIF
    }

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (bodySizeSp + 1.5f) * scaledDensity
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    val titleLineHeightPx = (bodySizeSp + 7f) * scaledDensity

    // BookPage iç boşlukları + alttaki sayfa numarası için ayrılan alan.
    val contentWidthPx = (
        pageWidthPx - (64f * density)
    ).toInt().coerceAtLeast(120)

    // StaticLayout satır altlarını gerçek piksel yüksekliğiyle ölçüyor.
    // Yalnızca küçük Compose/StaticLayout farkı için ince bir güvenlik payı bırakıyoruz.
    val bottomSafetyPx = (bodyLineHeightPx * 1.0f).toInt()

    val contentHeightPx = (
        pageHeightPx -
            (28f * density) -      // üst 22dp + alt 6dp sayfa iç boşluğu
            (20f * density) -      // aşağı alınmış sayfa numarası alanı
            bottomSafetyPx         // yarım satır görünmesini engelleyen güvenli alan
    ).toInt().coerceAtLeast((bodyLineHeightPx * 4f).toInt())

    return buildList {
        for (chapter in chapters) {
            val normalized = chapter.text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n[ \\t]+"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()

            if (normalized.isBlank()) continue

            // Önceki sürüm her sayfa için metni tekrar tekrar ölçüyordu.
            // Şimdi bölümün tamamını yalnızca BİR kez layout ediyoruz.
            val bodyLayout = buildReaderLayout(
                text = normalized,
                paint = bodyPaint,
                widthPx = contentWidthPx,
                targetLineHeightPx = bodyLineHeightPx
            )

            if (bodyLayout.lineCount <= 0) continue

            val titleHeightPx = if (chapter.title.isNotBlank()) {
                buildReaderLayout(
                    text = chapter.title,
                    paint = titlePaint,
                    widthPx = contentWidthPx,
                    targetLineHeightPx = titleLineHeightPx
                ).height + (16f * density).toInt()
            } else {
                0
            }

            var startLine = 0
            var firstPage = true

            while (startLine < bodyLayout.lineCount) {
                val availableHeight = if (firstPage) {
                    (contentHeightPx - titleHeightPx)
                        .coerceAtLeast((bodyLineHeightPx * 3f).toInt())
                } else {
                    contentHeightPx
                }

                val pageTop = bodyLayout.getLineTop(startLine)
                var endLine = startLine

                while (endLine + 1 < bodyLayout.lineCount) {
                    val nextBottom = bodyLayout.getLineBottom(endLine + 1)
                    if (nextBottom - pageTop > availableHeight) break
                    endLine++
                }

                val startOffset = bodyLayout.getLineStart(startLine)
                val endOffset = bodyLayout.getLineEnd(endLine)

                val body = normalized
                    .substring(startOffset, endOffset)
                    .trim()

                if (body.isNotBlank()) {
                    add(
                        ReaderPage(
                            chapter = if (firstPage) chapter.title else "",
                            body = body
                        )
                    )
                    firstPage = false
                }

                startLine = endLine + 1
            }
        }
    }
}

private fun buildReaderLayout(
    text: String,
    paint: TextPaint,
    widthPx: Int,
    targetLineHeightPx: Float
): StaticLayout {
    val metrics = paint.fontMetrics
    val naturalLineHeight = metrics.descent - metrics.ascent
    val extraLineSpacing = (targetLineHeightPx - naturalLineHeight).coerceAtLeast(0f)

    return StaticLayout.Builder
        .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(extraLineSpacing, 1f)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
}

@Composable
private fun FoldBookApp(hasSeparatingVerticalHinge: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    var library by remember { mutableStateOf<List<LibraryBook>>(emptyList()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }

    var reading by remember { mutableStateOf(false) }
    var readerLoading by remember { mutableStateOf(false) }
    var readerError by remember { mutableStateOf<String?>(null) }
    var readerTitle by remember { mutableStateOf("") }
    var readerKey by remember { mutableStateOf("") }
    var readerFormat by remember { mutableStateOf(BookFormat.EPUB) }
    var readerBook by remember { mutableStateOf<EpubBook?>(null) }

    LaunchedEffect(reading) {
        val controller = WindowCompat.getInsetsController(activity.window, view)

        if (reading) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun scanFolder(uri: Uri) {
        scope.launch {
            libraryLoading = true
            libraryError = null

            runCatching {
                withContext(Dispatchers.IO) {
                    LibraryStore.scanFolder(context, uri)
                }
            }.onSuccess {
                library = it
            }.onFailure {
                libraryError = it.message ?: "Kitap klasörü okunamadı."
            }

            libraryLoading = false
        }
    }

    fun openBook(book: LibraryBook) {
        LibraryStore.saveLastOpened(context, book.uri)
        LibraryStore.saveActiveBook(context, book)

        scope.launch {
            readerLoading = true
            readerError = null
            readerKey = book.uri
            readerFormat = book.format

            if (book.format == BookFormat.PDF) {
                readerBook = null
                readerTitle = book.title
                reading = true
                readerLoading = false
                return@launch
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    EpubLoader.load(context, Uri.parse(book.uri))
                }
            }.onSuccess { epub ->
                if (epub.chapters.none { it.text.isNotBlank() }) {
                    readerError = "Bu EPUB içinde okunabilir metin bulunamadı."
                } else {
                    readerBook = epub
                    readerTitle = epub.title.ifBlank { book.title }
                    reading = true
                }
            }.onFailure {
                LibraryStore.clearActiveBook(context)
                readerError = it.message ?: "Kitap açılamadı."
            }

            readerLoading = false
        }
    }

    fun toggleFavorite(book: LibraryBook) {
        val favorite = LibraryStore.toggleFavorite(context, book)
        library = library.map { current ->
            if (current.uri == book.uri) {
                current.copy(isFavorite = favorite)
            } else {
                current
            }
        }
    }

    fun renameBook(book: LibraryBook, newTitle: String) {
        val folder = LibraryStore.savedFolder(context)

        scope.launch {
            libraryLoading = true
            libraryError = null

            runCatching {
                withContext(Dispatchers.IO) {
                    LibraryStore.renameBook(context, book, newTitle)
                    folder?.let { LibraryStore.scanFolder(context, it) }
                }
            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    library = refreshed
                }
            }.onFailure {
                libraryError = it.message ?: "Kitap yeniden adlandırılamadı."
            }

            libraryLoading = false
        }
    }

    fun deleteBook(book: LibraryBook) {
        val folder = LibraryStore.savedFolder(context)

        scope.launch {
            libraryLoading = true
            libraryError = null

            runCatching {
                withContext(Dispatchers.IO) {
                    LibraryStore.deleteBook(context, book)
                    folder?.let { LibraryStore.scanFolder(context, it) }
                }
            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    library = refreshed
                } else {
                    library = library.filterNot { it.uri == book.uri }
                }
            }.onFailure {
                libraryError = it.message ?: "Kitap silinemedi."
            }

            libraryLoading = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            LibraryStore.saveFolder(context, uri)
            scanFolder(uri)
        }
    }

    LaunchedEffect(Unit) {
        LibraryStore.savedFolder(context)?.let { scanFolder(it) }

        LibraryStore.readActiveBook(context)?.let { activeBook ->
            openBook(activeBook)
        }
    }

    fun closeReader() {
        LibraryStore.clearActiveBook(context)
        readerBook = null
        reading = false
        readerError = null
    }

    BackHandler(enabled = reading) {
        closeReader()
    }

    if (reading) {
        if (readerFormat == BookFormat.PDF) {
            PdfReaderScreen(
                bookKey = readerKey,
                hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
                onBack = ::closeReader
            )
        } else {
            readerBook?.let { book ->
                ReaderScreen(
                    book = book,
                    bookKey = readerKey,
                    bookTitle = readerTitle,
                    hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
                    onBack = ::closeReader
                )
            }
        }
    } else {
        LibraryScreen(
            books = library,
            isLoading = libraryLoading || readerLoading,
            error = libraryError ?: readerError,
            onChooseFolder = { folderPicker.launch(null) },
            onRefresh = {
                LibraryStore.savedFolder(context)?.let { scanFolder(it) }
            },
            onBookClick = ::openBook,
            onToggleFavorite = ::toggleFavorite,
            onRenameBook = ::renameBook,
            onDeleteBook = ::deleteBook
        )
    }
}

@Composable
private fun LibraryScreen(
    books: List<LibraryBook>,
    isLoading: Boolean,
    error: String?,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
    onBookClick: (LibraryBook) -> Unit,
    onToggleFavorite: (LibraryBook) -> Unit,
    onRenameBook: (LibraryBook, String) -> Unit,
    onDeleteBook: (LibraryBook) -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val density = LocalDensity.current
    val supportBilling = remember(activity) {
        SupportBillingManager(activity)
    }
    val supportProducts by supportBilling.products.collectAsState()
    val billingReady by supportBilling.ready.collectAsState()
    val billingMessage by supportBilling.message.collectAsState()

    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var supportVisible by rememberSaveable { mutableStateOf(false) }
    var notesVisible by rememberSaveable { mutableStateOf(false) }
    var notes by remember { mutableStateOf(LibraryStore.readNotes(context)) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var actionBook by remember { mutableStateOf<LibraryBook?>(null) }
    var renameBookTarget by remember { mutableStateOf<LibraryBook?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteBookTarget by remember { mutableStateOf<LibraryBook?>(null) }

    DisposableEffect(supportBilling) {
        supportBilling.start()
        onDispose {
            supportBilling.close()
        }
    }

    var selectedTheme by remember {
        mutableStateOf(LibraryStore.readReaderTheme(context))
    }
    var selectedFontSize by remember {
        mutableStateOf(LibraryStore.readReaderFontSize(context))
    }

    val lastOpenedUri = remember(books) {
        LibraryStore.readLastOpened(context)
    }

    val orderedBooks = remember(books, lastOpenedUri) {
        val lastOpenedBook = books.firstOrNull { it.uri == lastOpenedUri }
        if (lastOpenedBook == null) {
            books
        } else {
            buildList {
                add(lastOpenedBook)
                addAll(books.filterNot { it.uri == lastOpenedUri })
            }
        }
    }

    val filteredBooks = remember(orderedBooks, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            orderedBooks
        } else {
            orderedBooks.filter { book ->
                book.title.contains(query, ignoreCase = true)
            }
        }
    }

    if (notesVisible) {
        AlertDialog(
            onDismissRequest = { notesVisible = false },
            title = {
                Text(
                    text = "Notlar",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (notes.isEmpty()) {
                    Text(
                        text = "Henüz not eklenmemiş.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        notes.forEach { note ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = note.bookTitle,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )

                                    Text(
                                        text = "Sayfa ${note.pageNumber}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                                    )

                                    Text(
                                        text = note.text,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )

                                    TextButton(
                                        onClick = {
                                            LibraryStore.deleteNote(context, note.id)
                                            notes = LibraryStore.readNotes(context)
                                        }
                                    ) {
                                        Text(
                                            text = "Notu Sil",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { notesVisible = false }) {
                    Text("Kapat")
                }
            }
        )
    }

    actionBook?.let { book ->
        AlertDialog(
            onDismissRequest = { actionBook = null },
            title = {
                Text(
                    text = book.title,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onToggleFavorite(book)
                            actionBook = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (book.isFavorite) {
                                "♥ Favorilerden Çıkar"
                            } else {
                                "♥ Favorilere Ekle"
                            }
                        )
                    }

                    Button(
                        onClick = {
                            renameBookTarget = book
                            renameText = book.title
                            actionBook = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Yeniden Adlandır")
                    }

                    Button(
                        onClick = {
                            deleteBookTarget = book
                            actionBook = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kitabı Sil")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionBook = null }) {
                    Text("Kapat")
                }
            }
        )
    }

    renameBookTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { renameBookTarget = null },
            title = {
                Text(
                    text = "Yeniden Adlandır",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Kitap adı") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = renameText.trim()
                        if (title.isNotBlank()) {
                            onRenameBook(book, title)
                            renameBookTarget = null
                        }
                    }
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameBookTarget = null }) {
                    Text("İptal")
                }
            }
        )
    }

    deleteBookTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteBookTarget = null },
            title = {
                Text(
                    text = "Kitap silinsin mi?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "“${book.title}” cihazındaki kitap klasöründen silinecek."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteBook(book)
                        deleteBookTarget = null
                    }
                ) {
                    Text(
                        text = "Sil",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBookTarget = null }) {
                    Text("Vazgeç")
                }
            }
        )
    }

    if (supportVisible) {
        AlertDialog(
            onDismissRequest = {
                supportVisible = false
                supportBilling.clearMessage()
            },
            title = {
                Text(
                    text = "FoldBook'a Destek Ol ❤️",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "FoldBook'u sevdiysen küçük bir destek bırakabilirsin. Tamamen isteğe bağlıdır.",
                        fontSize = 14.sp
                    )

                    supportProducts.forEach { product ->
                        Button(
                            onClick = {
                                supportBilling.clearMessage()
                                supportBilling.purchase(product.productId)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${product.emoji} ${product.title}  •  ${product.priceLabel}"
                            )
                        }
                    }

                    if (!billingReady) {
                        Text(
                            text = "Google Play ödeme servisine bağlanıyor…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                    }

                    billingMessage?.let { message ->
                        Text(
                            text = message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        supportVisible = false
                        supportBilling.clearMessage()
                    }
                ) {
                    Text("Kapat")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kütüphane",
                    modifier = Modifier.weight(1f),
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface
                )

                TextButton(
                    onClick = {
                        notes = LibraryStore.readNotes(context)
                        notesVisible = true
                    }
                ) {
                    Text("Notlar")
                }

                TextButton(
                    onClick = { settingsVisible = !settingsVisible }
                ) {
                    Text("Ayarlar")
                }

                TextButton(
                    onClick = {
                        supportBilling.clearMessage()
                        supportVisible = true
                    }
                ) {
                    Text("Destek")
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = {
                    Text("🔍  Kitap ara")
                },
                singleLine = true,
                shape = RoundedCornerShape(22.dp)
            )

            if (settingsVisible) {
                val popupOffsetY = with(density) { 168.dp.roundToPx() }

                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, popupOffsetY),
                    onDismissRequest = { settingsVisible = false },
                    properties = PopupProperties(
                        focusable = true,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true
                    )
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .shadow(10.dp, RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onChooseFolder,
                                    enabled = !isLoading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (isLoading) {
                                            "Taranıyor…"
                                        } else {
                                            "Kitapların Olduğu Klasörü Seç"
                                        },
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Button(
                                    onClick = onRefresh,
                                    enabled = !isLoading
                                ) {
                                    Text("Yenile")
                                }
                            }

                            Text(
                                text = "Tema",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    ReaderThemeOption.LIGHT to "Açık",
                                    ReaderThemeOption.SEPIA to "Sepya",
                                    ReaderThemeOption.DARK to "Koyu"
                                ).forEach { (option, label) ->
                                    Button(
                                        onClick = {
                                            selectedTheme = option
                                            LibraryStore.saveReaderTheme(context, option)
                                        }
                                    ) {
                                        Text(
                                            if (selectedTheme == option) "✓ $label" else label
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "EPUB yazı boyutu",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    ReaderFontSize.SMALL to "Küçük",
                                    ReaderFontSize.MEDIUM to "Orta",
                                    ReaderFontSize.LARGE to "Büyük"
                                ).forEach { (option, label) ->
                                    Button(
                                        onClick = {
                                            selectedFontSize = option
                                            LibraryStore.saveReaderFontSize(context, option)
                                        }
                                    ) {
                                        Text(
                                            if (selectedFontSize == option) "✓ $label" else label
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "PDF sabit sayfa düzenini korur; tema PDF'ye de uygulanır.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            )

                            Text(
                                text = "v0.9.20",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            when {
                books.isEmpty() && !isLoading -> {
                    EmptyLibrary(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        onChooseFolder = onChooseFolder
                    )
                }

                filteredBooks.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Bu isimde kitap bulunamadı.",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 102.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 10.dp,
                            bottom = 28.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            items = filteredBooks,
                            key = { it.uri }
                        ) { book ->
                            ShelfBook(
                                book = book,
                                isLastOpened = book.uri == lastOpenedUri,
                                onClick = { onBookClick(book) },
                                onLongPress = { actionBook = book }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    modifier: Modifier = Modifier,
    onChooseFolder: () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📖",
                fontSize = 64.sp
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Rafın henüz boş",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "EPUB ve PDF kitaplarının bulunduğu klasörü bir kez seç. FoldBook klasörü hatırlayıp kitaplarını burada rafa dizecek.",
                modifier = Modifier.fillMaxWidth(0.72f),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )

            Spacer(Modifier.height(22.dp))

            Button(onClick = onChooseFolder) {
                Text("Kitapların Olduğu Klasörü Seç")
            }
        }
    }
}

@Composable
private fun ShelfBook(
    book: LibraryBook,
    isLastOpened: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val coverBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = book.uri,
        key2 = book.format
    ) {
        value = withContext(Dispatchers.IO) {
            when (book.format) {
                BookFormat.EPUB -> EpubLoader.loadCover(context, Uri.parse(book.uri))?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                BookFormat.PDF -> loadPdfCover(context, Uri.parse(book.uri))
            }
        }
    }

    val covers = listOf(
        Color(0xFF6D4937),
        Color(0xFF425B4D),
        Color(0xFF596779),
        Color(0xFF77515C),
        Color(0xFF79613F),
        Color(0xFF4F526A)
    )
    val coverColor = covers[(book.title.hashCode() and Int.MAX_VALUE) % covers.size]

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.67f)
            .shadow(
                elevation = if (isLastOpened) 10.dp else 6.dp,
                shape = RoundedCornerShape(5.dp)
            )
            .pointerInput(book.uri) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() }
                )
            },
        shape = RoundedCornerShape(5.dp),
        color = coverColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap!!.asImageBitmap(),
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(9.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(4.dp)
                            .align(Alignment.CenterStart)
                            .background(Color.Black.copy(alpha = 0.13f))
                    )

                    Text(
                        text = book.title,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 5.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFFFF8EA)
                    )
                }
            }

            if (isLastOpened) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 2.dp,
                            color = Color(0xFFE7C98A).copy(alpha = 0.92f),
                            shape = RoundedCornerShape(5.dp)
                        )
                        .zIndex(3f)
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 7.dp)
                        .zIndex(4f),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Text(
                        text = "Son okunan",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }

            if (book.isFavorite) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .zIndex(5f),
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.86f)
                ) {
                    Text(
                        text = "♥",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color(0xFFD32F2F),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    book: EpubBook,
    bookKey: String,
    bookTitle: String,
    hasSeparatingVerticalHinge: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val densityInfo = LocalDensity.current

    var controlsVisible by rememberSaveable(bookKey) { mutableStateOf(false) }
    var highlights by remember(bookKey) {
        mutableStateOf(LibraryStore.readHighlights(context, bookKey))
    }
    var themeName by rememberSaveable(bookKey) {
        mutableStateOf(LibraryStore.readReaderTheme(context).name)
    }
    val theme = runCatching {
        ReaderThemeOption.valueOf(themeName)
    }.getOrDefault(ReaderThemeOption.LIGHT)
    val fontSize = remember(bookKey) {
        LibraryStore.readReaderFontSize(context)
    }
    val palette = readerPalette(theme)

    var ttsReady by remember { mutableStateOf(false) }
    var ttsActive by rememberSaveable(bookKey) { mutableStateOf(false) }
    var ttsPaused by rememberSaveable(bookKey) { mutableStateOf(false) }
    var ttsRate by rememberSaveable(bookKey) { mutableFloatStateOf(1.0f) }
    var ttsMessage by remember { mutableStateOf<String?>(null) }

    var currentSpreadIndex by rememberSaveable(bookKey) { mutableIntStateOf(0) }
    var ttsReadPageIndex by rememberSaveable(bookKey) { mutableIntStateOf(0) }
    var ttsCharOffset by rememberSaveable(bookKey) { mutableIntStateOf(0) }
    var ttsSpeakBaseOffset by remember { mutableIntStateOf(0) }
    var speakRequestToken by remember { mutableIntStateOf(0) }
    var utteranceDoneToken by remember { mutableIntStateOf(0) }
    var autoForwardToken by remember { mutableIntStateOf(0) }
    var utteranceSerial by remember { mutableIntStateOf(0) }

    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            activity.runOnUiThread {
                ttsReady = status == TextToSpeech.SUCCESS
                ttsMessage = if (ttsReady) null else "Yerel ses motoru başlatılamadı."
            }
        }
    }

    DisposableEffect(tts) {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    activity.runOnUiThread {
                        if (ttsActive && !ttsPaused) {
                            ttsCharOffset = 0
                            utteranceDoneToken++
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    activity.runOnUiThread {
                        ttsMessage = "Sesli okuma sırasında bir hata oluştu."
                        ttsActive = false
                        ttsPaused = false
                    }
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int
                ) {
                    activity.runOnUiThread {
                        if (ttsActive && !ttsPaused) {
                            ttsCharOffset = (ttsSpeakBaseOffset + start).coerceAtLeast(0)
                        }
                    }
                }
            }
        )

        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    LaunchedEffect(ttsReady, ttsRate) {
        if (ttsReady) {
            val localeResult = tts.setLanguage(Locale.getDefault())
            if (
                localeResult == TextToSpeech.LANG_MISSING_DATA ||
                localeResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.setLanguage(Locale("tr", "TR"))
            }
            tts.setSpeechRate(ttsRate)
        }
    }

    CompositionLocalProvider(
        LocalReaderPalette provides palette,
        LocalReaderFontSize provides fontSize
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.background
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                val twoPage = hasSeparatingVerticalHinge || maxWidth >= 700.dp

                val containerWidthPx = with(densityInfo) { maxWidth.toPx() }
                val containerHeightPx = with(densityInfo) { maxHeight.toPx() }
                val density = densityInfo.density
                val scaledDensity = densityInfo.density * densityInfo.fontScale

                val pageWidthPx = if (twoPage) {
                    ((containerWidthPx - (10f * density)) / 2f)
                        .toInt()
                        .coerceAtLeast(1)
                } else {
                    (containerWidthPx - (6f * density))
                        .toInt()
                        .coerceAtLeast(1)
                }

                val pageHeightPx = (containerHeightPx - (8f * density))
                    .toInt()
                    .coerceAtLeast(1)

                val pages by produceState(
                    initialValue = emptyList<ReaderPage>(),
                    book,
                    fontSize,
                    pageWidthPx,
                    pageHeightPx,
                    density,
                    scaledDensity
                ) {
                    value = withContext(Dispatchers.Default) {
                        book.toReaderPages(
                            fontSize = fontSize,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            density = density,
                            scaledDensity = scaledDensity
                        )
                    }
                }

                if (pages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Sayfalar hazırlanıyor…",
                            color = palette.text.copy(alpha = 0.72f),
                            fontSize = 14.sp
                        )
                    }
                } else {
                    val savedRawPage = remember(bookKey, pages.size) {
                        LibraryStore.readProgress(
                            context = context,
                            bookUri = bookKey,
                            lastPageIndex = pages.lastIndex
                        )
                    }
                    val savedPage = if (twoPage) {
                        (savedRawPage - (savedRawPage % 2)).coerceAtLeast(0)
                    } else {
                        savedRawPage
                    }

                    LaunchedEffect(bookKey, pages.size, twoPage) {
                        currentSpreadIndex = savedPage.coerceIn(0, pages.lastIndex)
                        if (!ttsActive) {
                            ttsReadPageIndex = currentSpreadIndex
                            ttsCharOffset = 0
                        }
                    }

                    fun speechText(page: ReaderPage): String {
                        return buildString {
                            if (page.chapter.isNotBlank()) {
                                append(page.chapter.trim())
                                append(". ")
                            }
                            append(page.body.trim())
                        }.trim()
                    }

                    LaunchedEffect(
                        speakRequestToken,
                        ttsReady,
                        ttsActive,
                        ttsPaused,
                        ttsRate,
                        pages.size
                    ) {
                        if (
                            speakRequestToken > 0 &&
                            ttsReady &&
                            ttsActive &&
                            !ttsPaused
                        ) {
                            val page = pages.getOrNull(ttsReadPageIndex)
                            val fullText = page?.let(::speechText).orEmpty()

                            if (fullText.isBlank()) {
                                utteranceDoneToken++
                            } else {
                                val safeOffset = ttsCharOffset
                                    .coerceIn(0, (fullText.length - 1).coerceAtLeast(0))

                                ttsSpeakBaseOffset = safeOffset
                                utteranceSerial++

                                tts.setSpeechRate(ttsRate)
                                tts.speak(
                                    fullText.substring(safeOffset),
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "foldbook-${ttsReadPageIndex}-$utteranceSerial"
                                )
                            }
                        }
                    }

                    LaunchedEffect(utteranceDoneToken) {
                        if (
                            utteranceDoneToken <= 0 ||
                            !ttsActive ||
                            ttsPaused
                        ) return@LaunchedEffect

                        val rightPageIndex = if (twoPage) {
                            currentSpreadIndex + 1
                        } else {
                            currentSpreadIndex
                        }

                        if (
                            twoPage &&
                            ttsReadPageIndex < rightPageIndex &&
                            ttsReadPageIndex + 1 <= pages.lastIndex
                        ) {
                            ttsReadPageIndex++
                            ttsCharOffset = 0
                            speakRequestToken++
                        } else {
                            val step = if (twoPage) 2 else 1
                            if (currentSpreadIndex + step <= pages.lastIndex) {
                                autoForwardToken++
                            } else {
                                tts.stop()
                                ttsActive = false
                                ttsPaused = false
                                ttsCharOffset = 0
                                ttsMessage = "Kitabın sonuna geldin."
                            }
                        }
                    }

                    CompositionLocalProvider(
                        LocalAddNote provides { text, pageNumber ->
                            LibraryStore.addNote(
                                context = context,
                                bookUri = bookKey,
                                bookTitle = bookTitle,
                                pageNumber = pageNumber,
                                text = text
                            )
                            Toast.makeText(
                                context,
                                "Notlara eklendi.",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        LocalBookHighlights provides highlights,
                        LocalAddHighlight provides { text, pageNumber, startOffset, endOffset ->
                            LibraryStore.addHighlight(
                                context = context,
                                bookUri = bookKey,
                                pageNumber = pageNumber,
                                startOffset = startOffset,
                                endOffset = endOffset,
                                text = text
                            )
                            highlights = LibraryStore.readHighlights(context, bookKey)
                            Toast.makeText(
                                context,
                                "Sarı fosforla çizildi.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        BookSpread(
                            pages = pages,
                            bookKey = bookKey,
                            initialPageIndex = savedPage,
                            onPageChanged = { pageIndex ->
                                LibraryStore.saveProgress(
                                    context = context,
                                    bookUri = bookKey,
                                    pageIndex = pageIndex
                                )

                                currentSpreadIndex = pageIndex

                                if (ttsActive) {
                                    tts.stop()
                                    ttsReadPageIndex = pageIndex
                                    ttsCharOffset = 0

                                    if (!ttsPaused) {
                                        speakRequestToken++
                                    }
                                }
                            },
                            twoPage = twoPage,
                            autoForwardToken = autoForwardToken,
                            onSingleTap = {
                                controlsVisible = !controlsVisible
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    AnimatedVisibility(
                        visible = controlsVisible,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .zIndex(20f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color.Black.copy(alpha = 0.76f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            tts.stop()
                                            ttsActive = false
                                            ttsPaused = false
                                            onBack()
                                        }
                                    ) {
                                        Text("Rafa Dön")
                                    }

                                    when {
                                        !ttsReady -> {
                                            Button(
                                                onClick = {},
                                                enabled = false
                                            ) {
                                                Text("🔊 Hazırlanıyor")
                                            }
                                        }

                                        !ttsActive -> {
                                            Button(
                                                onClick = {
                                                    ttsMessage = null
                                                    ttsActive = true
                                                    ttsPaused = false
                                                    ttsReadPageIndex = currentSpreadIndex
                                                    ttsCharOffset = 0
                                                    speakRequestToken++
                                                }
                                            ) {
                                                Text("🔊 Sesli Oku")
                                            }
                                        }

                                        !ttsPaused -> {
                                            Button(
                                                onClick = {
                                                    tts.stop()
                                                    ttsPaused = true
                                                }
                                            ) {
                                                Text("⏸ Duraklat")
                                            }
                                        }

                                        else -> {
                                            Button(
                                                onClick = {
                                                    ttsPaused = false
                                                    speakRequestToken++
                                                }
                                            ) {
                                                Text("▶ Devam")
                                            }
                                        }
                                    }

                                    if (ttsActive) {
                                        Button(
                                            onClick = {
                                                tts.stop()
                                                ttsActive = false
                                                ttsPaused = false
                                                ttsCharOffset = 0
                                            }
                                        ) {
                                            Text("⏹ Durdur")
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            ttsRate = when (ttsRate) {
                                                0.8f -> 1.0f
                                                1.0f -> 1.2f
                                                1.2f -> 1.5f
                                                else -> 0.8f
                                            }

                                            if (ttsActive && !ttsPaused) {
                                                tts.stop()
                                                speakRequestToken++
                                            }
                                        }
                                    ) {
                                        Text("${ttsRate}x")
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        ReaderThemeOption.LIGHT to "Açık",
                                        ReaderThemeOption.SEPIA to "Sepya",
                                        ReaderThemeOption.DARK to "Koyu"
                                    ).forEach { (option, label) ->
                                        Button(
                                            onClick = {
                                                themeName = option.name
                                                LibraryStore.saveReaderTheme(context, option)
                                            }
                                        ) {
                                            Text(label)
                                        }
                                    }
                                }

                                ttsMessage?.let { message ->
                                    Text(
                                        text = message,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.88f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookSpread(
    pages: List<ReaderPage>,
    bookKey: String,
    initialPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    twoPage: Boolean,
    autoForwardToken: Int = 0,
    onSingleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable(bookKey) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
    }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragYFraction by remember { mutableFloatStateOf(0.5f) }
    var turnDirection by remember { mutableIntStateOf(0) }
    var pageWidthPx by remember { mutableFloatStateOf(1f) }
    var pageHeightPx by remember { mutableFloatStateOf(1f) }
    var settling by remember { mutableStateOf(false) }

    val settleAnimation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val step = if (twoPage) 2 else 1
    val progress = if (settling) settleAnimation.value else dragProgress

    fun canTurn(direction: Int): Boolean = when (direction) {
        1 -> pageIndex + step <= pages.lastIndex
        -1 -> pageIndex - step >= 0
        else -> false
    }

    fun settleTurn(cancelOnly: Boolean = false) {
        val direction = turnDirection
        val start = dragProgress
        val shouldComplete =
            !cancelOnly && direction != 0 && canTurn(direction) && start >= 0.20f

        scope.launch {
            settling = true
            settleAnimation.snapTo(start)
            settleAnimation.animateTo(
                targetValue = if (shouldComplete) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (shouldComplete) 230 else 160,
                    easing = FastOutSlowInEasing
                )
            )

            if (shouldComplete) {
                val newIndex = (pageIndex + if (direction == 1) step else -step)
                    .coerceIn(0, pages.lastIndex.coerceAtLeast(0))

                Snapshot.withMutableSnapshot {
                    pageIndex = newIndex
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = 0.5f
                    turnDirection = 0
                    settling = false
                }

                onPageChanged(newIndex)
                settleAnimation.snapTo(0f)
            } else {
                Snapshot.withMutableSnapshot {
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = 0.5f
                    turnDirection = 0
                    settling = false
                }

                settleAnimation.snapTo(0f)
            }
        }
    }

    LaunchedEffect(bookKey, initialPageIndex) {
        pageIndex = initialPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        dragPx = 0f
        dragProgress = 0f
        dragYFraction = 0.5f
        turnDirection = 0
    }

    LaunchedEffect(twoPage) {
        if (twoPage && pageIndex % 2 != 0) {
            pageIndex = (pageIndex - 1).coerceAtLeast(0)
        }

        dragPx = 0f
        dragProgress = 0f
        dragYFraction = 0.5f
        turnDirection = 0
    }

    LaunchedEffect(autoForwardToken) {
        if (
            autoForwardToken > 0 &&
            !settling &&
            canTurn(1)
        ) {
            settling = true
            turnDirection = 1
            dragPx = -pageWidthPx
            dragProgress = 0f
            dragYFraction = 0.68f

            settleAnimation.snapTo(0f)
            settleAnimation.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 360,
                    easing = FastOutSlowInEasing
                )
            )

            val newIndex = (pageIndex + step)
                .coerceIn(0, pages.lastIndex.coerceAtLeast(0))

            Snapshot.withMutableSnapshot {
                pageIndex = newIndex
                dragPx = 0f
                dragProgress = 0f
                dragYFraction = 0.5f
                turnDirection = 0
                settling = false
            }

            onPageChanged(newIndex)
            settleAnimation.snapTo(0f)
        }
    }

    val gestureModifier = Modifier
        .onSizeChanged {
            pageWidthPx = if (twoPage) it.width / 2f else it.width.toFloat()
            pageHeightPx = it.height.toFloat().coerceAtLeast(1f)
        }
        .pointerInput(pageIndex, twoPage, pageWidthPx, pageHeightPx, settling) {
            if (settling) return@pointerInput

            detectDragGestures(
                onDragStart = { startOffset ->
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = (startOffset.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                    turnDirection = 0
                },
                onDrag = { change, dragAmount ->
                    change.consume()

                    dragYFraction = (change.position.y / pageHeightPx)
                        .coerceIn(0.06f, 0.94f)

                    val proposed = (dragPx + dragAmount.x)
                        .coerceIn(-pageWidthPx, pageWidthPx)

                    val direction = when {
                        proposed < 0f -> 1
                        proposed > 0f -> -1
                        else -> 0
                    }

                    if (direction == 0 || canTurn(direction)) {
                        dragPx = proposed
                        turnDirection = direction
                        dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                    } else {
                        dragPx = proposed.coerceIn(
                            -pageWidthPx * 0.06f,
                            pageWidthPx * 0.06f
                        )
                        dragProgress = 0f
                        turnDirection = 0
                    }
                },
                onDragEnd = { settleTurn() },
                onDragCancel = { settleTurn(cancelOnly = true) }
            )
        }
        .pointerInput(bookKey, pageIndex, twoPage) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val startPosition = down.position
                val startTime = down.uptimeMillis
                var moved = false

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: break

                    val dx = change.position.x - startPosition.x
                    val dy = change.position.y - startPosition.y

                    if (
                        kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                        kotlin.math.abs(dy) > viewConfiguration.touchSlop
                    ) {
                        moved = true
                    }

                    if (!change.pressed) {
                        val duration = change.uptimeMillis - startTime

                        if (
                            !moved &&
                            duration < viewConfiguration.longPressTimeoutMillis
                        ) {
                            onSingleTap()
                        }
                        break
                    }
                }
            }
        }

    Box(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalReaderPageCount provides pages.size
        ) {
            if (twoPage) {
                TwoPageSpread(
                    pages = pages,
                    pageIndex = pageIndex,
                    turnDirection = turnDirection,
                    progress = progress,
                    curlY = dragYFraction
                )
            } else {
                SinglePageSpread(
                    pages = pages,
                    pageIndex = pageIndex,
                    turnDirection = turnDirection,
                    progress = progress,
                    curlY = dragYFraction
                )
            }
        }
    }
}

@Composable
private fun TwoPageSpread(
    pages: List<ReaderPage>,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    curlY: Float
) {
    val isForward = turnDirection == 1
    val isBackward = turnDirection == -1

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .zIndex(if (isBackward) 3f else 0f)
        ) {
            val leftPage = if (isBackward) {
                pages.getOrNull(pageIndex - 2)
            } else {
                pages.getOrNull(pageIndex)
            }
            val leftNumber = if (isBackward) pageIndex - 1 else pageIndex + 1

            BookPage(
                page = leftPage,
                pageNumber = leftNumber.coerceAtLeast(1),
                modifier = Modifier.fillMaxSize()
            )

            if (isBackward) {
                TurningPage(
                    frontPage = pages.getOrNull(pageIndex),
                    frontNumber = pageIndex + 1,
                    backPage = pages.getOrNull(pageIndex - 1),
                    backNumber = pageIndex,
                    progress = progress,
                    direction = -1,
                    curlY = curlY,
                    crossSpine = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )
            }
        }

        BookSpine(progress = progress)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .zIndex(if (isForward) 3f else 0f)
        ) {
            val rightPage = if (isForward) {
                pages.getOrNull(pageIndex + 3)
            } else {
                pages.getOrNull(pageIndex + 1)
            }
            val rightNumber = if (isForward) pageIndex + 4 else pageIndex + 2

            BookPage(
                page = rightPage,
                pageNumber = rightNumber,
                modifier = Modifier.fillMaxSize()
            )

            if (isForward) {
                TurningPage(
                    frontPage = pages.getOrNull(pageIndex + 1),
                    frontNumber = pageIndex + 2,
                    backPage = pages.getOrNull(pageIndex + 2),
                    backNumber = pageIndex + 3,
                    progress = progress,
                    direction = 1,
                    curlY = curlY,
                    crossSpine = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )
            }
        }
    }
}

@Composable
private fun SinglePageSpread(
    pages: List<ReaderPage>,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    curlY: Float
) {
    val targetIndex = when (turnDirection) {
        1 -> pageIndex + 1
        -1 -> pageIndex - 1
        else -> pageIndex
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 3.dp, vertical = 4.dp)
    ) {
        BookPage(
            page = pages.getOrNull(targetIndex),
            pageNumber = targetIndex + 1,
            modifier = Modifier.fillMaxSize()
        )

        if (turnDirection != 0) {
            TurningPage(
                frontPage = pages.getOrNull(pageIndex),
                frontNumber = pageIndex + 1,
                backPage = null,
                backNumber = 0,
                progress = progress,
                direction = turnDirection,
                curlY = curlY,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            )
        }
    }
}

@Composable
private fun TurningPage(
    frontPage: ReaderPage?,
    frontNumber: Int,
    backPage: ReaderPage?,
    backNumber: Int,
    progress: Float,
    direction: Int,
    curlY: Float,
    crossSpine: Boolean = false,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val showingBack = p > 0.5f
    val rotation = if (direction == 1) -180f * p else 180f * p
    val spineShiftPx = if (crossSpine) {
        with(LocalDensity.current) { 10.dp.toPx() } * p
    } else {
        0f
    }
    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }

    Box(
        modifier = modifier.graphicsLayer {
            transformOrigin = origin
            rotationY = rotation
            translationX = when {
                !crossSpine -> 0f
                direction == 1 -> -spineShiftPx
                else -> spineShiftPx
            }
            cameraDistance = 30f
            shadowElevation = 18f * (1f - abs(0.5f - p) * 2f)
            scaleY = 1f
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (showingBack) scaleX = -1f
                }
        ) {
            if (showingBack && backPage != null) {
                BookPage(
                    page = backPage,
                    pageNumber = backNumber,
                    isBackSide = false,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BookPage(
                    page = frontPage,
                    pageNumber = frontNumber,
                    isBackSide = false,
                    modifier = Modifier.fillMaxSize()
                )
            }

            PageEdgeShadow(
                direction = direction,
                progress = p
            )
        }
    }
}

@Composable
private fun BoxScope.PageEdgeShadow(
    direction: Int,
    progress: Float
) {
    val strength = (1f - abs(0.5f - progress) * 2f).coerceIn(0f, 1f)
    val dark = Color.Black.copy(alpha = 0.18f * strength)
    val clear = Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(26.dp)
            .align(if (direction == 1) Alignment.CenterStart else Alignment.CenterEnd)
            .background(
                Brush.horizontalGradient(
                    colors = if (direction == 1) {
                        listOf(dark, clear)
                    } else {
                        listOf(clear, dark)
                    }
                )
            )
    )
}

@Composable
private fun BookSpine(progress: Float) {
    val shadowStrength =
        0.07f + (0.10f * (1f - abs(0.5f - progress) * 2f))

    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.025f),
                        Color.Black.copy(alpha = shadowStrength),
                        Color.Black.copy(alpha = 0.025f)
                    )
                ),
                RoundedCornerShape(50)
            )
    )
}

@Composable
private fun BookPage(
    page: ReaderPage?,
    pageNumber: Int,
    isBackSide: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = LocalReaderPalette.current
    val readerFontSize = LocalReaderFontSize.current
    val totalPages = LocalReaderPageCount.current
    val addNote = LocalAddNote.current
    val highlights = LocalBookHighlights.current
    val addHighlight = LocalAddHighlight.current
    val bodySize = fontSizeSp(readerFontSize)
    val bodyText = page?.body.orEmpty()

    val highlightRanges = remember(bodyText, pageNumber, highlights) {
        buildList<Pair<Int, Int>> {
            val usedIds = mutableSetOf<String>()

            highlights.forEach { highlight ->
                if (
                    highlight.pageNumber == pageNumber &&
                    highlight.startOffset >= 0 &&
                    highlight.endOffset <= bodyText.length &&
                    highlight.endOffset > highlight.startOffset
                ) {
                    val anchoredText = bodyText.substring(
                        highlight.startOffset,
                        highlight.endOffset
                    )
                    if (anchoredText.trim() == highlight.text.trim()) {
                        add(highlight.startOffset to highlight.endOffset)
                        usedIds += highlight.id
                    }
                }
            }

            highlights.forEach { highlight ->
                if (highlight.id in usedIds) return@forEach
                val quote = highlight.text.trim()
                if (quote.length < 12) return@forEach

                val foundAt = bodyText.indexOf(quote)
                if (foundAt >= 0) {
                    add(foundAt to (foundAt + quote.length))
                }
            }
        }.distinct()
    }

    val highlightedBody = remember(bodyText, highlightRanges) {
        buildAnnotatedString {
            append(bodyText)
            highlightRanges.forEach { (start, end) ->
                if (start >= 0 && end <= bodyText.length && end > start) {
                    addStyle(
                        style = SpanStyle(
                            background = Color(0xFFFFE45C).copy(alpha = 0.58f)
                        ),
                        start = start,
                        end = end
                    )
                }
            }
        }
    }

    var bodyValue by remember(bodyText, highlightedBody) {
        mutableStateOf(
            TextFieldValue(
                annotatedString = highlightedBody
            )
        )
    }

    val selectionStart = minOf(
        bodyValue.selection.start,
        bodyValue.selection.end
    ).coerceIn(0, bodyText.length)

    val selectionEnd = maxOf(
        bodyValue.selection.start,
        bodyValue.selection.end
    ).coerceIn(0, bodyText.length)

    val selectedText = if (selectionEnd > selectionStart) {
        bodyText.substring(selectionStart, selectionEnd).trim()
    } else {
        ""
    }

    Surface(
        modifier = modifier
            .shadow(7.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)),
        color = if (isBackSide) palette.pageBack else palette.page
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 28.dp, top = 22.dp, end = 28.dp, bottom = 6.dp)
        ) {
            if (!page?.chapter.isNullOrBlank()) {
                Text(
                    text = page?.chapter.orEmpty(),
                    fontSize = (bodySize + 1.5f).sp,
                    lineHeight = (bodySize + 7f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = palette.text
                )

                Spacer(Modifier.size(16.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isBackSide) {
                    Text(
                        text = highlightedBody,
                        fontSize = bodySize.sp,
                        lineHeight = (bodySize + 11f).sp,
                        fontFamily = FontFamily.Serif,
                        color = palette.text.copy(alpha = 0.88f)
                    )
                } else {
                    BasicTextField(
                        value = bodyValue,
                        onValueChange = { next ->
                            bodyValue = TextFieldValue(
                                annotatedString = highlightedBody,
                                selection = next.selection
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        readOnly = true,
                        textStyle = TextStyle(
                            fontSize = bodySize.sp,
                            lineHeight = (bodySize + 11f).sp,
                            fontFamily = FontFamily.Serif,
                            color = palette.text
                        )
                    )

                    if (selectedText.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    addNote(selectedText, pageNumber)
                                    bodyValue = TextFieldValue(
                                        annotatedString = highlightedBody
                                    )
                                }
                            ) {
                                Text("Notlara Ekle")
                            }

                            Button(
                                onClick = {
                                    addHighlight(
                                        bodyText.substring(selectionStart, selectionEnd),
                                        pageNumber,
                                        selectionStart,
                                        selectionEnd
                                    )
                                    bodyValue = TextFieldValue(
                                        annotatedString = highlightedBody
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFD83D),
                                    contentColor = Color(0xFF332A00)
                                )
                            ) {
                                Text("Fosforla Çiz")
                            }
                        }
                    }
                }
            }

            if (page != null && pageNumber > 0) {
                Text(
                    text = if (totalPages > 0) {
                        "$pageNumber / $totalPages"
                    } else {
                        pageNumber.toString()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 2.dp),
                    fontSize = 12.sp,
                    color = palette.text.copy(alpha = 0.50f)
                )
            }
        }
    }
}

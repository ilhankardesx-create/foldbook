package com.foldbook.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private fun fontSizeSp(size: ReaderFontSize): Float = when (size) {
    ReaderFontSize.SMALL -> 16f
    ReaderFontSize.MEDIUM -> 18f
    ReaderFontSize.LARGE -> 21f
}

private fun pageCharLimit(size: ReaderFontSize): Int = when (size) {
    ReaderFontSize.SMALL -> 730
    ReaderFontSize.MEDIUM -> 610
    ReaderFontSize.LARGE -> 500
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

private fun EpubBook.toReaderPages(fontSize: ReaderFontSize): List<ReaderPage> {
    return chapters.flatMap { chapter ->
        paginateText(
            text = chapter.text,
            maxChars = pageCharLimit(fontSize)
        ).mapIndexed { pageIndex, body ->
            ReaderPage(
                chapter = if (pageIndex == 0) chapter.title else "",
                body = body
            )
        }
    }
}

private fun paginateText(text: String, maxChars: Int = 610): List<String> {
    val normalized = text
        .replace("\r", "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    if (normalized.isBlank()) return emptyList()

    val paragraphs = normalized
        .split(Regex("\\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val pages = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotBlank()) {
            pages += current.toString().trim()
            current.clear()
        }
    }

    for (paragraph in paragraphs) {
        val words = paragraph.split(Regex("\\s+"))

        for (word in words) {
            if (current.length + word.length + 1 > maxChars && current.isNotBlank()) {
                flush()
            }

            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }

        if (current.length > maxChars * 0.76f) {
            flush()
        } else if (current.isNotEmpty()) {
            current.append("\n\n")
        }
    }

    flush()
    return pages
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
    var readerPages by remember { mutableStateOf<List<ReaderPage>>(emptyList()) }

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
        library = listOf(book) + library.filterNot { it.uri == book.uri }

        scope.launch {
            readerLoading = true
            readerError = null
            readerKey = book.uri
            readerFormat = book.format

            if (book.format == BookFormat.PDF) {
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
                val pages = epub.toReaderPages(
                    LibraryStore.readReaderFontSize(context)
                )
                if (pages.isEmpty()) {
                    readerError = "Bu EPUB içinde okunabilir metin bulunamadı."
                } else {
                    readerPages = pages
                    readerTitle = epub.title.ifBlank { book.title }
                    reading = true
                }
            }.onFailure {
                readerError = it.message ?: "Kitap açılamadı."
            }

            readerLoading = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            LibraryStore.saveFolder(context, uri)
            scanFolder(uri)
        }
    }

    LaunchedEffect(Unit) {
        LibraryStore.savedFolder(context)?.let { scanFolder(it) }
    }

    BackHandler(enabled = reading) {
        reading = false
        readerError = null
    }

    if (reading) {
        if (readerFormat == BookFormat.PDF) {
            PdfReaderScreen(
                bookKey = readerKey,
                hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
                onBack = {
                    reading = false
                    readerError = null
                }
            )
        } else {
            ReaderScreen(
                pages = readerPages,
                bookKey = readerKey,
                hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
                onBack = {
                    reading = false
                    readerError = null
                }
            )
        }
    } else {
        LibraryScreen(
            books = library,
            isLoading = libraryLoading || readerLoading,
            error = libraryError ?: readerError,
            onChooseFolder = { folderPicker.launch(null) },
            onBookClick = ::openBook
        )
    }
}

@Composable
private fun LibraryScreen(
    books: List<LibraryBook>,
    isLoading: Boolean,
    error: String?,
    onChooseFolder: () -> Unit,
    onBookClick: (LibraryBook) -> Unit
) {
    val context = LocalContext.current
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var selectedTheme by remember {
        mutableStateOf(LibraryStore.readReaderTheme(context))
    }
    var selectedFontSize by remember {
        mutableStateOf(LibraryStore.readReaderFontSize(context))
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
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "FoldBook",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(13.dp))
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FoldBook",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = if (books.isEmpty()) {
                            "Kütüphanen"
                        } else {
                            "${books.size} kitap rafında"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }

                Text(
                    text = "v0.8.2",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onChooseFolder,
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Taranıyor…" else "Klasör Seç")
                }

                Button(
                    onClick = { settingsVisible = !settingsVisible }
                ) {
                    Text("Okuma Ayarları")
                }
            }

            AnimatedVisibility(visible = settingsVisible) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                    }
                }
            }

            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            if (books.isEmpty() && !isLoading) {
                EmptyLibrary(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    onChooseFolder = onChooseFolder
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 145.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 10.dp,
                        bottom = 32.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(
                        items = books,
                        key = { it.uri }
                    ) { book ->
                        ShelfBook(
                            book = book,
                            onClick = { onBookClick(book) }
                        )
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
                Text("Kitap Klasörü Seç")
            }
        }
    }
}

@Composable
private fun ShelfBook(
    book: LibraryBook,
    onClick: () -> Unit
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .shadow(9.dp, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
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
                            .padding(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(5.dp)
                                .align(Alignment.CenterStart)
                                .background(Color.Black.copy(alpha = 0.13f))
                        )

                        Text(
                            text = book.title,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 8.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            lineHeight = 21.sp,
                            color = Color(0xFFFFF8EA)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = book.format.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .shadow(5.dp, RoundedCornerShape(3.dp))
                .background(
                    Color(0xFF806044),
                    RoundedCornerShape(3.dp)
                )
        )
    }
}

@Composable
private fun ReaderScreen(
    pages: List<ReaderPage>,
    bookKey: String,
    hasSeparatingVerticalHinge: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val savedPage = remember(bookKey, pages.size) {
        LibraryStore.readProgress(
            context = context,
            bookUri = bookKey,
            lastPageIndex = pages.lastIndex
        )
    }

    var controlsVisible by rememberSaveable(bookKey) { mutableStateOf(false) }
    val theme = remember(bookKey) {
        LibraryStore.readReaderTheme(context)
    }
    val fontSize = remember(bookKey) {
        LibraryStore.readReaderFontSize(context)
    }
    val palette = readerPalette(theme)

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
                    .pointerInput(bookKey) {
                        detectTapGestures {
                            controlsVisible = !controlsVisible
                        }
                    }
            ) {
                val twoPage = hasSeparatingVerticalHinge || maxWidth >= 700.dp

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
                    },
                    twoPage = twoPage,
                    modifier = Modifier.fillMaxSize()
                )

                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                        .zIndex(20f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Black.copy(alpha = 0.72f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = onBack) {
                                Text("Rafa Dön")
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
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable(bookKey) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
    }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var turnDirection by remember { mutableIntStateOf(0) }
    var pageWidthPx by remember { mutableFloatStateOf(1f) }
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

                pageIndex = newIndex
                onPageChanged(newIndex)
            }

            dragPx = 0f
            dragProgress = 0f
            turnDirection = 0
            settleAnimation.snapTo(0f)
            settling = false
        }
    }

    LaunchedEffect(bookKey, initialPageIndex) {
        pageIndex = initialPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        dragPx = 0f
        dragProgress = 0f
        turnDirection = 0
    }

    LaunchedEffect(twoPage) {
        if (twoPage && pageIndex % 2 != 0) {
            pageIndex = (pageIndex - 1).coerceAtLeast(0)
        }

        dragPx = 0f
        dragProgress = 0f
        turnDirection = 0
    }

    val gestureModifier = Modifier
        .onSizeChanged {
            pageWidthPx = if (twoPage) it.width / 2f else it.width.toFloat()
        }
        .pointerInput(pageIndex, twoPage, pageWidthPx, settling) {
            if (settling) return@pointerInput

            detectHorizontalDragGestures(
                onDragStart = {
                    dragPx = 0f
                    dragProgress = 0f
                    turnDirection = 0
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()

                    val proposed = (dragPx + dragAmount)
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

    Box(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        if (twoPage) {
            TwoPageSpread(
                pages = pages,
                pageIndex = pageIndex,
                turnDirection = turnDirection,
                progress = progress
            )
        } else {
            SinglePageSpread(
                pages = pages,
                pageIndex = pageIndex,
                turnDirection = turnDirection,
                progress = progress
            )
        }
    }
}

@Composable
private fun TwoPageSpread(
    pages: List<ReaderPage>,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float
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
    progress: Float
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
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val showingBack = p > 0.5f
    val rotation = if (direction == 1) -180f * p else 180f * p
    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }

    Box(
        modifier = modifier.graphicsLayer {
            transformOrigin = origin
            rotationY = rotation
            cameraDistance = 30f
            shadowElevation = 18f * (1f - abs(0.5f - p) * 2f)
            scaleY = 1f - (0.012f * (1f - abs(0.5f - p) * 2f))
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
                    isBackSide = true,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BookPage(
                    page = frontPage,
                    pageNumber = frontNumber,
                    isBackSide = showingBack,
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
    val bodySize = fontSizeSp(readerFontSize)

    Surface(
        modifier = modifier
            .shadow(7.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)),
        color = if (isBackSide) palette.pageBack else palette.page
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 22.dp)
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

            Text(
                text = page?.body.orEmpty(),
                fontSize = bodySize.sp,
                lineHeight = (bodySize + 11f).sp,
                fontFamily = FontFamily.Serif,
                color = palette.text.copy(
                    alpha = if (isBackSide) 0.88f else 1f
                )
            )

            Spacer(Modifier.weight(1f))

            if (page != null && pageNumber > 0) {
                Text(
                    text = pageNumber.toString(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    fontSize = 12.sp,
                    color = palette.text.copy(alpha = 0.50f)
                )
            }
        }
    }
}

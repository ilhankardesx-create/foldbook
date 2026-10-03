package com.foldbook.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

private fun EpubBook.toReaderPages(): List<ReaderPage> {
    return chapters.flatMap { chapter ->
        paginateText(chapter.text).mapIndexed { pageIndex, body ->
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
        scope.launch {
            readerLoading = true
            readerError = null

            runCatching {
                withContext(Dispatchers.IO) {
                    EpubLoader.load(context, Uri.parse(book.uri))
                }
            }.onSuccess { epub ->
                val pages = epub.toReaderPages()
                if (pages.isEmpty()) {
                    readerError = "Bu EPUB içinde okunabilir metin bulunamadı."
                } else {
                    readerPages = pages
                    readerTitle = epub.title.ifBlank { book.title }
                    readerKey = book.uri
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
        ReaderScreen(
            pages = readerPages,
            bookKey = readerKey,
            hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
            onBack = {
                reading = false
                readerError = null
            }
        )
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
                    painter = painterResource(R.mipmap.ic_launcher),
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

                Button(
                    onClick = onChooseFolder,
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Taranıyor…" else "Klasör Seç")
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    text = "v0.5",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
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
                text = "EPUB kitaplarının bulunduğu klasörü bir kez seç. FoldBook klasörü hatırlayıp kitaplarını burada rafa dizecek.",
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

                Text(
                    text = "EPUB",
                    modifier = Modifier.align(Alignment.BottomCenter),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.68f)
                )
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp)
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
    Surface(
        modifier = modifier
            .shadow(7.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)),
        color = if (isBackSide) Color(0xFFFFF7E8) else Color(0xFFFFFCF5)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            if (!page?.chapter.isNullOrBlank()) {
                Text(
                    text = page?.chapter.orEmpty(),
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.size(16.dp))
            }

            Text(
                text = page?.body.orEmpty(),
                fontSize = 18.sp,
                lineHeight = 29.sp,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (isBackSide) 0.88f else 1f
                )
            )

            Spacer(Modifier.weight(1f))

            if (page != null && pageNumber > 0) {
                Text(
                    text = pageNumber.toString(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                )
            }
        }
    }
}

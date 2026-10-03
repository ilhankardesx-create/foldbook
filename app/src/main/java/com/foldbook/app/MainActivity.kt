package com.foldbook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
                FoldBookReader(
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

private data class DemoPage(
    val chapter: String,
    val body: String
)

private val samplePages = listOf(
    DemoPage(
        "Bölüm 1 — Başlangıç",
        "FoldBook, katlanabilir telefon açıkken gerçek bir kitabın iki karşılıklı sayfası gibi davranmak için tasarlandı. Parmağını sağ sayfada sola doğru sürükle."
    ),
    DemoPage(
        "İki Sayfalı Okuma",
        "Ekran genişlediğinde içerik otomatik olarak iki sayfaya ayrılır. Dikey ve ayırıcı bir menteşe algılanırsa kitap düzeni özellikle korunur."
    ),
    DemoPage(
        "Yeni Sayfa Motoru",
        "Sayfayı çevirirken hareket artık parmağını takip ediyor. Bıraktığında sayfa eşik noktasına göre yumuşakça tamamlanıyor veya eski yerine dönüyor."
    ),
    DemoPage(
        "Kağıdın Arka Yüzü",
        "Sayfa yarıyı geçince arka yüzü görünür ve sıradaki yaprağın içeriğine dönüşür. Orta çizgideki gölge de hareketle birlikte değişir."
    ),
    DemoPage(
        "Kapalı Telefon",
        "Telefon kapalı veya dar ekrandayken tek sayfa görünür. Aynı kaydırma hareketiyle bir sonraki ya da önceki sayfaya geçilir."
    ),
    DemoPage(
        "Sıradaki Adım",
        "Bu temel hazır olduğunda EPUB içe aktarma, kütüphane görünümü, yazı tipi ayarları ve gerçek kitap dosyalarını okuma özellikleri eklenecek."
    ),
    DemoPage(
        "FoldBook",
        "Amaç basit: Fold cihaz açıldığında ekrana bakmak yerine elinde gerçekten açık bir kitap varmış hissini vermek."
    ),
    DemoPage(
        "Prototip 0.2",
        "Bu sürümde ileri ve geri sayfa hareketi, çift sayfalı düzen ve daha güçlü derinlik hissi birlikte çalışıyor."
    )
)

private fun EpubBook.toReaderPages(): List<DemoPage> {
    return chapters.flatMap { chapter ->
        paginateText(chapter.text).mapIndexed { pageIndex, body ->
            DemoPage(
                chapter = if (pageIndex == 0) chapter.title else "",
                body = body
            )
        }
    }
}

private fun paginateText(text: String, maxChars: Int = 520): List<String> {
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

        if (current.length > maxChars * 0.72f) {
            flush()
        } else if (current.isNotEmpty()) {
            current.append("\n\n")
        }
    }

    flush()
    return pages
}

@Composable
private fun FoldBookReader(hasSeparatingVerticalHinge: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf(samplePages) }
    var bookTitle by remember { mutableStateOf("FoldBook") }
    var bookKey by remember { mutableStateOf("sample") }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val epubPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                loadError = null

                runCatching {
                    withContext(Dispatchers.IO) {
                        EpubLoader.load(context, uri)
                    }
                }.onSuccess { book ->
                    val importedPages = book.toReaderPages()
                    if (importedPages.isEmpty()) {
                        loadError = "Bu EPUB içinde okunabilir metin bulunamadı."
                    } else {
                        pages = importedPages
                        bookTitle = book.title.ifBlank { "EPUB Kitap" }
                        bookKey = uri.toString()
                    }
                }.onFailure {
                    loadError = it.message ?: "EPUB açılamadı."
                }

                isLoading = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 18.dp)
        ) {
            ReaderHeader(
                title = bookTitle,
                isLoading = isLoading,
                onOpenBook = {
                    epubPicker.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/octet-stream"
                        )
                    )
                }
            )

            loadError?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                val twoPage = hasSeparatingVerticalHinge || maxWidth >= 700.dp

                BookSpread(
                    pages = pages,
                    bookKey = bookKey,
                    twoPage = twoPage,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ReaderHeader(
    title: String,
    isLoading: Boolean,
    onOpenBook: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Button(
            onClick = onOpenBook,
            enabled = !isLoading
        ) {
            Text(if (isLoading) "Açılıyor…" else "EPUB Aç")
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = "v0.3",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BookSpread(
    pages: List<DemoPage>,
    bookKey: String,
    twoPage: Boolean,
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
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
        val shouldComplete = !cancelOnly && direction != 0 && canTurn(direction) && start >= 0.20f

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
                pageIndex += if (direction == 1) step else -step
            }

            dragPx = 0f
            dragProgress = 0f
            turnDirection = 0
            settleAnimation.snapTo(0f)
            settling = false
        }
    }

    LaunchedEffect(bookKey) {
        pageIndex = 0
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
                        dragPx = proposed.coerceIn(-pageWidthPx * 0.06f, pageWidthPx * 0.06f)
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
    pages: List<DemoPage>,
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
                .padding(vertical = 20.dp)
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
                .padding(vertical = 20.dp)
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
    pages: List<DemoPage>,
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
            .padding(vertical = 20.dp)
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
    frontPage: DemoPage?,
    frontNumber: Int,
    backPage: DemoPage?,
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
            .width(28.dp)
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
    val shadowStrength = 0.08f + (0.10f * (1f - abs(0.5f - progress) * 2f))

    Box(
        modifier = Modifier
            .width(18.dp)
            .fillMaxHeight()
            .padding(vertical = 20.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.03f),
                        Color.Black.copy(alpha = shadowStrength),
                        Color.Black.copy(alpha = 0.03f)
                    )
                ),
                RoundedCornerShape(50)
            )
    )
}

@Composable
private fun BookPage(
    page: DemoPage?,
    pageNumber: Int,
    isBackSide: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)),
        color = if (isBackSide) Color(0xFFFFF7E8) else Color(0xFFFFFCF5)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 30.dp)
        ) {
            Text(
                text = page?.chapter.orEmpty(),
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.size(22.dp))

            Text(
                text = page?.body.orEmpty(),
                fontSize = 18.sp,
                lineHeight = 30.sp,
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
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

package com.foldbook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

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
            background = Color(0xFFE9E1D2),
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

private val demoPages = listOf(
    DemoPage(
        "Bölüm 1 — Başlangıç",
        "FoldBook, katlanabilir telefon açıkken gerçek bir kitabın iki karşılıklı sayfası gibi davranmak için tasarlandı. Parmağını sağ sayfada sola doğru sürükle."
    ),
    DemoPage(
        "İki Sayfalı Okuma",
        "Ekran genişlediğinde içerik otomatik olarak iki sayfaya ayrılır. Dikey ve ayırıcı bir menteşe algılanırsa kitap düzeni özellikle korunur."
    ),
    DemoPage(
        "Sayfa Hissi",
        "Bu ilk prototipte sayfa, kitabın orta çizgisinden dönüyormuş gibi üç boyutlu hareket eder. Sonraki adım gerçek kıvrılma, gölge ve sayfanın arka yüzünü eklemek."
    ),
    DemoPage(
        "Kapalı Telefon",
        "Telefon kapalı veya dar ekrandayken tek sayfa görünür. Aynı kaydırma hareketiyle bir sonraki ya da önceki sayfaya geçilir."
    ),
    DemoPage(
        "Sıradaki Adım",
        "Okuma motoru oturduktan sonra EPUB içe aktarma, kütüphane görünümü, yazı tipi ayarları ve PDF desteği eklenecek."
    ),
    DemoPage(
        "FoldBook",
        "Amaç basit: Fold cihaz açıldığında ekrana bakmak yerine elinde gerçekten açık bir kitap varmış hissini vermek."
    )
)

@Composable
private fun FoldBookReader(hasSeparatingVerticalHinge: Boolean) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 18.dp)
        ) {
            ReaderHeader()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                val twoPage = hasSeparatingVerticalHinge || maxWidth >= 700.dp

                BookSpread(
                    twoPage = twoPage,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ReaderHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FoldBook",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "PROTOTİP",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BookSpread(
    twoPage: Boolean,
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var pageWidthPx by remember { mutableFloatStateOf(1f) }

    val step = if (twoPage) 2 else 1
    val forwardProgress = (-dragPx / pageWidthPx).coerceIn(0f, 1f)
    val backwardProgress = (dragPx / pageWidthPx).coerceIn(0f, 1f)

    fun finishDrag() {
        when {
            forwardProgress > 0.24f && pageIndex + step <= demoPages.lastIndex -> pageIndex += step
            backwardProgress > 0.24f && pageIndex - step >= 0 -> pageIndex -= step
        }
        dragPx = 0f
    }

    val gestureModifier = Modifier
        .onSizeChanged {
            pageWidthPx = if (twoPage) it.width / 2f else it.width.toFloat()
        }
        .pointerInput(pageIndex, twoPage, pageWidthPx) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    dragPx = (dragPx + dragAmount)
                        .coerceIn(-pageWidthPx, pageWidthPx)
                },
                onDragEnd = { finishDrag() },
                onDragCancel = { dragPx = 0f }
            )
        }

    Box(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        if (twoPage) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BookPage(
                    page = demoPages.getOrNull(pageIndex),
                    pageNumber = pageIndex + 1,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(vertical = 20.dp)
                )

                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(alpha = 0.07f),
                            RoundedCornerShape(50)
                        )
                )

                BookPage(
                    page = demoPages.getOrNull(pageIndex + 1),
                    pageNumber = pageIndex + 2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(vertical = 20.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            rotationY = -155f * forwardProgress
                            cameraDistance = 28f
                            shadowElevation = 12f * forwardProgress
                        }
                )
            }
        } else {
            BookPage(
                page = demoPages.getOrNull(pageIndex),
                pageNumber = pageIndex + 1,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 20.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = -155f * forwardProgress
                        cameraDistance = 28f
                        shadowElevation = 12f * forwardProgress
                    }
            )
        }

        if (dragPx > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(24.dp)
                    .height(120.dp)
                    .background(
                        Color.Black.copy(alpha = 0.04f * backwardProgress),
                        RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                    )
            )
        }
    }
}

@Composable
private fun BookPage(
    page: DemoPage?,
    pageNumber: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)),
        color = Color(0xFFFFFCF5)
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
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = pageNumber.toString(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

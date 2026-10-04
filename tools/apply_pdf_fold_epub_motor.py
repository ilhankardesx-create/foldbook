from pathlib import Path
import re

pdf = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
p = pdf.read_text()

p = p.replace(
    'val spineWidthPx = with(densityInfo) { 8.dp.toPx() }',
    'val spineWidthPx = with(densityInfo) { 10.dp.toPx() }',
    1
)

new_two_page = r'''@Composable
private fun PdfTwoPageSpread(
    document: PdfBookDocument,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    theme: ReaderThemeOption,
    renderWidthPx: Int
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
            val leftIndex = if (isBackward) pageIndex - 2 else pageIndex

            PdfPage(
                document = document,
                index = leftIndex,
                theme = theme,
                renderWidthPx = renderWidthPx,
                modifier = Modifier.fillMaxSize()
            )

            if (isBackward) {
                PdfTurningPage(
                    document = document,
                    frontIndex = pageIndex,
                    backIndex = pageIndex - 1,
                    progress = progress,
                    direction = -1,
                    theme = theme,
                    renderWidthPx = renderWidthPx,
                    crossSpine = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )
            }
        }

        PdfBookSpine(progress = progress)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .zIndex(if (isForward) 3f else 0f)
        ) {
            val rightIndex = if (isForward) pageIndex + 3 else pageIndex + 1

            PdfPage(
                document = document,
                index = rightIndex,
                theme = theme,
                renderWidthPx = renderWidthPx,
                modifier = Modifier.fillMaxSize()
            )

            if (isForward) {
                PdfTurningPage(
                    document = document,
                    frontIndex = pageIndex + 1,
                    backIndex = pageIndex + 2,
                    progress = progress,
                    direction = 1,
                    theme = theme,
                    renderWidthPx = renderWidthPx,
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
private fun PdfBookSpine(progress: Float) {
    val strength = 0.07f + (0.10f * (1f - abs(0.5f - progress) * 2f))
    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .background(
                Color.Black.copy(alpha = strength.coerceIn(0.05f, 0.17f)),
                RoundedCornerShape(50)
            )
    )
}

@Composable
private fun PdfSinglePageSpread'''

pattern_two = re.compile(
    r'@Composable\nprivate fun PdfTwoPageSpread\(.*?\n\}\n\n@Composable\nprivate fun PdfSinglePageSpread',
    re.S
)
p, n = pattern_two.subn(new_two_page, p, count=1)
if n != 1:
    raise SystemExit(f'PdfTwoPageSpread replacement count={n}')

new_turning = r'''@Composable
private fun PdfTurningPage(
    document: PdfBookDocument,
    frontIndex: Int?,
    backIndex: Int?,
    progress: Float,
    direction: Int,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    cameraDistanceValue: Float = 30f,
    crossSpine: Boolean = false,
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
    val spineShiftPx = if (crossSpine) {
        with(LocalDensity.current) { 10.dp.toPx() } * p
    } else {
        0f
    }

    // EPUB motorunda olduğu gibi, dönen yaprağın iki yüzünü dönüş başlamadan
    // sabitliyoruz. Animasyon sırasında PdfPage yeniden render/recompose edilmez.
    val frontBitmap = remember(document, frontIndex, renderWidthPx, theme) {
        frontIndex
            ?.takeIf { it in 0 until document.pageCount }
            ?.let { document.cachedPage(it, renderWidthPx, theme) }
    }
    val backBitmap = remember(document, backIndex, renderWidthPx, theme) {
        backIndex
            ?.takeIf { it in 0 until document.pageCount }
            ?.let { document.cachedPage(it, renderWidthPx, theme) }
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
            cameraDistance = cameraDistanceValue
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
            PdfFrozenPage(
                bitmap = if (showingBack) backBitmap else frontBitmap,
                index = if (showingBack) backIndex else frontIndex,
                theme = theme,
                modifier = Modifier.fillMaxSize()
            )

            val edgeAlpha = 0.18f * (1f - abs(0.5f - p) * 2f)
            Box(
                modifier = Modifier
                    .align(
                        if (direction == 1) Alignment.CenterStart else Alignment.CenterEnd
                    )
                    .width(26.dp)
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = edgeAlpha))
            )
        }
    }
}

@Composable
private fun PdfFrozenPage(
    bitmap: Bitmap?,
    index: Int?,
    theme: ReaderThemeOption,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(7.dp))
            .clip(RoundedCornerShape(7.dp)),
        color = when (theme) {
            ReaderThemeOption.LIGHT -> Color(0xFFF5F2EA)
            ReaderThemeOption.SEPIA -> Color(0xFFE7D3B2)
            ReaderThemeOption.DARK -> Color(0xFF191919)
        }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = index?.let { page -> "PDF sayfa " + (page + 1) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (index != null) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp),
                    color = if (theme == ReaderThemeOption.DARK) {
                        Color.White.copy(alpha = 0.52f)
                    } else {
                        Color.Black.copy(alpha = 0.42f)
                    },
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun PdfPage'''

pattern_turn = re.compile(
    r'@Composable\nprivate fun PdfTurningPage\(.*?\n\}\n\n@Composable\nprivate fun PdfPage',
    re.S
)
p, n = pattern_turn.subn(new_turning, p, count=1)
if n != 1:
    raise SystemExit(f'PdfTurningPage replacement count={n}')

pdf.write_text(p)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 35' not in g or 'versionName = "0.9.24"' not in g:
    raise SystemExit('version anchor missing')
g = g.replace('versionCode = 35', 'versionCode = 36', 1)
g = g.replace('versionName = "0.9.24"', 'versionName = "0.9.25"', 1)
gradle.write_text(g)

main = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
m = main.read_text()
if 'text = "v0.9.24"' in m:
    m = m.replace('text = "v0.9.24"', 'text = "v0.9.25"', 1)
elif 'text = "v0.9.23"' in m:
    m = m.replace('text = "v0.9.23"', 'text = "v0.9.25"', 1)
else:
    raise SystemExit('settings version anchor missing')
main.write_text(m)

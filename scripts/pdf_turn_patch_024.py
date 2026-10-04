from pathlib import Path

pdf = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
p = pdf.read_text()

old = 'private val pageCache = object : LruCache<String, Bitmap>(64 * 1024) {'
new = 'private val pageCache = object : LruCache<String, Bitmap>(96 * 1024) {'
if old not in p:
    raise SystemExit('cache anchor missing')
p = p.replace(old, new, 1)

if 'import androidx.compose.ui.platform.LocalDensity\n' not in p:
    old = 'import androidx.compose.ui.platform.LocalContext\n'
    new = 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalDensity\n'
    if old not in p:
        raise SystemExit('LocalDensity import anchor missing')
    p = p.replace(old, new, 1)

old = '    val renderWidthPx = (pageWidthPx * 1.45f).toInt().coerceIn(900, 2400)\n'
new = '''    val densityInfo = LocalDensity.current
    val spineWidthPx = with(densityInfo) { 8.dp.toPx() }
    val renderScale = if (twoPage) 1.45f else 1.15f
    val renderWidthPx = (pageWidthPx * renderScale).toInt().coerceIn(820, 2200)
'''
if old not in p:
    raise SystemExit('render width anchor missing')
p = p.replace(old, new, 1)

old = '((it.width - 10f) / 2f).coerceAtLeast(1f)'
new = '((it.width - spineWidthPx) / 2f).coerceAtLeast(1f)'
if old not in p:
    raise SystemExit('page width anchor missing')
p = p.replace(old, new, 1)

old = '''    fun canTurn(direction: Int): Boolean = when (direction) {
        1 -> pageIndex + step < document.pageCount
        -1 -> pageIndex - step >= 0
        else -> false
    }
'''
new = '''    fun canTurn(direction: Int): Boolean = when (direction) {
        1 -> pageIndex + step < document.pageCount
        -1 -> pageIndex - step >= 0
        else -> false
    }

    var warmingDirection by remember { mutableIntStateOf(0) }

    fun turnAssetIndices(direction: Int): List<Int> {
        return if (twoPage) {
            when (direction) {
                1 -> listOf(pageIndex, pageIndex + 1, pageIndex + 2, pageIndex + 3)
                -1 -> listOf(pageIndex - 2, pageIndex - 1, pageIndex, pageIndex + 1)
                else -> emptyList()
            }
        } else {
            when (direction) {
                1 -> listOf(pageIndex, pageIndex + 1)
                -1 -> listOf(pageIndex - 1, pageIndex)
                else -> emptyList()
            }
        }.filter { it in 0 until document.pageCount }
    }

    fun turnAssetsReady(direction: Int): Boolean {
        if (!canTurn(direction)) return false
        return turnAssetIndices(direction).all {
            document.cachedPage(it, renderWidthPx, theme) != null
        }
    }

    fun warmTurnAssets(direction: Int) {
        if (!canTurn(direction) || warmingDirection == direction) return
        warmingDirection = direction
        scope.launch {
            withContext(Dispatchers.IO) {
                turnAssetIndices(direction).forEach {
                    document.renderPage(it, renderWidthPx, theme)
                }
            }
            if (warmingDirection == direction) warmingDirection = 0
        }
    }
'''
if old not in p:
    raise SystemExit('canTurn anchor missing')
p = p.replace(old, new, 1)

old = '''            val candidates = if (twoPage) {
                listOf(
                    pageIndex - 2,
                    pageIndex - 1,
                    pageIndex,
                    pageIndex + 1,
                    pageIndex + 2,
                    pageIndex + 3
                )
            } else {
                listOf(
                    pageIndex,
                    pageIndex + 1,
                    pageIndex - 1,
                    pageIndex + 2,
                    pageIndex - 2
                )
            }
'''
new = '''            val candidates = if (twoPage) {
                listOf(
                    pageIndex + 2,
                    pageIndex + 3,
                    pageIndex - 1,
                    pageIndex - 2,
                    pageIndex,
                    pageIndex + 1
                )
            } else {
                listOf(
                    pageIndex + 1,
                    pageIndex - 1,
                    pageIndex,
                    pageIndex + 2,
                    pageIndex - 2
                )
            }
'''
if old not in p:
    raise SystemExit('prefetch anchor missing')
p = p.replace(old, new, 1)

old = '''                    if (direction == 0 || canTurn(direction)) {
                        dragPx = proposed
                        turnDirection = direction
                        dragProgress =
                            (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                    } else {
                        dragPx = proposed.coerceIn(
                            -pageWidthPx * 0.055f,
                            pageWidthPx * 0.055f
                        )
                        dragProgress = 0f
                        turnDirection = 0
                    }
'''
new = '''                    when {
                        direction == 0 -> {
                            dragPx = 0f
                            dragProgress = 0f
                            turnDirection = 0
                        }

                        canTurn(direction) && turnAssetsReady(direction) -> {
                            dragPx = proposed
                            turnDirection = direction
                            dragProgress =
                                (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                        }

                        canTurn(direction) -> {
                            warmTurnAssets(direction)
                            dragPx = 0f
                            dragProgress = 0f
                            turnDirection = 0
                        }

                        else -> {
                            dragPx = proposed.coerceIn(
                                -pageWidthPx * 0.055f,
                                pageWidthPx * 0.055f
                            )
                            dragProgress = 0f
                            turnDirection = 0
                        }
                    }
'''
if old not in p:
    raise SystemExit('drag anchor missing')
p = p.replace(old, new, 1)

start = p.find('@Composable\nprivate fun PdfSinglePageSpread(')
end = p.find('@Composable\nprivate fun PdfTurningPage(', start)
if start < 0 or end < 0:
    raise SystemExit('single page block missing')

single = '''@Composable
private fun PdfSinglePageSpread(
    document: PdfBookDocument,
    pageIndex: Int,
    turnDirection: Int,
    progress: Float,
    theme: ReaderThemeOption,
    renderWidthPx: Int
) {
    val targetIndex = when (turnDirection) {
        1 -> pageIndex + 1
        -1 -> pageIndex - 1
        else -> pageIndex
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 3.dp)
    ) {
        PdfPage(
            document = document,
            index = targetIndex,
            theme = theme,
            renderWidthPx = renderWidthPx,
            modifier = Modifier.fillMaxSize()
        )

        if (turnDirection != 0) {
            PdfSingleTurningPage(
                document = document,
                frontIndex = pageIndex,
                progress = progress,
                direction = turnDirection,
                theme = theme,
                renderWidthPx = renderWidthPx,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            )
        }
    }
}

@Composable
private fun PdfSingleTurningPage(
    document: PdfBookDocument,
    frontIndex: Int,
    progress: Float,
    direction: Int,
    theme: ReaderThemeOption,
    renderWidthPx: Int,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val rotation = if (direction == 1) -88f * p else 88f * p
    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }

    Box(
        modifier = modifier.graphicsLayer {
            transformOrigin = origin
            rotationY = rotation
            cameraDistance = 48f
            shadowElevation = 18f * p
            scaleY = 1f - (0.008f * p)
        }
    ) {
        PdfPage(
            document = document,
            index = frontIndex,
            theme = theme,
            renderWidthPx = renderWidthPx,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(
                    if (direction == 1) Alignment.CenterEnd else Alignment.CenterStart
                )
                .width(18.dp)
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f * p))
        )
    }
}

'''
p = p[:start] + single + p[end:]

sig_old = '''    theme: ReaderThemeOption,
    renderWidthPx: Int,
    cameraDistanceValue: Float = 30f,
    modifier: Modifier = Modifier
) {'''
sig_new = '''    theme: ReaderThemeOption,
    renderWidthPx: Int,
    cameraDistanceValue: Float = 30f,
    crossSpine: Boolean = false,
    modifier: Modifier = Modifier
) {'''
if sig_old not in p:
    raise SystemExit('turning signature anchor missing')
p = p.replace(sig_old, sig_new, 1)

turn_pos = p.find('@Composable\nprivate fun PdfTurningPage(')
origin_old = '''    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }

    Box(
        modifier = modifier.graphicsLayer {'''
origin_new = '''    val origin = if (direction == 1) {
        TransformOrigin(0f, 0.5f)
    } else {
        TransformOrigin(1f, 0.5f)
    }
    val spineShiftPx = if (crossSpine) {
        with(LocalDensity.current) { 8.dp.toPx() } * p
    } else {
        0f
    }

    Box(
        modifier = modifier.graphicsLayer {'''
pos = p.find(origin_old, turn_pos)
if pos < 0:
    raise SystemExit('turning origin anchor missing')
p = p[:pos] + p[pos:].replace(origin_old, origin_new, 1)

layer_old = '''            cameraDistance = cameraDistanceValue
            shadowElevation = 20f * (1f - abs(0.5f - p) * 2f)
'''
layer_new = '''            cameraDistance = cameraDistanceValue
            translationX = when {
                !crossSpine -> 0f
                direction == 1 -> -spineShiftPx
                else -> spineShiftPx
            }
            shadowElevation = 20f * (1f - abs(0.5f - p) * 2f)
'''
if layer_old not in p:
    raise SystemExit('turning layer anchor missing')
p = p.replace(layer_old, layer_new, 1)

backward_old = '''                    theme = theme,
                    renderWidthPx = renderWidthPx,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )'''
backward_new = '''                    theme = theme,
                    renderWidthPx = renderWidthPx,
                    crossSpine = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                )'''
# This exact snippet appears twice for the two PdfTurningPage calls and nowhere in PdfPage.
if p.count(backward_old) < 2:
    raise SystemExit(f'two-page turn call anchors missing: {p.count(backward_old)}')
p = p.replace(backward_old, backward_new, 2)

pdf.write_text(p)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 34' not in g or 'versionName = "0.9.23"' not in g:
    raise SystemExit('version anchor missing')
g = g.replace('versionCode = 34', 'versionCode = 35', 1)
g = g.replace('versionName = "0.9.23"', 'versionName = "0.9.24"', 1)
gradle.write_text(g)

main = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
m = main.read_text()
if 'text = "v0.9.23"' not in m:
    raise SystemExit('settings version anchor missing')
m = m.replace('text = "v0.9.23"', 'text = "v0.9.24"', 1)
main.write_text(m)

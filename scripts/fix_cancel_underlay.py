from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)

# --- PageCurl: only blend during an actual cancellation ---
p = Path('app/src/main/java/com/foldbook/app/PageCurl.kt')
s = p.read_text()
s = replace_once(
    s,
    '    crossSpine: Boolean,\n    modifier: Modifier = Modifier,',
    '    crossSpine: Boolean,\n    isCancelling: Boolean = false,\n    modifier: Modifier = Modifier,',
    'PageCurl parameter'
)
s = replace_once(
    s,
    '''            val cancelBlendEnd = 0.10f\n            val meshOpacity = smootherStep((p / cancelBlendEnd).coerceIn(0f, 1f))\n            if (p < cancelBlendEnd) {\n                drawLayer(frontLayer)\n            }\n''',
    '''            val cancelBlendEnd = 0.10f\n            val meshOpacity = if (isCancelling) {\n                smootherStep((p / cancelBlendEnd).coerceIn(0f, 1f))\n            } else {\n                1f\n            }\n            if (isCancelling && p < cancelBlendEnd) {\n                drawLayer(frontLayer)\n            }\n''',
    'PageCurl cancel-only blend'
)
p.write_text(s)

# --- EPUB reader ---
p = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
s = p.read_text()
s = replace_once(
    s,
    '    var settling by remember { mutableStateOf(false) }\n    // Lock the gesture',
    '    var settling by remember { mutableStateOf(false) }\n    var cancelling by remember { mutableStateOf(false) }\n    // Lock the gesture',
    'EPUB cancelling state'
)
s = replace_once(
    s,
    '            settling = true\n            settleAnimation.snapTo(start)',
    '            settling = true\n            cancelling = !shouldComplete\n            settleAnimation.snapTo(start)',
    'EPUB settle cancelling start'
)
# reset cancelling in both completion and cancel branches
s = s.replace('                    settling = false\n                }\n                settleAnimation.snapTo(0f)', '                    settling = false\n                    cancelling = false\n                }\n                settleAnimation.snapTo(0f)', 1)
s = s.replace('                    settling = false\n                }\n\n                settleAnimation.snapTo(0f)', '                    settling = false\n                    cancelling = false\n                }\n\n                settleAnimation.snapTo(0f)', 1)

s = replace_once(
    s,
    '                    curlY = dragYFraction\n                )',
    '                    curlY = dragYFraction,\n                    cancelling = cancelling\n                )',
    'EPUB two page call'
)
s = replace_once(
    s,
    '                    curlY = dragYFraction\n                )',
    '                    curlY = dragYFraction,\n                    cancelling = cancelling\n                )',
    'EPUB single page call'
)
s = replace_once(
    s,
    '    progress: Float,\n    curlY: Float\n) {\n    val isForward = turnDirection == 1',
    '    progress: Float,\n    curlY: Float,\n    cancelling: Boolean\n) {\n    val isForward = turnDirection == 1',
    'EPUB two page signature'
)
s = replace_once(
    s,
    '    val isBackward = turnDirection == -1\n\n    Row(',
    '    val isBackward = turnDirection == -1\n    val revealCurrentUnderlay = cancelling && progress < 0.10f\n\n    Row(',
    'EPUB two page reveal flag'
)
s = replace_once(
    s,
    '''            val leftPage = if (isBackward) {\n                pages.getOrNull(pageIndex - 2)\n            } else {\n                pages.getOrNull(pageIndex)\n            }\n            val leftNumber = if (isBackward) pageIndex - 1 else pageIndex + 1\n''',
    '''            val leftPage = if (isBackward) {\n                if (revealCurrentUnderlay) pages.getOrNull(pageIndex)\n                else pages.getOrNull(pageIndex - 2)\n            } else {\n                pages.getOrNull(pageIndex)\n            }\n            val leftNumber = if (isBackward) {\n                if (revealCurrentUnderlay) pageIndex + 1 else pageIndex - 1\n            } else pageIndex + 1\n''',
    'EPUB left underlay'
)
s = replace_once(
    s,
    '                    crossSpine = true,\n                    modifier = Modifier',
    '                    crossSpine = true,\n                    cancelling = cancelling,\n                    modifier = Modifier',
    'EPUB backward turning cancel param'
)
s = replace_once(
    s,
    '''            val rightPage = if (isForward) {\n                pages.getOrNull(pageIndex + 3)\n            } else {\n                pages.getOrNull(pageIndex + 1)\n            }\n            val rightNumber = if (isForward) pageIndex + 4 else pageIndex + 2\n''',
    '''            val rightPage = if (isForward) {\n                if (revealCurrentUnderlay) pages.getOrNull(pageIndex + 1)\n                else pages.getOrNull(pageIndex + 3)\n            } else {\n                pages.getOrNull(pageIndex + 1)\n            }\n            val rightNumber = if (isForward) {\n                if (revealCurrentUnderlay) pageIndex + 2 else pageIndex + 4\n            } else pageIndex + 2\n''',
    'EPUB right underlay'
)
s = replace_once(
    s,
    '                    crossSpine = true,\n                    modifier = Modifier',
    '                    crossSpine = true,\n                    cancelling = cancelling,\n                    modifier = Modifier',
    'EPUB forward turning cancel param'
)
s = replace_once(
    s,
    '    progress: Float,\n    curlY: Float\n) {\n    val targetIndex = when (turnDirection)',
    '    progress: Float,\n    curlY: Float,\n    cancelling: Boolean\n) {\n    val revealCurrentUnderlay = cancelling && progress < 0.10f\n    val targetIndex = if (revealCurrentUnderlay) pageIndex else when (turnDirection)',
    'EPUB single signature and target'
)
s = replace_once(
    s,
    '                curlY = curlY,\n                cameraDistanceValue = 70f,',
    '                curlY = curlY,\n                cancelling = cancelling,\n                cameraDistanceValue = 70f,',
    'EPUB single turning cancel param'
)
s = replace_once(
    s,
    '    curlY: Float,\n    crossSpine: Boolean = false,',
    '    curlY: Float,\n    cancelling: Boolean = false,\n    crossSpine: Boolean = false,',
    'EPUB TurningPage signature'
)
s = replace_once(
    s,
    '        crossSpine = crossSpine,\n        modifier = modifier,',
    '        crossSpine = crossSpine,\n        isCancelling = cancelling,\n        modifier = modifier,',
    'EPUB PageCurl cancel param'
)
p.write_text(s)

# --- PDF reader ---
p = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
s = p.read_text()
s = replace_once(
    s,
    '    var settling by remember { mutableStateOf(false) }\n    // Lock the gesture',
    '    var settling by remember { mutableStateOf(false) }\n    var cancelling by remember { mutableStateOf(false) }\n    // Lock the gesture',
    'PDF cancelling state'
)
s = replace_once(
    s,
    '            settling = true\n            settleAnimation.snapTo(start)',
    '            settling = true\n            cancelling = !shouldComplete\n            settleAnimation.snapTo(start)',
    'PDF settle cancelling start'
)
s = s.replace('                    settling = false\n                }\n            } else {', '                    settling = false\n                    cancelling = false\n                }\n            } else {', 1)
s = s.replace('                    settling = false\n                }\n            }\n            settleAnimation.snapTo(0f)', '                    settling = false\n                    cancelling = false\n                }\n            }\n            settleAnimation.snapTo(0f)', 1)

s = replace_once(
    s,
    '                onHighlightsChanged = onHighlightsChanged\n            )',
    '                onHighlightsChanged = onHighlightsChanged,\n                cancelling = cancelling\n            )',
    'PDF two page call'
)
s = replace_once(
    s,
    '                onHighlightsChanged = onHighlightsChanged\n            )',
    '                onHighlightsChanged = onHighlightsChanged,\n                cancelling = cancelling\n            )',
    'PDF single page call'
)
s = replace_once(
    s,
    '    onHighlightsChanged: () -> Unit\n) {\n    val isForward = turnDirection == 1',
    '    onHighlightsChanged: () -> Unit,\n    cancelling: Boolean\n) {\n    val isForward = turnDirection == 1',
    'PDF two signature'
)
s = replace_once(
    s,
    '    val isBackward = turnDirection == -1\n\n    Row(',
    '    val isBackward = turnDirection == -1\n    val revealCurrentUnderlay = cancelling && progress < 0.10f\n\n    Row(',
    'PDF reveal flag'
)
s = replace_once(
    s,
    '            val leftIndex = if (isBackward) pageIndex - 2 else pageIndex',
    '            val leftIndex = if (isBackward) {\n                if (revealCurrentUnderlay) pageIndex else pageIndex - 2\n            } else pageIndex',
    'PDF left underlay'
)
s = replace_once(
    s,
    '                    crossSpine = true,\n                    modifier = Modifier.fillMaxSize().zIndex(4f)',
    '                    crossSpine = true,\n                    cancelling = cancelling,\n                    modifier = Modifier.fillMaxSize().zIndex(4f)',
    'PDF backward cancel param'
)
s = replace_once(
    s,
    '            val rightIndex = if (isForward) pageIndex + 3 else pageIndex + 1',
    '            val rightIndex = if (isForward) {\n                if (revealCurrentUnderlay) pageIndex + 1 else pageIndex + 3\n            } else pageIndex + 1',
    'PDF right underlay'
)
s = replace_once(
    s,
    '                    crossSpine = true,\n                    modifier = Modifier.fillMaxSize().zIndex(4f)',
    '                    crossSpine = true,\n                    cancelling = cancelling,\n                    modifier = Modifier.fillMaxSize().zIndex(4f)',
    'PDF forward cancel param'
)
s = replace_once(
    s,
    '    onHighlightsChanged: () -> Unit\n) {\n    val targetIndex = when (turnDirection)',
    '    onHighlightsChanged: () -> Unit,\n    cancelling: Boolean\n) {\n    val revealCurrentUnderlay = cancelling && progress < 0.10f\n    val targetIndex = if (revealCurrentUnderlay) pageIndex else when (turnDirection)',
    'PDF single signature target'
)
s = replace_once(
    s,
    '                highlights = highlights,\n                modifier = Modifier.fillMaxSize().zIndex(4f)',
    '                highlights = highlights,\n                cancelling = cancelling,\n                modifier = Modifier.fillMaxSize().zIndex(4f)',
    'PDF single turning cancel param'
)
s = replace_once(
    s,
    '    highlights: List<BookHighlight>,\n    modifier: Modifier = Modifier\n) {\n    val bitmap = remember(document, frontIndex, renderWidthPx, theme)',
    '    highlights: List<BookHighlight>,\n    cancelling: Boolean = false,\n    modifier: Modifier = Modifier\n) {\n    val bitmap = remember(document, frontIndex, renderWidthPx, theme)',
    'PDF single turning signature'
)
s = replace_once(
    s,
    '        crossSpine = false,\n        modifier = modifier,',
    '        crossSpine = false,\n        isCancelling = cancelling,\n        modifier = modifier,',
    'PDF single PageCurl param'
)
s = replace_once(
    s,
    '    highlights: List<BookHighlight>,\n    cameraDistanceValue: Float = 30f,',
    '    highlights: List<BookHighlight>,\n    cancelling: Boolean = false,\n    cameraDistanceValue: Float = 30f,',
    'PDF turning signature'
)
s = replace_once(
    s,
    '        crossSpine = crossSpine,\n        modifier = modifier,',
    '        crossSpine = crossSpine,\n        isCancelling = cancelling,\n        modifier = modifier,',
    'PDF turning PageCurl param'
)
p.write_text(s)

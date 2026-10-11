from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)

# EPUB: stable pages keep their frame shadow, turning-page snapshots do not.
p = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
s = p.read_text()

s = replace_once(
    s,
    '''private fun BookPage(\n    page: ReaderPage?,\n    pageNumber: Int,\n    isBackSide: Boolean = false,\n    modifier: Modifier = Modifier\n) {''',
    '''private fun BookPage(\n    page: ReaderPage?,\n    pageNumber: Int,\n    isBackSide: Boolean = false,\n    showFrameShadow: Boolean = true,\n    modifier: Modifier = Modifier\n) {''',
    'EPUB BookPage signature'
)

s = replace_once(
    s,
    '''    Surface(\n        modifier = modifier\n            .shadow(7.dp, RoundedCornerShape(10.dp))\n            .clip(RoundedCornerShape(10.dp)),\n        color = if (isBackSide) palette.pageBack else palette.page\n    ) {''',
    '''    val pageShape = RoundedCornerShape(10.dp)\n    val framedModifier = if (showFrameShadow) {\n        modifier.shadow(7.dp, pageShape)\n    } else {\n        modifier\n    }\n\n    Surface(\n        modifier = framedModifier.clip(pageShape),\n        color = if (isBackSide) palette.pageBack else palette.page\n    ) {''',
    'EPUB BookPage shadow'
)

# Both front and back faces used by the curl must be shadowless. The resting page
# underneath owns the frame shadow, avoiding a doubled halo during cancel fade.
s = s.replace(
    '''                pageNumber = frontNumber,\n                modifier = Modifier.fillMaxSize()''',
    '''                pageNumber = frontNumber,\n                showFrameShadow = false,\n                modifier = Modifier.fillMaxSize()''',
    1
)
s = s.replace(
    '''                pageNumber = if (backPage != null) backNumber else frontNumber,\n                isBackSide = true,\n                modifier = Modifier.fillMaxSize()''',
    '''                pageNumber = if (backPage != null) backNumber else frontNumber,\n                isBackSide = true,\n                showFrameShadow = false,\n                modifier = Modifier.fillMaxSize()''',
    1
)

p.write_text(s)

# PDF: same rule for frozen turning-page surfaces.
p = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
s = p.read_text()

s = replace_once(
    s,
    '''private fun PdfFrozenPage(\n    document: PdfBookDocument,\n    bitmap: Bitmap?,\n    index: Int?,\n    totalPages: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    highlights: List<BookHighlight>,\n    modifier: Modifier = Modifier\n) {''',
    '''private fun PdfFrozenPage(\n    document: PdfBookDocument,\n    bitmap: Bitmap?,\n    index: Int?,\n    totalPages: Int,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    highlights: List<BookHighlight>,\n    showFrameShadow: Boolean = true,\n    modifier: Modifier = Modifier\n) {''',
    'PDF PdfFrozenPage signature'
)

s = replace_once(
    s,
    '''    Surface(\n        modifier = modifier\n            .shadow(6.dp, RoundedCornerShape(7.dp))\n            .clip(RoundedCornerShape(7.dp)),\n        color = when (theme) {''',
    '''    val pageShape = RoundedCornerShape(7.dp)\n    val framedModifier = if (showFrameShadow) {\n        modifier.shadow(6.dp, pageShape)\n    } else {\n        modifier\n    }\n\n    Surface(\n        modifier = framedModifier.clip(pageShape),\n        color = when (theme) {''',
    'PDF PdfFrozenPage shadow'
)

# All PdfFrozenPage calls inside turning composables should be shadowless. These
# exact snippets occur only in PdfSingleTurningPage/PdfTurningPage in this area.
s = s.replace(
    '''                highlights = highlights,\n                modifier = Modifier.fillMaxSize()''',
    '''                highlights = highlights,\n                showFrameShadow = false,\n                modifier = Modifier.fillMaxSize()''',
    4
)

p.write_text(s)

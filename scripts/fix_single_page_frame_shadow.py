from pathlib import Path

p = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
s = p.read_text()
old = '''    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 3.dp, vertical = 4.dp)
    ) {
        BookPage(
            page = pages.getOrNull(targetIndex),
            pageNumber = targetIndex + 1,
            modifier = Modifier.fillMaxSize()
        )
'''
new = '''    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .shadow(
                elevation = 7.dp,
                shape = RoundedCornerShape(10.dp),
                clip = false
            )
    ) {
        BookPage(
            page = pages.getOrNull(targetIndex),
            pageNumber = targetIndex + 1,
            showFrameShadow = false,
            modifier = Modifier.fillMaxSize()
        )
'''
if old not in s:
    raise SystemExit('single-page frame block not found')
p.write_text(s.replace(old, new, 1))

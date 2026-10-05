from pathlib import Path

pdf_path = Path("app/src/main/java/com/foldbook/app/PdfReader.kt")
build_path = Path("app/build.gradle.kts")
main_path = Path("app/src/main/java/com/foldbook/app/MainActivity.kt")

pdf = pdf_path.read_text(encoding="utf-8")

old_target = """            settleAnimation.animateTo(\n                targetValue = if (shouldComplete) 1f else 0f,\n                animationSpec = tween("""
new_target = """            settleAnimation.animateTo(\n                targetValue = if (shouldComplete) {\n                    // PDF bitmap texture'ını Fold modunda tam 180 dereceye kadar\n                    // götürmek bazı GPU'larda son frame ghosting/parlama bırakıyor.\n                    // Telefon/tek sayfa motoruna dokunmadan yalnızca Fold'da\n                    // final spread'e çok küçük bir açı kala atomik geçiyoruz.\n                    if (twoPage) 0.985f else 1f\n                } else {\n                    0f\n                },\n                animationSpec = tween("""
if old_target not in pdf:
    raise SystemExit("settle target pattern not found")
pdf = pdf.replace(old_target, new_target, 1)

start_marker = "@Composable\nprivate fun PdfTwoPageSpread("
end_marker = "@Composable\nprivate fun PdfBookSpine("
start = pdf.index(start_marker)
end = pdf.index(end_marker, start)
segment = pdf[start:end]
if segment.count("PdfPage(") != 2:
    raise SystemExit(f"expected 2 stationary PdfPage calls in Fold spread, found {segment.count('PdfPage(')}")
segment = segment.replace("PdfPage(", "PdfStablePage(")
pdf = pdf[:start] + segment + pdf[end:]

insert_marker = "@Composable\nprivate fun PdfSinglePageSpread("
stable_page = '''@Composable\nprivate fun PdfStablePage(\n    document: PdfBookDocument,\n    index: Int?,\n    theme: ReaderThemeOption,\n    renderWidthPx: Int,\n    modifier: Modifier = Modifier\n) {\n    if (index == null || index !in 0 until document.pageCount) {\n        Box(modifier = modifier)\n        return\n    }\n\n    // Fold sayfa çevirme başlamadan gereken dört bitmap zaten cache'e alınıyor.\n    // Cache hazırsa produceState/IO teslimi yerine aynı bitmap'i doğrudan kullanmak,\n    // animasyon sonundaki eski-yeni texture üst üste binmesini engelliyor.\n    val cached = document.cachedPage(index, renderWidthPx, theme)\n    if (cached != null) {\n        PdfFrozenPage(\n            bitmap = cached,\n            index = index,\n            theme = theme,\n            modifier = modifier\n        )\n    } else {\n        // İlk açılış gibi cache'in henüz ısınmadığı durumda normal yükleme devam eder.\n        PdfPage(\n            document = document,\n            index = index,\n            theme = theme,\n            renderWidthPx = renderWidthPx,\n            modifier = modifier\n        )\n    }\n}\n\n'''
if insert_marker not in pdf:
    raise SystemExit("PdfSinglePageSpread marker not found")
pdf = pdf.replace(insert_marker, stable_page + insert_marker, 1)
pdf_path.write_text(pdf, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
if 'versionCode = 36' not in build or 'versionName = "0.9.25"' not in build:
    raise SystemExit("version 0.9.25 pattern not found")
build = build.replace('versionCode = 36', 'versionCode = 37', 1)
build = build.replace('versionName = "0.9.25"', 'versionName = "0.9.26"', 1)
build_path.write_text(build, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")
if 'text = "v0.9.25"' not in main:
    raise SystemExit("UI version pattern not found")
main = main.replace('text = "v0.9.25"', 'text = "v0.9.26"', 1)
main_path.write_text(main, encoding="utf-8")

print("Applied FoldBook 0.9.26 PDF Fold handoff patch")

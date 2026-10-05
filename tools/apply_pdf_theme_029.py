from pathlib import Path

pdf_path = Path("app/src/main/java/com/foldbook/app/PdfReader.kt")
build_path = Path("app/build.gradle.kts")
main_path = Path("app/src/main/java/com/foldbook/app/MainActivity.kt")

pdf = pdf_path.read_text(encoding="utf-8")

old = '''    private fun applyTheme(\n        source: Bitmap,\n        theme: ReaderThemeOption\n    ): Bitmap {\n        when (theme) {\n            ReaderThemeOption.LIGHT -> return source\n            ReaderThemeOption.SEPIA -> {\n                Canvas(source).drawColor(\n                    android.graphics.Color.argb(34, 214, 168, 96)\n                )\n                return source\n            }\n            ReaderThemeOption.DARK -> {\n                val output = Bitmap.createBitmap(\n                    source.width,\n                    source.height,\n                    Bitmap.Config.ARGB_8888\n                )\n                val matrix = ColorMatrix(\n                    floatArrayOf(\n                        -1f, 0f, 0f, 0f, 255f,\n                        0f, -1f, 0f, 0f, 255f,\n                        0f, 0f, -1f, 0f, 255f,\n                        0f, 0f, 0f, 1f, 0f\n                    )\n                )\n                val paint = Paint().apply {\n                    colorFilter = ColorMatrixColorFilter(matrix)\n                }\n                Canvas(output).drawBitmap(source, 0f, 0f, paint)\n                source.recycle()\n                return output\n            }\n        }\n    }\n'''

new = '''    private fun applyTheme(\n        source: Bitmap,\n        theme: ReaderThemeOption\n    ): Bitmap {\n        if (theme == ReaderThemeOption.LIGHT) return source\n\n        // PDF sayfasını düz renk bindirmesi veya tam negatif yapmak yerine\n        // luminance tabanlı okuyucu tonlarına eşliyoruz. Bu özellikle taranmış\n        // kitaplarda kağıt dokusunu daha sakin tutar, yazıyı da EPUB temasına\n        // daha yakın ve göz yormayan bir tonda gösterir.\n        val matrixValues = when (theme) {\n            ReaderThemeOption.LIGHT -> return source\n\n            ReaderThemeOption.SEPIA -> floatArrayOf(\n                // Siyah mürekkep -> koyu kahve, beyaz kağıt -> açık krem\n                0.2181f, 0.4282f, 0.0832f, 0f, 58f,\n                0.2193f, 0.4305f, 0.0836f, 0f, 45f,\n                0.2075f, 0.4078f, 0.0791f, 0f, 31f,\n                0f, 0f, 0f, 1f, 0f\n            )\n\n            ReaderThemeOption.DARK -> floatArrayOf(\n                // Tam negatif yerine kömür kağıt + kırık beyaz yazı.\n                // Böylece beyaz patlamalar ve tarama lekeleri daha az rahatsız eder.\n                -0.2286f, -0.4489f, -0.0872f, 0f, 222f,\n                -0.2240f, -0.4397f, -0.0854f, 0f, 218f,\n                -0.2122f, -0.4167f, -0.0809f, 0f, 208f,\n                0f, 0f, 0f, 1f, 0f\n            )\n        }\n\n        val output = Bitmap.createBitmap(\n            source.width,\n            source.height,\n            Bitmap.Config.ARGB_8888\n        )\n        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {\n            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrixValues))\n        }\n        Canvas(output).drawBitmap(source, 0f, 0f, paint)\n        source.recycle()\n        return output\n    }\n'''

if old not in pdf:
    raise SystemExit("applyTheme block not found; source changed")
pdf = pdf.replace(old, new, 1)
pdf_path.write_text(pdf, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
if 'versionCode = 39' not in build or 'versionName = "0.9.28"' not in build:
    raise SystemExit("unexpected version in build.gradle.kts")
build = build.replace('versionCode = 39', 'versionCode = 40', 1)
build = build.replace('versionName = "0.9.28"', 'versionName = "0.9.29"', 1)
build_path.write_text(build, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")
if 'text = "v0.9.28"' not in main:
    raise SystemExit("version label not found")
main = main.replace('text = "v0.9.28"', 'text = "v0.9.29"', 1)
main_path.write_text(main, encoding="utf-8")

print("FoldBook 0.9.29 PDF theme mapping patch applied")

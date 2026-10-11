from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)

# Smoothly cross-fade the almost-flat curl into the exact live page instead of
# hard-switching at a progress threshold.
curl = Path("app/src/main/java/com/foldbook/app/PageCurl.kt")
s = curl.read_text()
old = '''            // During the last tiny part of a cancelled turn, draw the exact live
            // front page instead of a nearly-flat mesh. This makes the overlay and
            // the resting page pixel-identical and removes the cancel flash.
            if (p <= 0.025f) {
                drawLayer(frontLayer)
                return@Canvas
            }

            val capturedFront = frontBitmap ?: return@Canvas
            val capturedBack = mirroredBackBitmap ?: return@Canvas
'''
new = '''            if (p <= 0.001f) {
                drawLayer(frontLayer)
                return@Canvas
            }

            val capturedFront = frontBitmap ?: return@Canvas
            val capturedBack = mirroredBackBitmap ?: return@Canvas

            // A cancelled turn used to hard-switch from the mesh to the live page,
            // which looked like a white flash. Keep the exact live page underneath
            // and gradually fade the nearly-flat mesh away during the last 10%.
            val cancelBlendEnd = 0.10f
            val meshOpacity = smootherStep((p / cancelBlendEnd).coerceIn(0f, 1f))
            if (p < cancelBlendEnd) {
                drawLayer(frontLayer)
            }
'''
s = replace_once(s, old, new, "PageCurl cancel handoff")

s = replace_once(
    s,
    '''                    frontColors[vertex] = android.graphics.Color.argb(
                        (frontAlpha * 255f).toInt().coerceIn(0, 255),
                        brightness,
                        brightness,
                        brightness
                    )
                    backColors[vertex] = android.graphics.Color.argb(
                        (backAlpha * 255f).toInt().coerceIn(0, 255),
                        brightness,
                        brightness,
                        brightness
                    )
''',
    '''                    frontColors[vertex] = android.graphics.Color.argb(
                        (frontAlpha * meshOpacity * 255f).toInt().coerceIn(0, 255),
                        brightness,
                        brightness,
                        brightness
                    )
                    backColors[vertex] = android.graphics.Color.argb(
                        (backAlpha * meshOpacity * 255f).toInt().coerceIn(0, 255),
                        brightness,
                        brightness,
                        brightness
                    )
''',
    "PageCurl mesh opacity"
)
curl.write_text(s)

# Make the cancel return duration proportional to how far the sheet was pulled.
for path, old_ms in [
    ("app/src/main/java/com/foldbook/app/MainActivity.kt", "durationMillis = if (shouldComplete) 230 else 160"),
    ("app/src/main/java/com/foldbook/app/PdfReader.kt", "durationMillis = if (shouldComplete) 235 else 155"),
]:
    p = Path(path)
    s = p.read_text()
    new_ms = (
        "durationMillis = if (shouldComplete) {\n"
        + ("                        230\n" if "MainActivity" in path else "                        235\n")
        + "                    } else {\n"
        + "                        (180 + (start * 300f).toInt()).coerceIn(180, 280)\n"
        + "                    }"
    )
    s = replace_once(s, old_ms, new_ms, f"{path} cancel duration")
    p.write_text(s)

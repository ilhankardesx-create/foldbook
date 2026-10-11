from pathlib import Path

p = Path('app/src/main/java/com/foldbook/app/PageCurl.kt')
s = p.read_text()
old = '''            val cancelBlendEnd = 0.10f
            val meshOpacity = if (isCancelling) {
                smootherStep((p / cancelBlendEnd).coerceIn(0f, 1f))
            } else {
                1f
            }
'''
new = '''            val cancelBlendEnd = 0.10f
            val cancelOpacity = if (isCancelling) {
                smootherStep((p / cancelBlendEnd).coerceIn(0f, 1f))
            } else {
                1f
            }

            // On a completed single-page turn the mesh can leave a 1-2 frame,
            // full-height sliver on the outgoing edge while the destination page is
            // already settled underneath. Fade only that final tail away so the
            // content itself never flashes and the curl geometry remains unchanged.
            val singlePageTailStart = 0.97f
            val completionTailOpacity = if (!crossSpine && !isCancelling) {
                1f - smootherStep(
                    ((p - singlePageTailStart) / (1f - singlePageTailStart))
                        .coerceIn(0f, 1f)
                )
            } else {
                1f
            }
            val meshOpacity = cancelOpacity * completionTailOpacity
'''
if old not in s:
    raise SystemExit('target block not found')
p.write_text(s.replace(old, new, 1))

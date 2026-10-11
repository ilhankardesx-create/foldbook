from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)

# EPUB
p = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
s = p.read_text()
s = replace_once(
    s,
    '''                    durationMillis = if (shouldComplete) {\n                        230\n                    } else {\n                        (180 + (start * 300f).toInt()).coerceIn(180, 280)\n                    },''',
    '''                    durationMillis = if (shouldComplete) {\n                        // Leave more time for a half-finished sheet to settle.\n                        // Near the end it stays shorter, but still has a visible soft landing.\n                        val remaining = (1f - start).coerceIn(0f, 1f)\n                        (240 + (remaining * 260f).toInt()).coerceIn(250, 430)\n                    } else {\n                        (180 + (start * 300f).toInt()).coerceIn(180, 280)\n                    },''',
    'EPUB completion duration'
)
s = replace_once(
    s,
    '''                            dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)''',
    '''                            // Never let a finger fling finish the whole sheet in one input frame.\n                            // Keep a visible release tail so even a very fast swipe still curls to rest.\n                            dragProgress = (abs(dragPx) / pageWidthPx)\n                                .coerceIn(0f, 0.88f)''',
    'EPUB interactive progress cap'
)
p.write_text(s)

# PDF
p = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
s = p.read_text()
s = replace_once(
    s,
    '''                    durationMillis = if (shouldComplete) {\n                        235\n                    } else {\n                        (180 + (start * 300f).toInt()).coerceIn(180, 280)\n                    },''',
    '''                    durationMillis = if (shouldComplete) {\n                        val target = if (twoPage) 0.985f else 1f\n                        val remaining = (target - start).coerceAtLeast(0f)\n                        (245 + (remaining * 270f).toInt()).coerceIn(255, 430)\n                    } else {\n                        (180 + (start * 300f).toInt()).coerceIn(180, 280)\n                    },''',
    'PDF completion duration'
)
s = replace_once(
    s,
    '''                                dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)''',
    '''                                // Preserve a release tail even on a very fast fling.\n                                dragProgress = (abs(dragPx) / pageWidthPx)\n                                    .coerceIn(0f, 0.88f)''',
    'PDF interactive progress cap'
)
p.write_text(s)

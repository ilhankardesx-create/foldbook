from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)

curl = Path("app/src/main/java/com/foldbook/app/PageCurl.kt")
s = curl.read_text()
s = replace_once(
    s,
    "                val tilt = (touchY - 0.5f) * 0.58f * wave",
    "                val tilt = -(touchY - 0.5f) * 0.58f * wave",
    "curl vertical tilt",
)
curl.write_text(s)

main = Path("app/src/main/java/com/foldbook/app/MainActivity.kt")
s = main.read_text()
old = '''                if (isBackSide) {
                    Text(
                        text = highlightedBody,
                        fontSize = bodySize.sp,
                        lineHeight = (bodySize + 11f).sp,
                        fontFamily = FontFamily.Serif,
                        color = palette.text.copy(alpha = 0.88f)
                    )
                } else {
'''
new = '''                if (isBackSide) {
                    BasicTextField(
                        value = TextFieldValue(annotatedString = highlightedBody),
                        onValueChange = {},
                        modifier = Modifier.fillMaxSize(),
                        readOnly = true,
                        textStyle = TextStyle(
                            fontSize = bodySize.sp,
                            lineHeight = (bodySize + 11f).sp,
                            fontFamily = FontFamily.Serif,
                            color = palette.text
                        )
                    )
                } else {
'''
s = replace_once(s, old, new, "EPUB backside renderer")
main.write_text(s)

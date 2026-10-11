from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)


def patch_reader(path: str, pdf: bool) -> None:
    p = Path(path)
    s = p.read_text()

    state_anchor = "    var settling by remember { mutableStateOf(false) }\n"
    state_new = state_anchor + (
        "    // Lock the gesture to one axis before a page turn begins. Vertical\n"
        "    // swipes must never accidentally create a curl/cancel animation.\n"
        "    var dragAxis by remember { mutableIntStateOf(0) } // 0 undecided, 1 horizontal, 2 vertical\n"
        "    var gestureDx by remember { mutableFloatStateOf(0f) }\n"
        "    var gestureDy by remember { mutableFloatStateOf(0f) }\n"
    )
    s = replace_once(s, state_anchor, state_new, f"{path} gesture state")

    if pdf:
        start_old = '''                onDragStart = { startOffset ->
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = (startOffset.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                    turnBaseIndex = pageIndex
                    turnDirection = 0
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragYFraction = (change.position.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                    val proposed = (dragPx + dragAmount.x)
                        .coerceIn(-pageWidthPx, pageWidthPx)
                    val direction = when {
                        proposed < 0f -> 1
                        proposed > 0f -> -1
                        else -> 0
                    }
                    when {
                        direction == 0 -> {
                            dragPx = 0f
                            dragProgress = 0f
                            turnDirection = 0
                        }
                        canTurn(direction) && turnAssetsReady(direction) -> {
                            dragPx = proposed
                            turnDirection = direction
                            dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
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
                },
                onDragEnd = { settleTurn() },
                onDragCancel = { settleTurn(cancelOnly = true) }
'''
        start_new = '''                onDragStart = { startOffset ->
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = (startOffset.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                    turnBaseIndex = pageIndex
                    turnDirection = 0
                    dragAxis = 0
                    gestureDx = 0f
                    gestureDy = 0f
                },
                onDrag = { change, dragAmount ->
                    gestureDx += dragAmount.x
                    gestureDy += dragAmount.y

                    if (dragAxis == 0) {
                        val absX = abs(gestureDx)
                        val absY = abs(gestureDy)
                        dragAxis = when {
                            absY > absX * 1.15f -> 2
                            absX > absY * 1.15f -> 1
                            else -> 0
                        }
                    }

                    if (dragAxis == 2) {
                        // A vertical gesture is not a page turn. Leave the page fully
                        // live so there is no curl to cancel and therefore no flash.
                        dragPx = 0f
                        dragProgress = 0f
                        turnDirection = 0
                    } else if (dragAxis == 1) {
                        change.consume()
                        dragYFraction = (change.position.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                        val proposed = gestureDx.coerceIn(-pageWidthPx, pageWidthPx)
                        val direction = when {
                            proposed < 0f -> 1
                            proposed > 0f -> -1
                            else -> 0
                        }
                        when {
                            direction == 0 -> {
                                dragPx = 0f
                                dragProgress = 0f
                                turnDirection = 0
                            }
                            canTurn(direction) && turnAssetsReady(direction) -> {
                                dragPx = proposed
                                turnDirection = direction
                                dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                            }
                            canTurn(direction) -> {
                                warmTurnAssets(direction)
                                dragPx = 0f
                                dragProgress = 0f
                                turnDirection = 0
                            }
                            else -> {
                                dragPx = 0f
                                dragProgress = 0f
                                turnDirection = 0
                            }
                        }
                    }
                },
                onDragEnd = {
                    val horizontalTurn = dragAxis == 1 && turnDirection != 0
                    dragAxis = 0
                    gestureDx = 0f
                    gestureDy = 0f
                    if (horizontalTurn) {
                        settleTurn()
                    } else {
                        dragPx = 0f
                        dragProgress = 0f
                        turnDirection = 0
                    }
                },
                onDragCancel = {
                    val horizontalTurn = dragAxis == 1 && turnDirection != 0
                    dragAxis = 0
                    gestureDx = 0f
                    gestureDy = 0f
                    if (horizontalTurn) {
                        settleTurn(cancelOnly = true)
                    } else {
                        dragPx = 0f
                        dragProgress = 0f
                        turnDirection = 0
                    }
                }
'''
    else:
        start_old = '''                onDragStart = { startOffset ->
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = (startOffset.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                    turnBaseIndex = pageIndex
                    turnDirection = 0
                },
                onDrag = { change, dragAmount ->
                    change.consume()

                    dragYFraction = (change.position.y / pageHeightPx)
                        .coerceIn(0.06f, 0.94f)

                    val proposed = (dragPx + dragAmount.x)
                        .coerceIn(-pageWidthPx, pageWidthPx)

                    val direction = when {
                        proposed < 0f -> 1
                        proposed > 0f -> -1
                        else -> 0
                    }

                    if (direction == 0 || canTurn(direction)) {
                        dragPx = proposed
                        turnDirection = direction
                        dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                    } else {
                        dragPx = proposed.coerceIn(
                            -pageWidthPx * 0.06f,
                            pageWidthPx * 0.06f
                        )
                        dragProgress = 0f
                        turnDirection = 0
                    }
                },
                onDragEnd = { settleTurn() },
                onDragCancel = { settleTurn(cancelOnly = true) }
'''
        start_new = '''                onDragStart = { startOffset ->
                    dragPx = 0f
                    dragProgress = 0f
                    dragYFraction = (startOffset.y / pageHeightPx).coerceIn(0.06f, 0.94f)
                    turnBaseIndex = pageIndex
                    turnDirection = 0
                    dragAxis = 0
                    gestureDx = 0f
                    gestureDy = 0f
                },
                onDrag = { change, dragAmount ->
                    gestureDx += dragAmount.x
                    gestureDy += dragAmount.y

                    if (dragAxis == 0) {
                        val absX = abs(gestureDx)
                        val absY = abs(gestureDy)
                        dragAxis = when {
                            absY > absX * 1.15f -> 2
                            absX > absY * 1.15f -> 1
                            else -> 0
                        }
                    }

                    if (dragAxis == 2) {
                        dragPx = 0f
                        dragProgress = 0f
                        turnDirection = 0
                    } else if (dragAxis == 1) {
                        change.consume()
                        dragYFraction = (change.position.y / pageHeightPx)
                            .coerceIn(0.06f, 0.94f)

                        val proposed = gestureDx.coerceIn(-pageWidthPx, pageWidthPx)
                        val direction = when {
                            proposed < 0f -> 1
                            proposed > 0f -> -1
                            else -> 0
                        }

                        if (direction == 0 || canTurn(direction)) {
                            dragPx = proposed
                            turnDirection = direction
                            dragProgress = (abs(dragPx) / pageWidthPx).coerceIn(0f, 1f)
                        } else {
                            dragPx = 0f
                            dragProgress = 0f
                            turnDirection = 0
                        }
                    }
                },
                onDragEnd = {
                    val horizontalTurn = dragAxis == 1 && turnDirection != 0
                    dragAxis = 0
                    gestureDx = 0f
                    gestureDy = 0f
                    if (horizontalTurn) {
                        settleTurn()
                    } else {
                        dragPx = 0f
                        dragProgress = 0f
                        turnDirection = 0
                    }
                },
                onDragCancel = {
                    val horizontalTurn = dragAxis == 1 && turnDirection != 0
                    dragAxis = 0
                    gestureDx = 0f
                    gestureDy = 0f
                    if (horizontalTurn) {
                        settleTurn(cancelOnly = true)
                    } else {
                        dragPx = 0f
                        dragProgress = 0f
                        turnDirection = 0
                    }
                }
'''

    s = replace_once(s, start_old, start_new, f"{path} drag block")
    p.write_text(s)


patch_reader("app/src/main/java/com/foldbook/app/MainActivity.kt", pdf=False)
patch_reader("app/src/main/java/com/foldbook/app/PdfReader.kt", pdf=True)

curl = Path("app/src/main/java/com/foldbook/app/PageCurl.kt")
s = curl.read_text()
s = replace_once(
    s,
    "            if (p <= 0.001f) {\n                drawLayer(frontLayer)\n                return@Canvas\n            }",
    "            // During the last tiny part of a cancelled turn, draw the exact live\n            // front page instead of a nearly-flat mesh. This makes the overlay and\n            // the resting page pixel-identical and removes the cancel flash.\n            if (p <= 0.025f) {\n                drawLayer(frontLayer)\n                return@Canvas\n            }",
    "PageCurl live-front threshold",
)
curl.write_text(s)

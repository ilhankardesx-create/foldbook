from pathlib import Path

pdf_path = Path('app/src/main/java/com/foldbook/app/PdfReader.kt')
main_path = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
gradle_path = Path('app/build.gradle.kts')

pdf = pdf_path.read_text(encoding='utf-8')

if 'PDF_SELECTION_HANDLES_028' not in pdf:
    old_state = '''    var selection by remember(index) { mutableStateOf<PdfSelectionSnapshot?>(null) }\n    var dragStart by remember(index) { mutableStateOf<Offset?>(null) }\n    var dragEnd by remember(index) { mutableStateOf<Offset?>(null) }\n'''
    new_state = '''    // PDF_SELECTION_HANDLES_028: EPUB-benzeri iki uçlu metin seçimi.\n    var selection by remember(index) { mutableStateOf<PdfSelectionSnapshot?>(null) }\n    var selectionStartPoint by remember(index) { mutableStateOf<Point?>(null) }\n    var selectionEndPoint by remember(index) { mutableStateOf<Point?>(null) }\n    var dragStart by remember(index) { mutableStateOf<Offset?>(null) }\n    var dragEnd by remember(index) { mutableStateOf<Offset?>(null) }\n    var handlePreview by remember(index) { mutableStateOf<Pair<Int, Offset>?>(null) }\n'''
    if old_state not in pdf:
        raise SystemExit('PDF selection state anchor not found')
    pdf = pdf.replace(old_state, new_state, 1)

    start = pdf.index('    val selectionModifier = if (')
    end = pdf.index('\n\n    Surface(\n', start)
    new_block = r'''    val handleKnobOffsetPx = with(densityInfo) { 7.dp.toPx() }
    val handleRadiusPx = with(densityInfo) { 6.5.dp.toPx() }
    val handleTouchRadiusPx = with(densityInfo) { 30.dp.toPx() }

    fun orderedPdfPoints(first: Point, second: Point): Pair<Point, Point> {
        val firstComesBefore =
            first.y < second.y || (first.y == second.y && first.x <= second.x)
        return if (firstComesBefore) first to second else second to first
    }

    fun normalizedSelectionPoints(first: Point, second: Point): Pair<Point, Point> {
        val g = geometry ?: return orderedPdfPoints(first, second)
        val almostSame =
            kotlin.math.abs(first.x - second.x) <= 2 &&
                kotlin.math.abs(first.y - second.y) <= 2
        val expandedSecond = if (almostSame) {
            val forwardX = (first.x + 18).coerceAtMost(g.pageWidthPoints)
            if (forwardX != first.x) Point(forwardX, first.y)
            else Point((first.x - 18).coerceAtLeast(0), first.y)
        } else second
        return orderedPdfPoints(first, expandedSecond)
    }

    fun selectionHandleCenters(snapshot: PdfSelectionSnapshot?): Pair<Offset, Offset>? {
        val shown = snapshot?.bounds?.mapNotNull(::toDisplayRect).orEmpty()
        if (shown.isEmpty()) return null
        val first = shown.first()
        val last = shown.last()
        return Offset(first.left, first.bottom + handleKnobOffsetPx) to
            Offset(last.right, last.bottom + handleKnobOffsetPx)
    }

    fun launchSelection(rawStart: Point, rawEnd: Point) {
        val (startPoint, endPoint) = normalizedSelectionPoints(rawStart, rawEnd)
        selectionStartPoint = startPoint
        selectionEndPoint = endPoint
        scope.launch {
            val chosen = withContext(Dispatchers.IO) {
                document.selectByPoints(index, startPoint, endPoint)
            }
            if (chosen == null || chosen.text.isBlank()) {
                selection = null
                selectionStartPoint = null
                selectionEndPoint = null
            } else {
                selection = chosen
            }
        }
    }

    val selectionModifier = if (
        interactive && document.supportsTextSelection && geometry != null
    ) {
        var result = Modifier.pointerInput(index, boxSize, renderWidthPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    dragStart = offset
                    dragEnd = offset
                    selection = null
                    selectionStartPoint = null
                    selectionEndPoint = null
                    handlePreview = null
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragEnd = change.position
                },
                onDragEnd = {
                    val startPoint = dragStart?.let(::toPdfPoint)
                    val stopPoint = dragEnd?.let(::toPdfPoint)
                    dragStart = null
                    dragEnd = null
                    if (startPoint != null && stopPoint != null) {
                        launchSelection(startPoint, stopPoint)
                    }
                },
                onDragCancel = {
                    dragStart = null
                    dragEnd = null
                }
            )
        }

        if (selection != null) {
            result = result.pointerInput(index, selection, boxSize, renderWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        pass = PointerEventPass.Initial,
                        requireUnconsumed = false
                    )
                    val handles = selectionHandleCenters(selection)
                    if (handles == null) {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                        return@awaitEachGesture
                    }

                    fun distanceSquared(a: Offset, b: Offset): Float {
                        val dx = a.x - b.x
                        val dy = a.y - b.y
                        return dx * dx + dy * dy
                    }

                    val r2 = handleTouchRadiusPx * handleTouchRadiusPx
                    val startDistance = distanceSquared(down.position, handles.first)
                    val endDistance = distanceSquared(down.position, handles.second)
                    val activeHandle = when {
                        startDistance <= r2 && startDistance <= endDistance -> 1
                        endDistance <= r2 -> 2
                        else -> 0
                    }

                    if (activeHandle == 0) {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                        return@awaitEachGesture
                    }

                    down.consume()
                    var latest = down.position
                    handlePreview = activeHandle to latest
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        latest = change.position
                        handlePreview = activeHandle to latest
                        change.consume()
                        if (!change.pressed) break
                    }

                    val moved = toPdfPoint(latest)
                    val fixedStart = selectionStartPoint
                    val fixedEnd = selectionEndPoint
                    handlePreview = null
                    if (moved != null && fixedStart != null && fixedEnd != null) {
                        if (activeHandle == 1) launchSelection(moved, fixedEnd)
                        else launchSelection(fixedStart, moved)
                    }
                }
            }
        }
        result
    } else Modifier'''
    pdf = pdf[:start] + new_block + pdf[end:]

    fn = pdf.index('private fun PdfPageSurface(')
    canvas_start = pdf.index('            ComposeCanvas(modifier = Modifier.fillMaxSize()) {', fn)
    canvas_end = pdf.index('\n\n            selection?.takeIf', canvas_start)
    new_canvas = r'''            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                savedHighlightBounds.forEach { rect ->
                    toDisplayRect(rect)?.let { shown ->
                        drawRect(
                            color = Color(0xFFFFE45C).copy(alpha = 0.46f),
                            topLeft = Offset(shown.left, shown.top),
                            size = Size(
                                (shown.right - shown.left).coerceAtLeast(1f),
                                (shown.bottom - shown.top).coerceAtLeast(1f)
                            )
                        )
                    }
                }
                selection?.let { chosen ->
                    chosen.bounds.forEach { rect ->
                        toDisplayRect(rect)?.let { shown ->
                            drawRect(
                                color = Color(0xFF74A9FF).copy(alpha = 0.34f),
                                topLeft = Offset(shown.left, shown.top),
                                size = Size(
                                    (shown.right - shown.left).coerceAtLeast(1f),
                                    (shown.bottom - shown.top).coerceAtLeast(1f)
                                )
                            )
                        }
                    }
                    selectionHandleCenters(chosen)?.let { baseHandles ->
                        val preview = handlePreview
                        val startCenter = if (preview?.first == 1) preview.second else baseHandles.first
                        val endCenter = if (preview?.first == 2) preview.second else baseHandles.second
                        val handleColor = Color(0xFF2F6FED)
                        drawLine(
                            color = handleColor,
                            start = Offset(startCenter.x, startCenter.y - handleKnobOffsetPx),
                            end = startCenter,
                            strokeWidth = 2.2.dp.toPx()
                        )
                        drawCircle(handleColor, handleRadiusPx, startCenter)
                        drawLine(
                            color = handleColor,
                            start = Offset(endCenter.x, endCenter.y - handleKnobOffsetPx),
                            end = endCenter,
                            strokeWidth = 2.2.dp.toPx()
                        )
                        drawCircle(handleColor, handleRadiusPx, endCenter)
                    }
                }
            }'''
    pdf = pdf[:canvas_start] + new_canvas + pdf[canvas_end:]

    pdf = pdf.replace(
        '                        Toast.makeText(context, "Notlara eklendi.", Toast.LENGTH_SHORT).show()\n                        selection = null\n',
        '                        Toast.makeText(context, "Notlara eklendi.", Toast.LENGTH_SHORT).show()\n                        selection = null\n                        selectionStartPoint = null\n                        selectionEndPoint = null\n                        handlePreview = null\n',
        1,
    )
    pdf = pdf.replace(
        '                            ).show()\n                            selection = null\n',
        '                            ).show()\n                            selection = null\n                            selectionStartPoint = null\n                            selectionEndPoint = null\n                            handlePreview = null\n',
        1,
    )
    pdf_path.write_text(pdf, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace('versionCode = 38', 'versionCode = 39', 1)
gradle = gradle.replace('versionName = "0.9.27"', 'versionName = "0.9.28"', 1)
gradle_path.write_text(gradle, encoding='utf-8')

main = main_path.read_text(encoding='utf-8')
main = main.replace('text = "v0.9.27"', 'text = "v0.9.28"', 1)
main_path.write_text(main, encoding='utf-8')

print('FoldBook 0.9.28 PDF selection handles patch applied.')

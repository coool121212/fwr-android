package com.fwr.app.ui.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fwr.app.data.model.MediaMetadata
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import java.util.Locale
import kotlin.math.abs

internal enum class MarkdownBlockKind {
    PARAGRAPH,
    QUOTE,
    HORIZONTAL_RULE,
    HEADING,
    CODE_BLOCK,
    LIST_ITEM,
    TABLE
}

internal enum class MarkdownTableAlign {
    LEFT,
    CENTER,
    RIGHT
}

internal data class MarkdownBlock(
    val isQuote: Boolean = false,
    val lines: List<String>,
    val kind: MarkdownBlockKind = if (isQuote) MarkdownBlockKind.QUOTE else MarkdownBlockKind.PARAGRAPH,
    val quoteDepth: Int = if (isQuote) 1 else 0,
    val headingLevel: Int = 0,
    val listMarker: String = "",
    val listIndent: Int = 0,
    val tableHeaders: List<String> = emptyList(),
    val tableAlignments: List<MarkdownTableAlign> = emptyList(),
    val tableRows: List<List<String>> = emptyList()
)

internal data class ParsedMarkdownBlock(
    val annotatedString: AnnotatedString,
    val spoilerCount: Int,
    val links: List<MarkdownLinkItem> = emptyList()
)

private sealed interface RenderedMarkdownBlock {
    data object HorizontalRule : RenderedMarkdownBlock
    data class CodeBlock(val code: String) : RenderedMarkdownBlock
    data class Heading(val level: Int, val parsed: ParsedMarkdownBlock) : RenderedMarkdownBlock
    data class Quote(
        val depth: Int,
        val parsed: ParsedMarkdownBlock,
        val inlineImageUrls: List<String>
    ) : RenderedMarkdownBlock
    data class ListItem(
        val marker: String,
        val indent: Int,
        val parsed: ParsedMarkdownBlock,
        val inlineImageUrls: List<String>
    ) : RenderedMarkdownBlock
    data class Paragraph(
        val parsed: ParsedMarkdownBlock,
        val inlineImageUrls: List<String>
    ) : RenderedMarkdownBlock
    data class Table(
        val headers: List<ParsedMarkdownBlock>,
        val alignments: List<MarkdownTableAlign>,
        val rows: List<List<ParsedMarkdownBlock>>,
        val columnWidthsDp: List<Dp>
    ) : RenderedMarkdownBlock
}

private data class PreparedMarkdownDocument(
    val items: List<RenderedMarkdownBlock>,
    val links: List<MarkdownLinkItem>
)

private val GIPHY_EMBED_TOKEN_REGEX = Regex(
    """^giphy\|([A-Za-z0-9_-]+)(?:\|.*)?$""",
    RegexOption.IGNORE_CASE
)
private val INLINE_IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
private val GIPHY_DIRECT_MEDIA_REGEX = Regex(
    """https?://(?:media\d*\.)?giphy\.com/(?:gifs/(?:[^/]+-)?|media/)([A-Za-z0-9]+)""",
    RegexOption.IGNORE_CASE
)
private val INLINE_BARE_URL_REGEX = Regex("""https?://[^\s<>\)\]]+""")

private const val MAX_INLINE_DEPTH = 6
private const val ESCAPABLE_MARKDOWN_CHARS = """\*_~^[]()><#-`!|"""

private val defaultItalicSupportsNativeSlant: Boolean by lazy {
    runCatching {
        val italicTypeface: Typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, 400, true)
        } else {
            Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        } ?: return@runCatching false

        val paint = Paint().apply { textSize = 100f }
        val regPath = Path()
        val italPath = Path()
        val regBounds = RectF()
        val italBounds = RectF()
        var anyValidReg = false

        fun measureDelta(sample: String): Float? {
            regPath.reset()
            italPath.reset()
            paint.typeface = Typeface.DEFAULT
            paint.getTextPath(sample, 0, sample.length, 0f, 0f, regPath)
            regPath.computeBounds(regBounds, true)
            if (regBounds.width() <= 0f) return null
            anyValidReg = true
            paint.typeface = italicTypeface
            paint.getTextPath(sample, 0, sample.length, 0f, 0f, italPath)
            italPath.computeBounds(italBounds, true)
            return maxOf(
                abs(italBounds.width() - regBounds.width()),
                abs(italBounds.right - regBounds.right)
            )
        }

        val deltaI = measureDelta("I")
        val deltaH = measureDelta("H")
        if (!anyValidReg) {
            italicTypeface.isItalic
        } else {
            maxOf(deltaI ?: 0f, deltaH ?: 0f) > 2f
        }
    }.getOrDefault(true)
}

private fun italicSpanStyle(): SpanStyle {
    return if (defaultItalicSupportsNativeSlant) {
        SpanStyle(fontStyle = FontStyle.Italic)
    } else {
        SpanStyle(
            fontStyle = FontStyle.Italic,
            textGeometricTransform = TextGeometricTransform(skewX = -0.25f)
        )
    }
}

internal fun unescapeMarkdownEntities(text: String): String {
    if (!text.contains('&')) return text
    return text
        .replace("&nbsp;", " ")
        .replace("&#x200B;", "", ignoreCase = true)
        .replace("&#8203;", "")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
}

private fun isHorizontalRuleLine(trimmed: String): Boolean {
    if (trimmed.length < 3) return false
    var ruleChar = '\u0000'
    var count = 0
    for (c in trimmed) {
        when (c) {
            ' ', '\t' -> Unit
            '-', '*', '_' -> {
                if (ruleChar == '\u0000') {
                    ruleChar = c
                } else if (ruleChar != c) {
                    return false
                }
                count++
            }
            else -> return false
        }
    }
    return count >= 3
}

private fun parseFenceHeader(trimmedStart: String): Pair<Char, Int>? {
    if (trimmedStart.length < 3) return null
    val ch = trimmedStart[0]
    if (ch != '`' && ch != '~') return null
    var count = 0
    while (count < trimmedStart.length && trimmedStart[count] == ch) {
        count++
    }
    if (count < 3) return null
    if (ch == '`' && trimmedStart.indexOf('`', startIndex = count) != -1) {
        return null
    }
    return ch to count
}

private fun isFenceClosingLine(trimmed: String, fenceChar: Char, minFenceLen: Int): Boolean {
    if (trimmed.length < minFenceLen) return false
    for (c in trimmed) {
        if (c != fenceChar) return false
    }
    return true
}

private fun parseAtxHeadingLine(trimmedStart: String): Pair<Int, String>? {
    var level = 0
    while (level < trimmedStart.length && level < 6 && trimmedStart[level] == '#') {
        level++
    }
    if (level == 0 || level >= trimmedStart.length) return null
    val afterHash = trimmedStart[level]
    if (afterHash != ' ' && afterHash != '\t') return null
    var content = trimmedStart.substring(level + 1).trim()
    // Strip optional closing ATX hashes preceded by whitespace (e.g., "# Title #")
    var end = content.length
    while (end > 0 && content[end - 1] == '#') {
        end--
    }
    if (end < content.length && end > 0 && content[end - 1].isWhitespace()) {
        content = content.substring(0, end).trimEnd()
    }
    return level to content
}

private fun parseQuotePrefix(line: String): Pair<Int, String>? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith(">") || trimmed.startsWith(">!")) return null
    var depth = 0
    var rest = trimmed
    while (rest.startsWith(">") && !rest.startsWith(">!")) {
        depth++
        rest = rest.removePrefix(">")
        if (rest.startsWith(" ") || rest.startsWith("\t")) {
            rest = rest.substring(1)
        }
        val nextTrimmed = rest.trimStart()
        if (nextTrimmed.startsWith(">") && !nextTrimmed.startsWith(">!")) {
            rest = nextTrimmed
        } else {
            break
        }
    }
    return depth.coerceAtMost(5) to rest.trimStart()
}

private data class ListPrefixMatch(
    val marker: String,
    val indent: Int,
    val content: String
)

private fun parseListItemLine(line: String): ListPrefixMatch? {
    var leadingSpaces = 0
    var idx = 0
    while (idx < line.length && (line[idx] == ' ' || line[idx] == '\t')) {
        leadingSpaces += if (line[idx] == '\t') 2 else 1
        idx++
    }
    if (idx >= line.length) return null
    val indent = (leadingSpaces / 2).coerceAtMost(6)
    val first = line[idx]
    if ((first == '*' || first == '-' || first == '+') &&
        idx + 1 < line.length &&
        (line[idx + 1] == ' ' || line[idx + 1] == '\t')
    ) {
        return ListPrefixMatch(
            marker = "•",
            indent = indent,
            content = line.substring(idx + 2).trimStart()
        )
    }
    if (first in '0'..'9') {
        var d = idx
        while (d < line.length && line[d] in '0'..'9' && (d - idx) < 9) {
            d++
        }
        if (d + 1 < line.length &&
            (line[d] == '.' || line[d] == ')') &&
            (line[d + 1] == ' ' || line[d + 1] == '\t')
        ) {
            return ListPrefixMatch(
                marker = "${line.substring(idx, d)}.",
                indent = indent,
                content = line.substring(d + 2).trimStart()
            )
        }
    }
    return null
}

private fun splitTableRawCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '\\' && i + 1 < line.length && line[i + 1] == '|') {
            sb.append('|')
            i += 2
            continue
        }
        if (c == '|') {
            cells.add(sb.toString())
            sb.setLength(0)
        } else {
            sb.append(c)
        }
        i++
    }
    cells.add(sb.toString())
    val trimmedLine = line.trim()
    if (trimmedLine.startsWith('|') && cells.isNotEmpty() && cells.first().isBlank()) {
        cells.removeAt(0)
    }
    if (trimmedLine.endsWith('|') && cells.isNotEmpty() && cells.last().isBlank()) {
        cells.removeAt(cells.lastIndex)
    }
    return cells.map { it.trim() }
}

private fun parseTableSeparatorRow(line: String): List<MarkdownTableAlign>? {
    if (!line.contains('|')) return null
    val rawCells = splitTableRawCells(line)
    if (rawCells.isEmpty()) return null
    if (!line.trim().startsWith('|') && !line.trim().endsWith('|') && rawCells.size < 2) {
        return null
    }
    val alignments = ArrayList<MarkdownTableAlign>(rawCells.size)
    for (cell in rawCells) {
        if (cell.isEmpty()) return null
        val startsColon = cell.startsWith(':')
        val endsColon = cell.endsWith(':')
        val startIdx = if (startsColon) 1 else 0
        val endIdx = if (endsColon && cell.length > startIdx) cell.length - 1 else cell.length
        if (endIdx <= startIdx) return null
        for (k in startIdx until endIdx) {
            if (cell[k] != '-') return null
        }
        alignments.add(
            when {
                startsColon && endsColon -> MarkdownTableAlign.CENTER
                endsColon -> MarkdownTableAlign.RIGHT
                else -> MarkdownTableAlign.LEFT
            }
        )
    }
    return alignments
}

private fun normalizeTableRowCells(rawCells: List<String>, colCount: Int): List<String> {
    return List(colCount) { col -> rawCells.getOrNull(col).orEmpty() }
}

private fun stripIndentedCodePrefix(line: String): String {
    return when {
        line.startsWith("    ") -> line.substring(4)
        line.startsWith("\t") -> line.substring(1)
        else -> line
    }
}

private fun isIndentedCodeLine(line: String): Boolean {
    return line.startsWith("    ") || line.startsWith("\t")
}

internal fun splitMarkdownBlocks(text: String): List<MarkdownBlock> {
    val rawLines = unescapeMarkdownEntities(text).lines()
    val grouped = mutableListOf<MarkdownBlock>()
    val pendingParagraphLines = mutableListOf<String>()
    val pendingQuoteLines = mutableListOf<String>()
    var pendingQuoteDepth = 0

    fun flushParagraph() {
        if (pendingParagraphLines.isNotEmpty()) {
            grouped.add(
                MarkdownBlock(
                    isQuote = false,
                    lines = pendingParagraphLines.toList(),
                    kind = MarkdownBlockKind.PARAGRAPH
                )
            )
            pendingParagraphLines.clear()
        }
    }

    fun flushQuote() {
        if (pendingQuoteLines.isNotEmpty()) {
            grouped.add(
                MarkdownBlock(
                    isQuote = true,
                    lines = pendingQuoteLines.toList(),
                    kind = MarkdownBlockKind.QUOTE,
                    quoteDepth = pendingQuoteDepth
                )
            )
            pendingQuoteLines.clear()
            pendingQuoteDepth = 0
        }
    }

    fun flushActiveTextBlocks() {
        flushParagraph()
        flushQuote()
    }

    var idx = 0
    val total = rawLines.size
    var lastWasBlank = true
    while (idx < total) {
        val line = rawLines[idx]
        if (line.isBlank()) {
            flushActiveTextBlocks()
            lastWasBlank = true
            idx++
            continue
        }

        val trimmedStart = line.trimStart()
        val trimmed = trimmedStart.trimEnd()

        // 1. Fenced code block (``` or ~~~)
        val fence = parseFenceHeader(trimmedStart)
        if (fence != null) {
            flushActiveTextBlocks()
            val (fenceChar, fenceLen) = fence
            val codeLines = mutableListOf<String>()
            idx++
            while (idx < total) {
                val innerLine = rawLines[idx]
                if (isFenceClosingLine(innerLine.trim(), fenceChar, fenceLen)) {
                    idx++
                    break
                }
                codeLines.add(innerLine)
                idx++
            }
            grouped.add(
                MarkdownBlock(
                    isQuote = false,
                    lines = codeLines,
                    kind = MarkdownBlockKind.CODE_BLOCK
                )
            )
            lastWasBlank = false
            continue
        }

        // 2. Horizontal rule (---, ***, ___)
        if (isHorizontalRuleLine(trimmed)) {
            flushActiveTextBlocks()
            grouped.add(
                MarkdownBlock(
                    isQuote = false,
                    lines = emptyList(),
                    kind = MarkdownBlockKind.HORIZONTAL_RULE
                )
            )
            lastWasBlank = false
            idx++
            continue
        }

        // 3. ATX Heading (# .. ######)
        val heading = parseAtxHeadingLine(trimmedStart)
        if (heading != null) {
            flushActiveTextBlocks()
            grouped.add(
                MarkdownBlock(
                    isQuote = false,
                    lines = listOf(heading.second),
                    kind = MarkdownBlockKind.HEADING,
                    headingLevel = heading.first
                )
            )
            lastWasBlank = false
            idx++
            continue
        }

        // 4. Markdown Table (header line with | followed by separator row)
        if (line.contains('|') && idx + 1 < total) {
            val alignments = parseTableSeparatorRow(rawLines[idx + 1])
            if (alignments != null && alignments.isNotEmpty()) {
                flushActiveTextBlocks()
                val headers = normalizeTableRowCells(splitTableRawCells(line), alignments.size)
                val rows = mutableListOf<List<String>>()
                idx += 2
                while (idx < total) {
                    val rowLine = rawLines[idx]
                    if (rowLine.isBlank() || !rowLine.contains('|')) break
                    rows.add(normalizeTableRowCells(splitTableRawCells(rowLine), alignments.size))
                    idx++
                }
                grouped.add(
                    MarkdownBlock(
                        isQuote = false,
                        lines = listOf(line),
                        kind = MarkdownBlockKind.TABLE,
                        tableHeaders = headers,
                        tableAlignments = alignments,
                        tableRows = rows
                    )
                )
                lastWasBlank = false
                continue
            }
        }

        // 5. Blockquotes (> ...), excluding >!spoilers!<
        val quote = parseQuotePrefix(line)
        if (quote != null) {
            flushParagraph()
            val (depth, content) = quote
            if (pendingQuoteLines.isNotEmpty() && pendingQuoteDepth != depth) {
                flushQuote()
            }
            pendingQuoteDepth = depth
            pendingQuoteLines.add(content)
            lastWasBlank = false
            idx++
            continue
        }

        // 6. List items (*, -, +, 1.)
        val listMatch = parseListItemLine(line)
        if (listMatch != null) {
            flushActiveTextBlocks()
            grouped.add(
                MarkdownBlock(
                    isQuote = false,
                    lines = listOf(listMatch.content),
                    kind = MarkdownBlockKind.LIST_ITEM,
                    listMarker = listMatch.marker,
                    listIndent = listMatch.indent
                )
            )
            lastWasBlank = false
            idx++
            continue
        }

        // 7. 4-space / tab indented code block (when not continuing an active paragraph)
        if (lastWasBlank && pendingParagraphLines.isEmpty() && pendingQuoteLines.isEmpty() && isIndentedCodeLine(line)) {
            val codeLines = mutableListOf<String>()
            while (idx < total) {
                val candidate = rawLines[idx]
                if (candidate.isBlank()) {
                    if (idx + 1 < total && isIndentedCodeLine(rawLines[idx + 1])) {
                        codeLines.add("")
                        idx++
                        continue
                    }
                    break
                }
                if (!isIndentedCodeLine(candidate)) break
                codeLines.add(stripIndentedCodePrefix(candidate))
                idx++
            }
            grouped.add(
                MarkdownBlock(
                    isQuote = false,
                    lines = codeLines,
                    kind = MarkdownBlockKind.CODE_BLOCK
                )
            )
            lastWasBlank = false
            continue
        }

        // 8. Normal paragraph line
        flushQuote()
        pendingParagraphLines.add(line)
        lastWasBlank = false
        idx++
    }

    flushActiveTextBlocks()
    return grouped
}

/**
 * Clean-room Reddit Markdown renderer supporting:
 * - Headings (`#`..`######`), horizontal rules (`---`, `***`, `___`)
 * - Blockquotes (`> quoted text`, nested `>>`) with left vertical accent bar(s)
 * - Unordered (`*`, `-`, `+`) and ordered (`1.`) lists
 * - Fenced (```` ``` ````) and 4-space indented code blocks, plus inline code (`` `code` ``)
 * - GFM-style Markdown tables (`| a | b |`)
 * - Bold (`**text**`, `__text__`), Italic (`*text*`, `_text_`), Bold+Italic (`***text***`),
 *   Strikethrough (`~~text~~`), Superscript (`^word`, `^(multi word)`)
 * - Links (`[label](https://...)`, `[label](/relative/path)`, bare `https://...`, `/r/subreddit`, `/u/user`)
 *   opening RIF's PREV/NEXT/SHARE/COPY/GO popup dialog on tap
 * - Tap-to-reveal Reddit spoilers (`>!hidden spoiler!<`)
 * - Embedded comment GIFs (`![gif](giphy|<id>)`) and native `media_metadata` embeds (`![img](<id>)`)
 */
@Composable
fun RifMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RwfPalette.TitleUnreadDark,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 18.sp,
    mediaMetadata: Map<String, MediaMetadata>? = null,
    selectable: Boolean = false,
    showInlineImages: Boolean = true,
    showLinkDialog: Boolean = true,
    onSubredditClick: ((String) -> Unit)? = null,
    onUserClick: ((String) -> Unit)? = null,
    onNonLinkClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val revealedSpoilers = remember(text) { mutableStateMapOf<Int, Boolean>() }
    var spoilerRevealVersion by remember(text) { mutableIntStateOf(0) }
    var activeLinkDialogIndex by remember(text) { mutableStateOf<Int?>(null) }

    val quoteColor = RwfPalette.MetadataTextDark
    val linkColor = RwfPalette.CyanLinkDark
    val codeBgColor = RwfPalette.DarkGraySurface
    val codeTextColor = RwfPalette.TitleUnreadDark
    val spoilerRevealedBgColor = RwfPalette.FlairBackgroundDark
    val quoteBarColor = RwfPalette.FlairBackgroundDark
    val placeholderPainter = remember(codeBgColor) { ColorPainter(codeBgColor) }

    val blocks = remember(text) { splitMarkdownBlocks(text) }

    val prepared = remember(
        blocks,
        mediaMetadata,
        showInlineImages,
        color,
        quoteColor,
        linkColor,
        codeBgColor,
        codeTextColor,
        spoilerRevealedBgColor,
        spoilerRevealVersion
    ) {
        var spoilerCounter = 0
        var linkCounter = 0
        val collectedLinks = mutableListOf<MarkdownLinkItem>()
        val items = ArrayList<RenderedMarkdownBlock>(blocks.size)

        fun parseSegment(segment: String, segmentColor: Color): ParsedMarkdownBlock {
            if (segment.isEmpty()) return ParsedMarkdownBlock(AnnotatedString(""), 0, emptyList())
            val parsed = buildRedditMarkdownAnnotatedString(
                raw = segment,
                baseColor = segmentColor,
                revealedSpoilers = revealedSpoilers,
                spoilerIdStart = spoilerCounter,
                linkIdStart = linkCounter,
                fixBrokenMarkdownLinks = true,
                fixShareLinks = true,
                linkColor = linkColor,
                codeBgColor = codeBgColor,
                codeTextColor = codeTextColor,
                spoilerRevealedBgColor = spoilerRevealedBgColor
            )
            spoilerCounter += parsed.spoilerCount
            linkCounter += parsed.links.size
            collectedLinks.addAll(parsed.links)
            return parsed
        }

        fun prepareDisplayText(rawBlockText: String): Pair<String, List<String>> {
            val inlineUrls = if (!showInlineImages) {
                emptyList()
            } else {
                extractInlineImageUrls(
                    raw = rawBlockText,
                    unwrapRedditMedia = true,
                    mediaMetadata = mediaMetadata
                )
            }
            val displayText = normalizeRedditEmbedTokensForDisplay(
                raw = rawBlockText,
                stripForInlineImages = showInlineImages,
                unwrapRedditMedia = true,
                mediaMetadata = mediaMetadata,
                inlineImageUrls = inlineUrls
            )
            return displayText to inlineUrls
        }

        for (block in blocks) {
            when (block.kind) {
                MarkdownBlockKind.HORIZONTAL_RULE -> {
                    items.add(RenderedMarkdownBlock.HorizontalRule)
                }
                MarkdownBlockKind.CODE_BLOCK -> {
                    items.add(RenderedMarkdownBlock.CodeBlock(block.lines.joinToString("\n")))
                }
                MarkdownBlockKind.HEADING -> {
                    val rawHeading = block.lines.joinToString(" ").trim()
                    val (displayText, _) = prepareDisplayText(rawHeading)
                    if (displayText.isNotEmpty()) {
                        items.add(
                            RenderedMarkdownBlock.Heading(
                                level = block.headingLevel,
                                parsed = parseSegment(displayText, color)
                            )
                        )
                    }
                }
                MarkdownBlockKind.QUOTE -> {
                    val blockText = block.lines.joinToString("\n").trimEnd()
                    val (displayText, inlineUrls) = prepareDisplayText(blockText)
                    if (displayText.isNotBlank() || inlineUrls.isNotEmpty()) {
                        items.add(
                            RenderedMarkdownBlock.Quote(
                                depth = block.quoteDepth.coerceAtLeast(1),
                                parsed = parseSegment(displayText, quoteColor),
                                inlineImageUrls = inlineUrls
                            )
                        )
                    }
                }
                MarkdownBlockKind.LIST_ITEM -> {
                    val itemText = block.lines.joinToString("\n").trimEnd()
                    val (displayText, inlineUrls) = prepareDisplayText(itemText)
                    items.add(
                        RenderedMarkdownBlock.ListItem(
                            marker = block.listMarker,
                            indent = block.listIndent,
                            parsed = parseSegment(displayText, color),
                            inlineImageUrls = inlineUrls
                        )
                    )
                }
                MarkdownBlockKind.TABLE -> {
                    val colCount = block.tableAlignments.size
                    val parsedHeaders = block.tableHeaders.map { parseSegment(it, color) }
                    val parsedRows = block.tableRows.map { row ->
                        row.map { cell -> parseSegment(cell, color) }
                    }
                    val colWidths = List(colCount) { col ->
                        val headerLen = block.tableHeaders.getOrNull(col)?.length ?: 0
                        val maxRowLen = block.tableRows.maxOfOrNull { it.getOrNull(col)?.length ?: 0 } ?: 0
                        val maxChars = maxOf(headerLen, maxRowLen)
                        (maxChars * 7.5f + 16f).coerceIn(64f, 220f).dp
                    }
                    items.add(
                        RenderedMarkdownBlock.Table(
                            headers = parsedHeaders,
                            alignments = block.tableAlignments,
                            rows = parsedRows,
                            columnWidthsDp = colWidths
                        )
                    )
                }
                MarkdownBlockKind.PARAGRAPH -> {
                    val blockText = block.lines.joinToString("\n").trimEnd()
                    if (blockText.isNotEmpty()) {
                        val (displayText, inlineUrls) = prepareDisplayText(blockText)
                        if (displayText.isNotBlank() || inlineUrls.isNotEmpty()) {
                            items.add(
                                RenderedMarkdownBlock.Paragraph(
                                    parsed = parseSegment(displayText, color),
                                    inlineImageUrls = inlineUrls
                                )
                            )
                        }
                    }
                }
            }
        }
        PreparedMarkdownDocument(items = items, links = collectedLinks.toList())
    }

    fun dispatchLinkTarget(item: MarkdownLinkItem) {
        when {
            !item.subreddit.isNullOrBlank() && onSubredditClick != null -> {
                onSubredditClick(item.subreddit)
            }
            !item.username.isNullOrBlank() && onUserClick != null -> {
                onUserClick(item.username)
            }
            else -> {
                MediaResolver.openExternalLink(context, item.url)
            }
        }
    }

    fun handleLinkOrSpoilerTap(annotated: AnnotatedString, offset: Int): Boolean {
        val spoilerAnn = annotated.getStringAnnotations("SPOILER", offset, offset).firstOrNull()
        if (spoilerAnn != null) {
            val sid = spoilerAnn.item.toIntOrNull()
            if (sid != null) {
                revealedSpoilers[sid] = !(revealedSpoilers[sid] ?: false)
                spoilerRevealVersion++
            }
            return true
        }

        val linkIdxAnn = annotated.getStringAnnotations("LINK_INDEX", offset, offset).firstOrNull()
        val linkIdx = linkIdxAnn?.item?.toIntOrNull()
        if (showLinkDialog && linkIdx != null && linkIdx in prepared.links.indices) {
            activeLinkDialogIndex = linkIdx
            return true
        }

        val subAnn = annotated.getStringAnnotations("SUB", offset, offset).firstOrNull()
        if (subAnn != null && onSubredditClick != null) {
            onSubredditClick(subAnn.item)
            return true
        }

        val userAnn = annotated.getStringAnnotations("USER", offset, offset).firstOrNull()
        if (userAnn != null && onUserClick != null) {
            onUserClick(userAnn.item)
            return true
        }

        val urlAnn = annotated.getStringAnnotations("URL", offset, offset).firstOrNull()
        if (urlAnn != null) {
            MediaResolver.openExternalLink(context, urlAnn.item)
            return true
        }

        return false
    }

    activeLinkDialogIndex?.let { initialIdx ->
        if (prepared.links.isNotEmpty()) {
            RifLinkActionDialog(
                links = prepared.links,
                initialIndex = initialIdx,
                onDismiss = { activeLinkDialogIndex = null },
                onGo = { chosen -> dispatchLinkTarget(chosen) }
            )
        }
    }

    val markdownBody: @Composable () -> Unit = {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            prepared.items.forEach { item ->
                when (item) {
                    RenderedMarkdownBlock.HorizontalRule -> {
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth(0.35f)
                                .padding(vertical = 4.dp),
                            thickness = 1.dp,
                            color = RwfPalette.MetadataTextDark.copy(alpha = 0.55f)
                        )
                    }

                    is RenderedMarkdownBlock.CodeBlock -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(codeBgColor, RoundedCornerShape(4.dp))
                                .border(1.dp, RwfPalette.DividerLineDark, RoundedCornerShape(4.dp))
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                text = item.code,
                                color = codeTextColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = (fontSize.value - 1f).coerceAtLeast(10f).sp,
                                lineHeight = (lineHeight.value - 2f).coerceAtLeast(14f).sp
                            )
                        }
                    }

                    is RenderedMarkdownBlock.Heading -> {
                        val deltaSp = when (item.level) {
                            1 -> 4f
                            2 -> 3f
                            3 -> 2f
                            4 -> 1f
                            else -> 0f
                        }
                        val headingFontSp = (fontSize.value + deltaSp).sp
                        val headingLineSp = (lineHeight.value + deltaSp + 1f).sp
                        ClickableMarkdownText(
                            annotated = item.parsed.annotatedString,
                            textStyle = TextStyle(
                                color = color,
                                fontSize = headingFontSp,
                                lineHeight = headingLineSp,
                                fontWeight = FontWeight.Bold
                            ),
                            selectable = selectable,
                            onTapOffset = { offset -> handleLinkOrSpoilerTap(item.parsed.annotatedString, offset) },
                            onNonLinkClick = onNonLinkClick,
                            onLongClick = onLongClick
                        )
                    }

                    is RenderedMarkdownBlock.Quote -> {
                        val barDepth = item.depth.coerceIn(1, 5)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .drawBehind {
                                    val barWidthPx = 3.dp.toPx()
                                    val stepPx = 8.dp.toPx()
                                    val cornerPx = 1.dp.toPx()
                                    for (d in 0 until barDepth) {
                                        drawRoundRect(
                                            color = quoteBarColor,
                                            topLeft = Offset(x = d * stepPx, y = 0f),
                                            size = Size(barWidthPx, size.height),
                                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                                        )
                                    }
                                }
                                .padding(start = (barDepth * 8 + 5).dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (item.parsed.annotatedString.isNotEmpty()) {
                                    ClickableMarkdownText(
                                        annotated = item.parsed.annotatedString,
                                        textStyle = TextStyle(
                                            color = quoteColor,
                                            fontSize = fontSize,
                                            lineHeight = lineHeight
                                        ),
                                        selectable = selectable,
                                        onTapOffset = { offset -> handleLinkOrSpoilerTap(item.parsed.annotatedString, offset) },
                                        onNonLinkClick = onNonLinkClick,
                                        onLongClick = onLongClick
                                    )
                                }
                                InlineImagesColumn(
                                    imageUrls = item.inlineImageUrls,
                                    hasPrecedingText = item.parsed.annotatedString.isNotEmpty(),
                                    codeBgColor = codeBgColor,
                                    linkColor = linkColor,
                                    placeholderPainter = placeholderPainter
                                )
                            }
                        }
                    }

                    is RenderedMarkdownBlock.ListItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.indent * 12).dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = item.marker,
                                color = color,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (item.parsed.annotatedString.isNotEmpty()) {
                                    ClickableMarkdownText(
                                        annotated = item.parsed.annotatedString,
                                        textStyle = TextStyle(
                                            color = color,
                                            fontSize = fontSize,
                                            lineHeight = lineHeight
                                        ),
                                        selectable = selectable,
                                        onTapOffset = { offset -> handleLinkOrSpoilerTap(item.parsed.annotatedString, offset) },
                                        onNonLinkClick = onNonLinkClick,
                                        onLongClick = onLongClick
                                    )
                                }
                                InlineImagesColumn(
                                    imageUrls = item.inlineImageUrls,
                                    hasPrecedingText = item.parsed.annotatedString.isNotEmpty(),
                                    codeBgColor = codeBgColor,
                                    linkColor = linkColor,
                                    placeholderPainter = placeholderPainter
                                )
                            }
                        }
                    }

                    is RenderedMarkdownBlock.Table -> {
                        val totalTableWidth = item.columnWidthsDp.fold(0.dp) { acc, w -> acc + w }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .border(1.dp, RwfPalette.DividerLineDark, RoundedCornerShape(3.dp))
                        ) {
                            Column(
                                modifier = Modifier.width(totalTableWidth)
                            ) {
                                Row(
                                    modifier = Modifier.background(codeBgColor)
                                ) {
                                    item.headers.forEachIndexed { colIdx, headerParsed ->
                                        val align = when (item.alignments.getOrNull(colIdx)) {
                                            MarkdownTableAlign.CENTER -> TextAlign.Center
                                            MarkdownTableAlign.RIGHT -> TextAlign.End
                                            else -> TextAlign.Start
                                        }
                                        val colWidth = item.columnWidthsDp.getOrElse(colIdx) { 100.dp }
                                        Box(
                                            modifier = Modifier
                                                .width(colWidth)
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            ClickableMarkdownText(
                                                annotated = headerParsed.annotatedString,
                                                textStyle = TextStyle(
                                                    color = color,
                                                    fontSize = (fontSize.value - 1f).coerceAtLeast(10f).sp,
                                                    lineHeight = (lineHeight.value - 2f).coerceAtLeast(14f).sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = align
                                                ),
                                                selectable = selectable,
                                                onTapOffset = { offset ->
                                                    handleLinkOrSpoilerTap(headerParsed.annotatedString, offset)
                                                },
                                                onNonLinkClick = onNonLinkClick,
                                                onLongClick = onLongClick,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                                item.rows.forEach { row ->
                                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                                    Row {
                                        row.forEachIndexed { colIdx, cellParsed ->
                                            val align = when (item.alignments.getOrNull(colIdx)) {
                                                MarkdownTableAlign.CENTER -> TextAlign.Center
                                                MarkdownTableAlign.RIGHT -> TextAlign.End
                                                else -> TextAlign.Start
                                            }
                                            val colWidth = item.columnWidthsDp.getOrElse(colIdx) { 100.dp }
                                            Box(
                                                modifier = Modifier
                                                    .width(colWidth)
                                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                            ) {
                                                ClickableMarkdownText(
                                                    annotated = cellParsed.annotatedString,
                                                    textStyle = TextStyle(
                                                        color = color,
                                                        fontSize = (fontSize.value - 1f).coerceAtLeast(10f).sp,
                                                        lineHeight = (lineHeight.value - 2f).coerceAtLeast(14f).sp,
                                                        textAlign = align
                                                    ),
                                                    selectable = selectable,
                                                    onTapOffset = { offset ->
                                                        handleLinkOrSpoilerTap(cellParsed.annotatedString, offset)
                                                    },
                                                    onNonLinkClick = onNonLinkClick,
                                                    onLongClick = onLongClick,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is RenderedMarkdownBlock.Paragraph -> {
                        if (item.parsed.annotatedString.isNotEmpty()) {
                            ClickableMarkdownText(
                                annotated = item.parsed.annotatedString,
                                textStyle = TextStyle(
                                    color = color,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight
                                ),
                                selectable = selectable,
                                onTapOffset = { offset -> handleLinkOrSpoilerTap(item.parsed.annotatedString, offset) },
                                onNonLinkClick = onNonLinkClick,
                                onLongClick = onLongClick
                            )
                        }
                        InlineImagesColumn(
                            imageUrls = item.inlineImageUrls,
                            hasPrecedingText = item.parsed.annotatedString.isNotEmpty(),
                            codeBgColor = codeBgColor,
                            linkColor = linkColor,
                            placeholderPainter = placeholderPainter
                        )
                    }
                }
            }
        }
    }

    if (selectable && onLongClick == null) {
        SelectionContainer {
            markdownBody()
        }
    } else {
        markdownBody()
    }
}

@Composable
private fun ClickableMarkdownText(
    annotated: AnnotatedString,
    textStyle: TextStyle,
    selectable: Boolean,
    onTapOffset: (Int) -> Boolean,
    onNonLinkClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val latestOnTapOffset by rememberUpdatedState(onTapOffset)
    val latestOnNonLinkClick by rememberUpdatedState(onNonLinkClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)

    fun handleTapPosition(pos: Offset): Boolean {
        val offset = layoutResult?.getOffsetForPosition(pos)
        if (offset == null) {
            latestOnNonLinkClick?.invoke()
            return false
        }
        if (latestOnTapOffset(offset)) {
            return true
        }
        latestOnNonLinkClick?.invoke()
        return false
    }

    Text(
        text = annotated,
        style = textStyle,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(
            annotated,
            selectable,
            onLongClick != null
        ) {
            if (selectable && latestOnLongClick == null) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downTime = System.currentTimeMillis()
                    val startPos = down.position
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    var releasedChange: PointerInputChange? = null
                    var cancelled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            cancelled = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        val movedDist = (change.position - startPos).getDistance()
                        val elapsed = System.currentTimeMillis() - downTime
                        if (movedDist > touchSlop || elapsed >= longPressTimeout) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) {
                            releasedChange = change
                            break
                        }
                    }

                    if (!cancelled && releasedChange != null && !releasedChange.isConsumed) {
                        if (handleTapPosition(releasedChange.position)) {
                            releasedChange.consume()
                        }
                    }
                }
            } else {
                detectTapGestures(
                    onLongPress = if (onLongClick != null) {
                        { latestOnLongClick?.invoke() }
                    } else {
                        null
                    },
                    onTap = { pos ->
                        handleTapPosition(pos)
                    }
                )
            }
        }
    )
}

@Composable
private fun InlineImagesColumn(
    imageUrls: List<String>,
    hasPrecedingText: Boolean,
    codeBgColor: Color,
    linkColor: Color,
    placeholderPainter: ColorPainter
) {
    if (imageUrls.isEmpty()) return
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = if (hasPrecedingText) 4.dp else 0.dp)
    ) {
        imageUrls.forEach { imageUrl ->
            var imageLoadFailed by remember(imageUrl) { mutableStateOf(false) }
            val imageRequest = remember(imageUrl) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build()
            }
            if (imageLoadFailed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .background(codeBgColor, RoundedCornerShape(4.dp))
                        .clickable { MediaResolver.openExternalLink(context, imageUrl) }
                        .padding(10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "[GIF / Image — tap to view]",
                        color = linkColor,
                        fontSize = 12.sp
                    )
                }
            } else {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Inline comment image",
                    contentScale = ContentScale.Fit,
                    placeholder = placeholderPainter,
                    onError = { imageLoadFailed = true },
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(190.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { MediaResolver.openExternalLink(context, imageUrl) }
                )
            }
        }
    }
}

private fun resolveRedditEmbedTokenUrl(
    token: String,
    mediaMetadata: Map<String, MediaMetadata>?
): String? {
    val trimmed = token.trim()
    val giphyMatch = GIPHY_EMBED_TOKEN_REGEX.find(trimmed)
    if (giphyMatch != null) {
        val giphyId = giphyMatch.groupValues[1]
        if (giphyId.isNotBlank()) {
            return "https://i.giphy.com/media/$giphyId/giphy.gif"
        }
    }
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed.replace("&amp;", "&")
    }
    val meta = mediaMetadata?.get(trimmed)
        ?: mediaMetadata?.get(trimmed.substringAfterLast('|'))
        ?: return null
    val rawUrl = meta.s?.gif?.takeIf { it.isNotBlank() }
        ?: meta.s?.u?.takeIf { it.isNotBlank() }
        ?: meta.p.lastOrNull()?.u?.takeIf { it.isNotBlank() }
        ?: return null
    return rawUrl.replace("&amp;", "&")
}

private fun resolveInlineMediaUrl(rawUrl: String, unwrapRedditMedia: Boolean): String? {
    val candidate = if (unwrapRedditMedia) MediaResolver.unwrapRedditMediaUrl(rawUrl) else rawUrl
    val lower = candidate.lowercase(Locale.US).substringBefore('?')
    val giphyIdMatch = GIPHY_DIRECT_MEDIA_REGEX.find(candidate)
    return when {
        giphyIdMatch != null -> "https://i.giphy.com/media/${giphyIdMatch.groupValues[1]}/giphy.gif"
        INLINE_IMAGE_EXTENSIONS.any { lower.endsWith(it) } ||
            lower.contains("i.redd.it/") ||
            lower.contains("preview.redd.it/") ||
            lower.contains("i.imgur.com/") -> candidate
        else -> null
    }
}

private fun isRegexWhitespace(c: Char): Boolean {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
}

private fun isRegexWordChar(c: Char): Boolean {
    return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}

private fun isRedditNameChar(c: Char): Boolean {
    return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
}

private fun unescapeMarkdownLinkLabel(rawLabel: String): String {
    if (!rawLabel.contains('\\')) return rawLabel
    val sb = StringBuilder(rawLabel.length)
    var i = 0
    while (i < rawLabel.length) {
        if (rawLabel[i] == '\\' && i + 1 < rawLabel.length && rawLabel[i + 1] in ESCAPABLE_MARKDOWN_CHARS) {
            sb.append(rawLabel[i + 1])
            i += 2
        } else {
            sb.append(rawLabel[i])
            i++
        }
    }
    return sb.toString()
}

private fun cleanRedditUrlEscapes(url: String): String {
    return url.replace("\\_", "_").replace("\\-", "-")
}

private enum class MarkdownLinkKind {
    URL,
    SUB,
    USER,
    OLD_SPOILER
}

private data class MarkdownLinkMatch(
    val start: Int,
    val endExclusive: Int,
    val label: String,
    val target: String,
    val kind: MarkdownLinkKind,
    val spoilerTitle: String? = null
) {
    val isUrl: Boolean get() = kind == MarkdownLinkKind.URL
}

private class MarkdownLinkScanner(private val raw: String) {
    private var failedBracketBefore = -1
    private var failedUrlTargetBefore = -1

    private fun skipOptionalTitleAndCloseParen(startPos: Int): Int {
        val n = raw.length
        var q = startPos
        while (q < n && (raw[q] == ' ' || raw[q] == '\t')) {
            q++
        }
        if (q < n && raw[q] == ')') return q
        if (q < n && (raw[q] == '"' || raw[q] == '\'')) {
            val quoteChar = raw[q]
            q++
            while (q < n && raw[q] != quoteChar && raw[q] != '\n') {
                if (raw[q] == '\\' && q + 1 < n) {
                    q += 2
                } else {
                    q++
                }
            }
            if (q >= n || raw[q] != quoteChar) return -1
            q++
            while (q < n && (raw[q] == ' ' || raw[q] == '\t')) {
                q++
            }
            if (q < n && raw[q] == ')') return q
        }
        return -1
    }

    private fun extractOptionalTitle(startPos: Int, closeParenIndex: Int): String? {
        var q = startPos
        while (q < closeParenIndex && (raw[q] == ' ' || raw[q] == '\t')) {
            q++
        }
        if (q < closeParenIndex && (raw[q] == '"' || raw[q] == '\'')) {
            val quoteChar = raw[q]
            q++
            val end = raw.lastIndexOf(quoteChar, startIndex = closeParenIndex - 1)
            if (end >= q) {
                return raw.substring(q, end)
            }
        }
        return null
    }

    fun tryMatchAt(start: Int, allowSubOrUser: Boolean): MarkdownLinkMatch? {
        val n = raw.length
        if (start >= n || raw[start] != '[' || start < failedBracketBefore) return null

        var closeBracket = start + 1
        while (closeBracket < n && raw[closeBracket] != ']' && raw[closeBracket] != '\n') {
            if (raw[closeBracket] == '\\' && closeBracket + 1 < n && raw[closeBracket + 1] != '\n') {
                closeBracket += 2
                continue
            }
            if (raw[closeBracket] == '[') {
                // Abort outer candidate at unescaped '[' so inner links and 8000-token scans stay strictly O(N)
                failedBracketBefore = maxOf(failedBracketBefore, closeBracket)
                return null
            }
            closeBracket++
        }
        if (closeBracket >= n || raw[closeBracket] == '\n') {
            failedBracketBefore = maxOf(failedBracketBefore, closeBracket)
            return null
        }
        if (closeBracket == start + 1) {
            return null
        }
        if (closeBracket + 1 >= n || raw[closeBracket + 1] != '(') {
            failedBracketBefore = maxOf(failedBracketBefore, closeBracket + 1)
            return null
        }

        var targetStart = closeBracket + 2
        while (targetStart < n && (raw[targetStart] == ' ' || raw[targetStart] == '\t')) {
            targetStart++
        }
        if (targetStart >= n) {
            failedBracketBefore = maxOf(failedBracketBefore, n)
            return null
        }

        val label = unescapeMarkdownLinkLabel(raw.substring(start + 1, closeBracket))

        // Support optional <...> wrapped targets
        var angleWrapped = false
        if (raw[targetStart] == '<') {
            angleWrapped = true
            targetStart++
        }

        // 1. Check (https?://...)
        val schemeLen = when {
            raw.startsWith("https://", targetStart) -> 8
            raw.startsWith("http://", targetStart) -> 7
            else -> 0
        }
        if (schemeLen > 0) {
            if (targetStart < failedUrlTargetBefore) {
                return null
            }
            var p = targetStart + schemeLen
            var parenDepth = 0
            var lastCloseBracketInScan = closeBracket
            while (p < n && !isRegexWhitespace(raw[p])) {
                val c = raw[p]
                if (angleWrapped && c == '>') break
                if (c == '(') {
                    parenDepth++
                } else if (c == ')') {
                    if (parenDepth == 0) break
                    parenDepth--
                } else if (c == ']') {
                    lastCloseBracketInScan = p
                }
                p++
            }
            val targetEnd = p
            if (targetEnd > targetStart + schemeLen) {
                var afterTarget = targetEnd
                if (angleWrapped) {
                    if (afterTarget >= n || raw[afterTarget] != '>') {
                        failedUrlTargetBefore = maxOf(failedUrlTargetBefore, afterTarget)
                        return null
                    }
                    afterTarget++
                }
                val closeParen = skipOptionalTitleAndCloseParen(afterTarget)
                if (closeParen != -1) {
                    return MarkdownLinkMatch(
                        start = start,
                        endExclusive = closeParen + 1,
                        label = label,
                        target = cleanRedditUrlEscapes(raw.substring(targetStart, targetEnd)),
                        kind = MarkdownLinkKind.URL
                    )
                }
            }
            failedUrlTargetBefore = maxOf(failedUrlTargetBefore, p)
            failedBracketBefore = maxOf(
                failedBracketBefore,
                if (p == n || raw[p] == '\n') p else lastCloseBracketInScan + 1
            )
            return null
        }

        // 2. Check subreddit, user, and root-relative Reddit links
        if (allowSubOrUser && !angleWrapped) {
            var p = targetStart
            val hasLeadingSlash = p < n && raw[p] == '/'
            if (hasLeadingSlash) p++
            val isSubPrefix = p + 1 < n && raw[p] == 'r' && raw[p + 1] == '/'
            val isShortUserPrefix = p + 1 < n && raw[p] == 'u' && raw[p + 1] == '/'
            val isLongUserPrefix = raw.startsWith("user/", p, ignoreCase = true)
            if (isSubPrefix || isShortUserPrefix || isLongUserPrefix) {
                val isSub = isSubPrefix
                p += if (isLongUserPrefix) 5 else 2
                val nameStart = p
                while (p < n && (if (isSub) isRegexWordChar(raw[p]) else isRedditNameChar(raw[p]))) {
                    p++
                }
                if (p > nameStart) {
                    val targetEnd = p
                    if (p < n && raw[p] == '/') p++
                    val closeParen = skipOptionalTitleAndCloseParen(p)
                    if (closeParen != -1) {
                        return MarkdownLinkMatch(
                            start = start,
                            endExclusive = closeParen + 1,
                            label = label,
                            target = raw.substring(targetStart, targetEnd),
                            kind = if (isSub) MarkdownLinkKind.SUB else MarkdownLinkKind.USER
                        )
                    }
                }
            }

            // General root-relative Reddit path (/message/compose/?to=/r/..., /r/sub/wiki/..., /s, /spoiler)
            if (hasLeadingSlash && targetStart + 1 < n && raw[targetStart + 1] != '/' && !isRegexWhitespace(raw[targetStart + 1])) {
                if (targetStart < failedUrlTargetBefore) {
                    return null
                }
                var relEnd = targetStart + 1
                var parenDepth = 0
                var lastCloseBracketInScan = closeBracket
                while (relEnd < n && !isRegexWhitespace(raw[relEnd])) {
                    val c = raw[relEnd]
                    if (c == '(') {
                        parenDepth++
                    } else if (c == ')') {
                        if (parenDepth == 0) break
                        parenDepth--
                    } else if (c == ']') {
                        lastCloseBracketInScan = relEnd
                    }
                    relEnd++
                }
                val closeParen = skipOptionalTitleAndCloseParen(relEnd)
                if (closeParen != -1) {
                    val relPath = cleanRedditUrlEscapes(raw.substring(targetStart, relEnd))
                    if (relPath == "/s" || relPath == "/spoiler" || relPath == "#s") {
                        val title = extractOptionalTitle(relEnd, closeParen)
                        if (!title.isNullOrBlank()) {
                            return MarkdownLinkMatch(
                                start = start,
                                endExclusive = closeParen + 1,
                                label = label,
                                target = relPath,
                                kind = MarkdownLinkKind.OLD_SPOILER,
                                spoilerTitle = title
                            )
                        }
                    }
                    return MarkdownLinkMatch(
                        start = start,
                        endExclusive = closeParen + 1,
                        label = label,
                        target = "https://www.reddit.com$relPath",
                        kind = MarkdownLinkKind.URL
                    )
                }
                failedUrlTargetBefore = maxOf(failedUrlTargetBefore, relEnd)
                failedBracketBefore = maxOf(
                    failedBracketBefore,
                    if (relEnd == n || raw[relEnd] == '\n') relEnd else lastCloseBracketInScan + 1
                )
                return null
            }
        }

        failedBracketBefore = maxOf(failedBracketBefore, closeBracket + 1)
        return null
    }
}

private inline fun forEachRedditEmbedToken(
    raw: String,
    onMatch: (start: Int, endExclusive: Int, rawMatch: String, token: String) -> Unit
) {
    val n = raw.length
    var i = 0
    while (i + 7 < n) {
        if (raw[i] == '!' && raw[i + 1] == '[' && raw[i + 5] == ']' && raw[i + 6] == '(') {
            val tag1 = raw[i + 2]
            val tag2 = raw[i + 3]
            val tag3 = raw[i + 4]
            val isGifOrImg = ((tag1 == 'g' || tag1 == 'G') && (tag2 == 'i' || tag2 == 'I') && (tag3 == 'f' || tag3 == 'F')) ||
                ((tag1 == 'i' || tag1 == 'I') && (tag2 == 'm' || tag2 == 'M') && (tag3 == 'g' || tag3 == 'G'))
            if (isGifOrImg) {
                val tokenStart = i + 7
                var p = tokenStart
                while (p < n && raw[p] != ')' && !isRegexWhitespace(raw[p])) {
                    p++
                }
                if (p > tokenStart && p < n && raw[p] == ')') {
                    onMatch(i, p + 1, raw.substring(i, p + 1), raw.substring(tokenStart, p))
                    i = p + 1
                    continue
                } else {
                    i = p
                    continue
                }
            }
        }
        i++
    }
}

private fun replaceMarkdownUrlLinksLinear(
    raw: String,
    transform: (matchValue: String, label: String, targetUrl: String) -> String
): String {
    if (!raw.contains('[')) return raw
    val scanner = MarkdownLinkScanner(raw)
    val sb = StringBuilder(raw.length)
    var lastIndex = 0
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '[') {
            val match = scanner.tryMatchAt(i, allowSubOrUser = false)
            if (match != null && match.isUrl) {
                if (match.start > lastIndex) {
                    sb.append(raw, lastIndex, match.start)
                }
                sb.append(transform(raw.substring(match.start, match.endExclusive), match.label, match.target))
                lastIndex = match.endExclusive
                i = match.endExclusive
                continue
            }
        }
        i++
    }
    if (lastIndex == 0) return raw
    if (lastIndex < raw.length) {
        sb.append(raw, lastIndex, raw.length)
    }
    return sb.toString()
}

internal fun normalizeRedditEmbedTokensForDisplay(
    raw: String,
    stripForInlineImages: Boolean,
    unwrapRedditMedia: Boolean,
    mediaMetadata: Map<String, MediaMetadata>?,
    inlineImageUrls: List<String> = emptyList()
): String {
    var result = raw
    if (result.contains("![")) {
        val sb = StringBuilder(result.length)
        var lastIndex = 0
        forEachRedditEmbedToken(result) { start, end, rawMatch, token ->
            if (start > lastIndex) {
                sb.append(result, lastIndex, start)
            }
            val resolved = resolveRedditEmbedTokenUrl(token, mediaMetadata)
            val effectiveResolved = if (resolved != null && unwrapRedditMedia) {
                MediaResolver.unwrapRedditMediaUrl(resolved)
            } else {
                resolved
            }
            val replacement = when {
                stripForInlineImages && effectiveResolved != null -> {
                    val isRenderedInline = effectiveResolved in inlineImageUrls ||
                        (resolved != null && resolved in inlineImageUrls)
                    if (isRenderedInline) {
                        ""
                    } else if (rawMatch.startsWith("![gif]", ignoreCase = true)) {
                        "[GIF]($effectiveResolved)"
                    } else {
                        "[Image]($effectiveResolved)"
                    }
                }
                !stripForInlineImages && resolved != null -> "[GIF]($resolved)"
                else -> rawMatch
            }
            sb.append(replacement)
            lastIndex = end
        }
        if (lastIndex > 0) {
            if (lastIndex < result.length) {
                sb.append(result, lastIndex, result.length)
            }
            result = sb.toString()
        }
    }

    if (stripForInlineImages && inlineImageUrls.isNotEmpty()) {
        val inlineKeySet = inlineImageUrls.map { it.substringBefore('?').lowercase(Locale.US) }.toSet()

        fun isInlineMediaMatch(url: String): Boolean {
            val resolved = resolveInlineMediaUrl(url, unwrapRedditMedia) ?: return false
            val resolvedLower = resolved.lowercase(Locale.US)
            return resolved in inlineImageUrls ||
                resolvedLower.substringBefore('?') in inlineKeySet
        }

        // Remove markdown links whose target URL is already rendered inline; preserve descriptive caption if non-URL
        result = replaceMarkdownUrlLinksLinear(result) { matchValue, label, targetUrl ->
            if (isInlineMediaMatch(targetUrl)) {
                val trimmedLabel = label.trim()
                val labelLower = trimmedLabel.lowercase(Locale.US)
                val looksLikeRawOrGenericLabel = trimmedLabel.startsWith("http://", ignoreCase = true) ||
                    trimmedLabel.startsWith("https://", ignoreCase = true) ||
                    labelLower in setOf("image", "img", "gif", "[gif]", "[image]", "preview.redd.it", "i.redd.it")
                if (looksLikeRawOrGenericLabel) "" else trimmedLabel
            } else {
                matchValue
            }
        }

        // Remove standalone bare URLs that are already rendered inline
        result = INLINE_BARE_URL_REGEX.replace(result) { match ->
            if (isInlineMediaMatch(match.value)) "" else match.value
        }
    }

    return result.lines().joinToString("\n") { it.trimEnd() }.trim()
}

internal fun extractInlineImageUrls(
    raw: String,
    unwrapRedditMedia: Boolean,
    mediaMetadata: Map<String, MediaMetadata>?
): List<String> {
    val urls = mutableListOf<String>()

    // 1. Parse Reddit markdown comment media embeds: ![gif](giphy|<id>) and ![img](<media_id>)
    if (raw.contains("![")) {
        forEachRedditEmbedToken(raw) { _, _, _, token ->
            val resolved = resolveRedditEmbedTokenUrl(token, mediaMetadata)
            if (!resolved.isNullOrBlank()) {
                urls += if (unwrapRedditMedia) MediaResolver.unwrapRedditMediaUrl(resolved) else resolved
            }
        }
    }

    // 2. Parse direct HTTPS image/GIPHY URLs
    for (m in INLINE_BARE_URL_REGEX.findAll(raw)) {
        resolveInlineMediaUrl(m.value, unwrapRedditMedia)?.let { urls += it }
    }
    return urls.distinct().take(4)
}

private fun findClosingDoubleDelimiter(
    segment: String,
    startIndex: Int,
    delim: Char
): Int {
    val n = segment.length
    var k = startIndex
    while (k + 1 < n) {
        val c = segment[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 2 < n && segment[k + 1] != '\n') {
            k += 2
            continue
        }
        if (c == delim && segment[k + 1] == delim) {
            if (delim == '_' && (k + 2 < n && segment[k + 2].isLetterOrDigit())) {
                k++
                continue
            }
            if (k > startIndex && segment[k - 1].isWhitespace()) {
                k++
                continue
            }
            return k + 2
        }
        k++
    }
    return -1
}

private fun findClosingTripleDelimiter(
    segment: String,
    startIndex: Int,
    delim: Char
): Int {
    val n = segment.length
    var k = startIndex
    while (k + 2 < n) {
        val c = segment[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 3 < n && segment[k + 1] != '\n') {
            k += 2
            continue
        }
        if (c == delim && segment[k + 1] == delim && segment[k + 2] == delim) {
            if (delim == '_' && (k + 3 < n && segment[k + 3].isLetterOrDigit())) {
                k++
                continue
            }
            if (k > startIndex && segment[k - 1].isWhitespace()) {
                k++
                continue
            }
            return k + 3
        }
        k++
    }
    return -1
}

private fun findClosingSingleDelimiter(
    segment: String,
    startIndex: Int,
    delim: Char
): Int {
    val n = segment.length
    var k = startIndex
    while (k < n) {
        val c = segment[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 1 < n && segment[k + 1] != '\n') {
            k += 2
            continue
        }
        if (c == delim) {
            // Skip over double delimiter inside italic (e.g., *italic **bold** italic*)
            if (k + 1 < n && segment[k + 1] == delim) {
                k += 2
                continue
            }
            if (delim == '_' && k + 1 < n && segment[k + 1].isLetterOrDigit()) {
                k++
                continue
            }
            if (k > startIndex && segment[k - 1].isWhitespace()) {
                k++
                continue
            }
            return k + 1
        }
        k++
    }
    return -1
}

private fun findClosingBacktickRun(
    segment: String,
    startIndex: Int,
    runLen: Int
): Int {
    val n = segment.length
    var k = startIndex
    while (k < n) {
        if (segment[k] == '\n') return -1
        if (segment[k] == '`') {
            var count = 0
            while (k + count < n && segment[k + count] == '`') {
                count++
            }
            if (count == runLen) {
                return k + count
            }
            k += count
            continue
        }
        k++
    }
    return -1
}

internal fun buildRedditMarkdownAnnotatedString(
    raw: String,
    baseColor: Color,
    revealedSpoilers: Map<Int, Boolean>,
    spoilerIdStart: Int,
    linkIdStart: Int = 0,
    fixBrokenMarkdownLinks: Boolean = true,
    fixShareLinks: Boolean = true,
    linkColor: Color = RwfPalette.CyanLinkDark,
    codeBgColor: Color = RwfPalette.DarkGraySurface,
    codeTextColor: Color = RwfPalette.TitleUnreadDark,
    spoilerRevealedBgColor: Color = RwfPalette.FlairBackgroundDark
): ParsedMarkdownBlock {
    var nextSpoilerId = spoilerIdStart
    var spoilerCount = 0
    var nextLinkId = linkIdStart
    val collectedLinks = mutableListOf<MarkdownLinkItem>()

    val annotated = buildAnnotatedString {
        fun appendParsedSegment(segment: String, depth: Int, insideLink: Boolean) {
            val n = segment.length
            val linkScanner = if (!insideLink) MarkdownLinkScanner(segment) else null
            var noSpoilerCloseUntil = -1
            var noBacktickCloseUntil = -1
            var noTripleStarCloseUntil = -1
            var noTripleUnderCloseUntil = -1
            var noDoubleStarCloseUntil = -1
            var noDoubleUnderCloseUntil = -1
            var noTildeCloseUntil = -1
            var noStarCloseUntil = -1
            var noUnderCloseUntil = -1
            var noSuperParenCloseUntil = -1

            var plainStart = 0
            var i = 0

            fun flushPlainUpTo(end: Int) {
                if (end > plainStart) {
                    append(segment.substring(plainStart, end))
                }
            }

            fun appendLinkStyled(
                item: MarkdownLinkItem,
                displayRaw: String,
                parseDisplayMarkdown: Boolean
            ) {
                val linkIdx = nextLinkId++
                collectedLinks.add(item)
                pushStringAnnotation("LINK_INDEX", linkIdx.toString())
                if (!item.subreddit.isNullOrBlank()) {
                    pushStringAnnotation("SUB", item.subreddit)
                }
                if (!item.username.isNullOrBlank()) {
                    pushStringAnnotation("USER", item.username)
                }
                pushStringAnnotation("URL", item.url)
                pushStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                )
                if (parseDisplayMarkdown && depth < MAX_INLINE_DEPTH) {
                    appendParsedSegment(displayRaw, depth + 1, insideLink = true)
                } else {
                    append(displayRaw)
                }
                pop()
                pop()
                if (!item.username.isNullOrBlank()) pop()
                if (!item.subreddit.isNullOrBlank()) pop()
                pop()
            }

            while (i < n) {
                val ch = segment[i]

                // 0. Backslash escape for markdown punctuation
                if (ch == '\\' && i + 1 < n && segment[i + 1] in ESCAPABLE_MARKDOWN_CHARS) {
                    flushPlainUpTo(i)
                    append(segment[i + 1])
                    i += 2
                    plainStart = i
                    continue
                }

                // 1. Spoiler: >!.+?!<
                if (ch == '>' && i + 4 < n && segment[i + 1] == '!' && i >= noSpoilerCloseUntil) {
                    val firstInner = segment[i + 2]
                    if (firstInner != '\n' && firstInner != '\r') {
                        var k = i + 3
                        var foundEnd = -1
                        while (k < n) {
                            val c = segment[k]
                            if (c == '\n' || c == '\r') break
                            if (c == '!' && k + 1 < n && segment[k + 1] == '<') {
                                foundEnd = k + 2
                                break
                            }
                            k++
                        }
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val sid = nextSpoilerId++
                            spoilerCount++
                            val revealed = revealedSpoilers[sid] == true
                            val inner = segment.substring(i + 2, foundEnd - 2)
                            pushStringAnnotation("SPOILER", sid.toString())
                            if (revealed) {
                                pushStyle(
                                    SpanStyle(
                                        color = baseColor,
                                        background = spoilerRevealedBgColor
                                    )
                                )
                                if (depth < MAX_INLINE_DEPTH) {
                                    appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                                } else {
                                    append(inner)
                                }
                                pop()
                            } else {
                                pushStyle(
                                    SpanStyle(
                                        color = Color(0xFF6A6A6A),
                                        background = Color(0xFF6A6A6A)
                                    )
                                )
                                append(inner)
                                pop()
                            }
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noSpoilerCloseUntil = k
                        }
                    }
                }

                // 2 & 3. Markdown links: [label](https://...), [label](/r/sub), [label](/u/user), [label](/path)
                if (!insideLink && ch == '[' && linkScanner != null) {
                    val linkMatch = linkScanner.tryMatchAt(i, allowSubOrUser = true)
                    if (linkMatch != null) {
                        flushPlainUpTo(i)
                        when (linkMatch.kind) {
                            MarkdownLinkKind.URL -> {
                                val url = if (fixShareLinks) {
                                    MediaResolver.unwrapRedditMediaUrl(linkMatch.target)
                                } else {
                                    linkMatch.target
                                }
                                appendLinkStyled(
                                    item = MarkdownLinkItem(label = linkMatch.label, url = url),
                                    displayRaw = linkMatch.label,
                                    parseDisplayMarkdown = true
                                )
                            }
                            MarkdownLinkKind.SUB -> {
                                if (fixBrokenMarkdownLinks) {
                                    val subName = linkMatch.target.trimStart('/').substring(2)
                                    appendLinkStyled(
                                        item = MarkdownLinkItem(
                                            label = linkMatch.label,
                                            url = "https://www.reddit.com/r/$subName",
                                            subreddit = subName
                                        ),
                                        displayRaw = linkMatch.label,
                                        parseDisplayMarkdown = true
                                    )
                                } else {
                                    append(segment.substring(linkMatch.start, linkMatch.endExclusive))
                                }
                            }
                            MarkdownLinkKind.USER -> {
                                if (fixBrokenMarkdownLinks) {
                                    val cleanTarget = linkMatch.target.trimStart('/')
                                    val userName = if (cleanTarget.startsWith("user/", ignoreCase = true)) {
                                        cleanTarget.substring(5)
                                    } else {
                                        cleanTarget.substring(2)
                                    }
                                    appendLinkStyled(
                                        item = MarkdownLinkItem(
                                            label = linkMatch.label,
                                            url = "https://www.reddit.com/user/$userName",
                                            username = userName
                                        ),
                                        displayRaw = linkMatch.label,
                                        parseDisplayMarkdown = true
                                    )
                                } else {
                                    append(segment.substring(linkMatch.start, linkMatch.endExclusive))
                                }
                            }
                            MarkdownLinkKind.OLD_SPOILER -> {
                                val sid = nextSpoilerId++
                                spoilerCount++
                                val revealed = revealedSpoilers[sid] == true
                                val secret = linkMatch.spoilerTitle.orEmpty()
                                if (linkMatch.label.isNotBlank()) {
                                    append(linkMatch.label)
                                    append(" ")
                                }
                                pushStringAnnotation("SPOILER", sid.toString())
                                if (revealed) {
                                    pushStyle(
                                        SpanStyle(
                                            color = baseColor,
                                            background = spoilerRevealedBgColor
                                        )
                                    )
                                    append(secret)
                                    pop()
                                } else {
                                    pushStyle(
                                        SpanStyle(
                                            color = Color(0xFF6A6A6A),
                                            background = Color(0xFF6A6A6A)
                                        )
                                    )
                                    append(secret)
                                    pop()
                                }
                                pop()
                            }
                        }
                        i = linkMatch.endExclusive
                        plainStart = i
                        continue
                    }
                }

                // 4. Inline code: `code` or ``code``
                if (ch == '`' && i >= noBacktickCloseUntil) {
                    var runLen = 1
                    while (i + runLen < n && segment[i + runLen] == '`') {
                        runLen++
                    }
                    val foundEnd = findClosingBacktickRun(segment, i + runLen, runLen)
                    if (foundEnd != -1) {
                        val rawCode = segment.substring(i + runLen, foundEnd - runLen)
                        val codeText = if (runLen > 1) rawCode.trim() else rawCode
                        if (codeText.isNotEmpty()) {
                            flushPlainUpTo(i)
                            pushStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = codeBgColor,
                                    color = codeTextColor
                                )
                            )
                            append(codeText)
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        }
                    } else {
                        noBacktickCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                    }
                }

                // 5. Bold + Italic: ***text*** or ___text___
                if (ch == '*' && i + 5 < n && segment[i + 1] == '*' && segment[i + 2] == '*' &&
                    i >= noTripleStarCloseUntil
                ) {
                    val firstInner = segment[i + 3]
                    if (firstInner != '*' && firstInner != '\n' && !firstInner.isWhitespace()) {
                        val foundEnd = findClosingTripleDelimiter(segment, i + 3, '*')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 3, foundEnd - 3)
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            pushStyle(italicSpanStyle())
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noTripleStarCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                if (ch == '_' && i + 5 < n && segment[i + 1] == '_' && segment[i + 2] == '_' &&
                    i >= noTripleUnderCloseUntil && (i == 0 || !segment[i - 1].isLetterOrDigit())
                ) {
                    val firstInner = segment[i + 3]
                    if (firstInner != '_' && firstInner != '\n' && !firstInner.isWhitespace()) {
                        val foundEnd = findClosingTripleDelimiter(segment, i + 3, '_')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 3, foundEnd - 3)
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            pushStyle(italicSpanStyle())
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noTripleUnderCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                // 6. Bold: **text** or __text__
                if (ch == '*' && i + 4 < n && segment[i + 1] == '*' && i >= noDoubleStarCloseUntil) {
                    val firstInner = segment[i + 2]
                    if (firstInner != '*' && firstInner != '\n' && !firstInner.isWhitespace()) {
                        val foundEnd = findClosingDoubleDelimiter(segment, i + 2, '*')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 2, foundEnd - 2)
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noDoubleStarCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                if (ch == '_' && i + 4 < n && segment[i + 1] == '_' && i >= noDoubleUnderCloseUntil &&
                    (i == 0 || !segment[i - 1].isLetterOrDigit())
                ) {
                    val firstInner = segment[i + 2]
                    if (firstInner != '_' && firstInner != '\n' && !firstInner.isWhitespace()) {
                        val foundEnd = findClosingDoubleDelimiter(segment, i + 2, '_')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 2, foundEnd - 2)
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noDoubleUnderCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                // 7. Strikethrough: ~~text~~
                if (ch == '~' && i + 4 < n && segment[i + 1] == '~' && i >= noTildeCloseUntil) {
                    val firstInner = segment[i + 2]
                    if (firstInner != '~' && firstInner != '\n') {
                        val foundEnd = findClosingDoubleDelimiter(segment, i + 2, '~')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 2, foundEnd - 2)
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noTildeCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                // 8. Italic: *text* or _text_
                if (ch == '*' && i >= noStarCloseUntil) {
                    if (i + 2 < n && segment[i + 1] != '*' && segment[i + 1] != '\n' && !segment[i + 1].isWhitespace()) {
                        val foundEnd = findClosingSingleDelimiter(segment, i + 1, '*')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 1, foundEnd - 1)
                            pushStyle(italicSpanStyle())
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noStarCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                if (ch == '_' && i >= noUnderCloseUntil && (i == 0 || !segment[i - 1].isLetterOrDigit())) {
                    if (i + 2 < n && segment[i + 1] != '_' && segment[i + 1] != '\n' && !segment[i + 1].isWhitespace()) {
                        val foundEnd = findClosingSingleDelimiter(segment, i + 1, '_')
                        if (foundEnd != -1) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(i + 1, foundEnd - 1)
                            pushStyle(italicSpanStyle())
                            if (depth < MAX_INLINE_DEPTH) {
                                appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                            } else {
                                append(inner)
                            }
                            pop()
                            i = foundEnd
                            plainStart = i
                            continue
                        } else {
                            noUnderCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    }
                }

                // 9. Superscript: ^(multi word) or ^word (chained ^^word)
                if (ch == '^' && i + 1 < n) {
                    var carets = 0
                    while (i + carets < n && segment[i + carets] == '^' && carets < 4) {
                        carets++
                    }
                    val afterCaret = i + carets
                    if (afterCaret < n && segment[afterCaret] == '(' && i >= noSuperParenCloseUntil) {
                        var k = afterCaret + 1
                        var parenDepth = 1
                        while (k < n && segment[k] != '\n') {
                            if (segment[k] == '(') parenDepth++
                            else if (segment[k] == ')') {
                                parenDepth--
                                if (parenDepth == 0) break
                            }
                            k++
                        }
                        if (k < n && segment[k] == ')' && parenDepth == 0) {
                            val inner = segment.substring(afterCaret + 1, k)
                            if (inner.isNotEmpty()) {
                                flushPlainUpTo(i)
                                val scale = if (carets >= 2) 0.68.em else 0.78.em
                                pushStyle(
                                    SpanStyle(
                                        baselineShift = BaselineShift.Superscript,
                                        fontSize = scale
                                    )
                                )
                                if (depth < MAX_INLINE_DEPTH) {
                                    appendParsedSegment(inner, depth + 1, insideLink = insideLink)
                                } else {
                                    append(inner)
                                }
                                pop()
                                i = k + 1
                                plainStart = i
                                continue
                            }
                        } else {
                            noSuperParenCloseUntil = segment.indexOf('\n', i).let { if (it == -1) n else it }
                        }
                    } else if (afterCaret < n && !isRegexWhitespace(segment[afterCaret]) && segment[afterCaret] != '^') {
                        var k = afterCaret
                        while (k < n && !isRegexWhitespace(segment[k]) && segment[k] != ')' && segment[k] != ']' && segment[k] != '<' && segment[k] != '>') {
                            k++
                        }
                        if (k > afterCaret) {
                            flushPlainUpTo(i)
                            val inner = segment.substring(afterCaret, k)
                            val scale = if (carets >= 2) 0.68.em else 0.78.em
                            pushStyle(
                                SpanStyle(
                                    baselineShift = BaselineShift.Superscript,
                                    fontSize = scale
                                )
                            )
                            append(inner)
                            pop()
                            i = k
                            plainStart = i
                            continue
                        }
                    }
                }

                // 10. Bare URL: https?://...
                if (!insideLink && ch == 'h' && (i == 0 || !segment[i - 1].isLetterOrDigit())) {
                    val schemeLen = when {
                        segment.startsWith("https://", i) -> 8
                        segment.startsWith("http://", i) -> 7
                        else -> 0
                    }
                    if (schemeLen > 0) {
                        var k = i + schemeLen
                        var parenDepth = 0
                        while (k < n && !isRegexWhitespace(segment[k]) &&
                            segment[k] != '<' && segment[k] != '>' &&
                            segment[k] != '[' && segment[k] != ']' &&
                            segment[k] != '"' && segment[k] != '\''
                        ) {
                            if (segment[k] == '(') {
                                parenDepth++
                            } else if (segment[k] == ')') {
                                if (parenDepth == 0) break
                                parenDepth--
                            }
                            k++
                        }
                        while (k > i + schemeLen && segment[k - 1] in charArrayOf('.', ',', ';', ':', '!', '?')) {
                            k--
                        }
                        if (k > i + schemeLen) {
                            flushPlainUpTo(i)
                            val rawUrl = segment.substring(i, k)
                            val cleanedUrl = cleanRedditUrlEscapes(rawUrl)
                            appendLinkStyled(
                                item = MarkdownLinkItem(label = rawUrl, url = cleanedUrl),
                                displayRaw = rawUrl,
                                parseDisplayMarkdown = false
                            )
                            i = k
                            plainStart = i
                            continue
                        }
                    }
                }

                // 11. Bare /?r/sub and /?u/user with (?<!\w)
                if (!insideLink && (ch == '/' || ch == 'r' || ch == 'u') && (i == 0 || !isRegexWordChar(segment[i - 1]))) {
                    val hasSlash = ch == '/'
                    val prefixOffset = if (hasSlash) i + 1 else i
                    if (prefixOffset + 2 < n && segment[prefixOffset + 1] == '/') {
                        val kind = segment[prefixOffset]
                        if (kind == 'r') {
                            var k = prefixOffset + 2
                            while (k < n && isRegexWordChar(segment[k])) {
                                k++
                            }
                            if (k > prefixOffset + 2) {
                                flushPlainUpTo(i)
                                val token = segment.substring(i, k)
                                val subName = segment.substring(prefixOffset + 2, k)
                                appendLinkStyled(
                                    item = MarkdownLinkItem(
                                        label = token,
                                        url = "https://www.reddit.com/r/$subName",
                                        subreddit = subName
                                    ),
                                    displayRaw = token,
                                    parseDisplayMarkdown = false
                                )
                                i = k
                                plainStart = i
                                continue
                            }
                        } else if (kind == 'u') {
                            var k = prefixOffset + 2
                            while (k < n && isRedditNameChar(segment[k])) {
                                k++
                            }
                            if (k > prefixOffset + 2) {
                                flushPlainUpTo(i)
                                val token = segment.substring(i, k)
                                val userName = segment.substring(prefixOffset + 2, k)
                                appendLinkStyled(
                                    item = MarkdownLinkItem(
                                        label = token,
                                        url = "https://www.reddit.com/user/$userName",
                                        username = userName
                                    ),
                                    displayRaw = token,
                                    parseDisplayMarkdown = false
                                )
                                i = k
                                plainStart = i
                                continue
                            }
                        }
                    }
                }

                i++
            }

            flushPlainUpTo(n)
        }

        appendParsedSegment(raw, depth = 0, insideLink = false)
    }
    return ParsedMarkdownBlock(
        annotatedString = annotated,
        spoilerCount = spoilerCount,
        links = collectedLinks.toList()
    )
}

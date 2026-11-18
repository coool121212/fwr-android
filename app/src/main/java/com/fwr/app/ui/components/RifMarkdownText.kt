package com.fwr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fwr.app.data.model.MediaMetadata
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import java.util.Locale

internal data class MarkdownBlock(
    val isQuote: Boolean,
    val lines: List<String>
)

internal data class ParsedMarkdownBlock(
    val annotatedString: AnnotatedString,
    val spoilerCount: Int
)

private val GIPHY_EMBED_TOKEN_REGEX = Regex(
    """^giphy\|([A-Za-z0-9_-]+)(?:\|.*)?$""",
    RegexOption.IGNORE_CASE
)

internal fun splitMarkdownBlocks(text: String): List<MarkdownBlock> {
    val rawLines = text.replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&").lines()
    val grouped = mutableListOf<MarkdownBlock>()
    var currentIsQuote: Boolean? = null
    var currentLines: MutableList<String>? = null
    for (line in rawLines) {
        val trimmed = line.trimStart()
        val isQuoteLine = trimmed.startsWith(">") && !trimmed.startsWith(">!")
        val contentLine = if (isQuoteLine) trimmed.removePrefix(">").trimStart() else line
        val activeLines = currentLines
        if (activeLines != null && currentIsQuote == isQuoteLine) {
            activeLines.add(contentLine)
        } else {
            if (activeLines != null && currentIsQuote != null) {
                grouped.add(MarkdownBlock(isQuote = currentIsQuote, lines = activeLines.toList()))
            }
            currentIsQuote = isQuoteLine
            currentLines = mutableListOf(contentLine)
        }
    }
    val finalLines = currentLines
    val finalIsQuote = currentIsQuote
    if (finalLines != null && finalIsQuote != null) {
        grouped.add(MarkdownBlock(isQuote = finalIsQuote, lines = finalLines.toList()))
    }
    return grouped
}

/**
 * Clean-room Reddit Markdown renderer supporting:
 * - Blockquotes (`> quoted text`) with left vertical accent bar
 * - Bold (`**text**`), Italic (`*text*`), Strikethrough (`~~text~~`), Inline Code (`` `code` ``)
 * - Links (`[label](https://...)`, bare `https://...`, `/r/subreddit`, `/u/user`)
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
    onSubredditClick: ((String) -> Unit)? = null,
    onUserClick: ((String) -> Unit)? = null,
    onNonLinkClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val revealedSpoilers = remember(text) { mutableStateMapOf<Int, Boolean>() }
    var spoilerRevealVersion by remember(text) { mutableIntStateOf(0) }

    val quoteColor = RwfPalette.MetadataTextDark
    val linkColor = RwfPalette.CyanLinkDark
    val codeBgColor = RwfPalette.DarkGraySurface
    val codeTextColor = RwfPalette.TitleUnreadDark
    val spoilerRevealedBgColor = RwfPalette.FlairBackgroundDark
    val quoteBarColor = RwfPalette.FlairBackgroundDark
    val placeholderPainter = remember(codeBgColor) { ColorPainter(codeBgColor) }

    val blocks = remember(text) { splitMarkdownBlocks(text) }

    val markdownBody: @Composable () -> Unit = {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            var spoilerCounter = 0
            blocks.forEach { block ->
                val blockText = block.lines.joinToString("\n").trimEnd()
                if (blockText.isEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    return@forEach
                }
                val inlineImageUrls = remember(
                    blockText,
                    mediaMetadata,
                    showInlineImages
                ) {
                    if (!showInlineImages) {
                        emptyList()
                    } else {
                        extractInlineImageUrls(
                            raw = blockText,
                            unwrapRedditMedia = true,
                            mediaMetadata = mediaMetadata
                        )
                    }
                }
                val displayText = remember(
                    blockText,
                    mediaMetadata,
                    inlineImageUrls,
                    showInlineImages
                ) {
                    normalizeRedditEmbedTokensForDisplay(
                        raw = blockText,
                        stripForInlineImages = showInlineImages,
                        unwrapRedditMedia = true,
                        mediaMetadata = mediaMetadata,
                        inlineImageUrls = inlineImageUrls
                    )
                }
                val baseColor = if (block.isQuote) quoteColor else color
                val startSpoilerId = spoilerCounter
                val parsedBlock = remember(
                    displayText,
                    baseColor,
                    linkColor,
                    codeBgColor,
                    codeTextColor,
                    spoilerRevealedBgColor,
                    startSpoilerId,
                    spoilerRevealVersion
                ) {
                    if (displayText.isNotEmpty()) {
                        buildRedditMarkdownAnnotatedString(
                            raw = displayText,
                            baseColor = baseColor,
                            revealedSpoilers = revealedSpoilers,
                            spoilerIdStart = startSpoilerId,
                            fixBrokenMarkdownLinks = true,
                            fixShareLinks = true,
                            linkColor = linkColor,
                            codeBgColor = codeBgColor,
                            codeTextColor = codeTextColor,
                            spoilerRevealedBgColor = spoilerRevealedBgColor
                        )
                    } else {
                        ParsedMarkdownBlock(AnnotatedString(""), 0)
                    }
                }
                spoilerCounter += parsedBlock.spoilerCount
                val annotated = parsedBlock.annotatedString

                val clickableBlock: @Composable () -> Unit = {
                    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                    fun handleTapPosition(pos: Offset): Boolean {
                        val offset = layoutResult?.getOffsetForPosition(pos)
                        if (offset == null) {
                            onNonLinkClick?.invoke()
                            return false
                        }

                        val spoilerAnn = annotated.getStringAnnotations("SPOILER", offset, offset).firstOrNull()
                        if (spoilerAnn != null) {
                            val sid = spoilerAnn.item.toIntOrNull()
                            if (sid != null) {
                                revealedSpoilers[sid] = !(revealedSpoilers[sid] ?: false)
                                spoilerRevealVersion++
                            }
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

                        onNonLinkClick?.invoke()
                        return false
                    }

                    Text(
                        text = annotated,
                        style = TextStyle(
                            color = if (block.isQuote) RwfPalette.MetadataTextDark else color,
                            fontSize = fontSize,
                            lineHeight = lineHeight
                        ),
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier.pointerInput(
                            annotated,
                            selectable,
                            onSubredditClick,
                            onUserClick,
                            onNonLinkClick,
                            onLongClick
                        ) {
                            if (selectable && onLongClick == null) {
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
                                        { onLongClick() }
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

                if (displayText.isNotBlank()) {
                    if (block.isQuote) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .drawBehind {
                                    drawRoundRect(
                                        color = quoteBarColor,
                                        size = Size(3.dp.toPx(), size.height),
                                        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                                    )
                                }
                                .padding(start = 11.dp)
                        ) {
                            clickableBlock()
                        }
                    } else {
                        clickableBlock()
                    }
                }

                if (inlineImageUrls.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = if (displayText.isNotBlank()) 4.dp else 0.dp)
                    ) {
                        inlineImageUrls.forEach { imageUrl ->
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

private val INLINE_IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
private val GIPHY_DIRECT_MEDIA_REGEX = Regex(
    """https?://(?:media\d*\.)?giphy\.com/(?:gifs/(?:[^/]+-)?|media/)([A-Za-z0-9]+)""",
    RegexOption.IGNORE_CASE
)
private val INLINE_BARE_URL_REGEX = Regex("""https?://[^\s<>\)\]]+""")

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

private data class MarkdownLinkMatch(
    val start: Int,
    val endExclusive: Int,
    val label: String,
    val target: String,
    val isUrl: Boolean
)

private class MarkdownLinkScanner(private val raw: String) {
    private var failedBracketBefore = -1
    private var failedUrlTargetBefore = -1

    fun tryMatchAt(start: Int, allowSubOrUser: Boolean): MarkdownLinkMatch? {
        val n = raw.length
        if (start >= n || raw[start] != '[' || start < failedBracketBefore) return null

        var closeBracket = start + 1
        while (closeBracket < n && raw[closeBracket] != ']' && raw[closeBracket] != '\n') {
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

        val targetStart = closeBracket + 2
        // Check (https?://[^)\s]+)
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
            var lastCloseBracketInScan = closeBracket
            while (p < n && raw[p] != ')' && !isRegexWhitespace(raw[p])) {
                if (raw[p] == ']') {
                    lastCloseBracketInScan = p
                }
                p++
            }
            if (p > targetStart + schemeLen && p < n && raw[p] == ')') {
                return MarkdownLinkMatch(
                    start = start,
                    endExclusive = p + 1,
                    label = raw.substring(start + 1, closeBracket),
                    target = raw.substring(targetStart, p),
                    isUrl = true
                )
            }
            failedUrlTargetBefore = maxOf(failedUrlTargetBefore, p)
            failedBracketBefore = maxOf(
                failedBracketBefore,
                if (p == n || raw[p] == '\n') p else lastCloseBracketInScan + 1
            )
            return null
        }

        // Check (/?(?:r|u)/[A-Za-z0-9_-]+)/?
        if (allowSubOrUser) {
            var p = targetStart
            if (p < n && raw[p] == '/') p++
            if (p + 1 < n && (raw[p] == 'r' || raw[p] == 'u') && raw[p + 1] == '/') {
                p += 2
                val nameStart = p
                while (p < n && isRedditNameChar(raw[p])) {
                    p++
                }
                if (p > nameStart) {
                    val targetEnd = p
                    if (p < n && raw[p] == '/') p++
                    if (p < n && raw[p] == ')') {
                        return MarkdownLinkMatch(
                            start = start,
                            endExclusive = p + 1,
                            label = raw.substring(start + 1, closeBracket),
                            target = raw.substring(targetStart, targetEnd),
                            isUrl = false
                        )
                    }
                }
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

internal fun buildRedditMarkdownAnnotatedString(
    raw: String,
    baseColor: Color,
    revealedSpoilers: Map<Int, Boolean>,
    spoilerIdStart: Int,
    fixBrokenMarkdownLinks: Boolean = true,
    fixShareLinks: Boolean = true,
    linkColor: Color = RwfPalette.CyanLinkDark,
    codeBgColor: Color = RwfPalette.DarkGraySurface,
    codeTextColor: Color = RwfPalette.TitleUnreadDark,
    spoilerRevealedBgColor: Color = RwfPalette.FlairBackgroundDark
): ParsedMarkdownBlock {
    var nextSpoilerId = spoilerIdStart
    var spoilerCount = 0
    val n = raw.length
    val linkScanner = MarkdownLinkScanner(raw)
    var noSpoilerCloseUntil = -1
    var noBacktickCloseUntil = -1
    var noDoubleStarCloseUntil = -1
    var noTildeCloseUntil = -1
    var noStarCloseUntil = -1

    val annotated = buildAnnotatedString {
        var plainStart = 0
        var i = 0

        fun flushPlainUpTo(end: Int) {
            if (end > plainStart) {
                append(raw.substring(plainStart, end))
            }
        }

        while (i < n) {
            val ch = raw[i]

            // 1. Spoiler: >!.+?!<
            if (ch == '>' && i + 4 < n && raw[i + 1] == '!' && i >= noSpoilerCloseUntil) {
                val firstInner = raw[i + 2]
                if (firstInner != '\n' && firstInner != '\r') {
                    var k = i + 3
                    var foundEnd = -1
                    while (k < n) {
                        val c = raw[k]
                        if (c == '\n' || c == '\r') break
                        if (c == '!' && k + 1 < n && raw[k + 1] == '<') {
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
                        val inner = raw.substring(i + 2, foundEnd - 2)
                        pushStringAnnotation("SPOILER", sid.toString())
                        if (revealed) {
                            pushStyle(
                                SpanStyle(
                                    color = baseColor,
                                    background = spoilerRevealedBgColor
                                )
                            )
                            append(inner)
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

            // 2 & 3. Markdown links: [label](https://...) or [label](/?(r|u)/...)
            if (ch == '[') {
                val linkMatch = linkScanner.tryMatchAt(i, allowSubOrUser = true)
                if (linkMatch != null) {
                    flushPlainUpTo(i)
                    if (linkMatch.isUrl) {
                        val url = if (fixShareLinks) MediaResolver.unwrapRedditMediaUrl(linkMatch.target) else linkMatch.target
                        pushStringAnnotation("URL", url)
                        pushStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                        append(linkMatch.label)
                        pop()
                        pop()
                    } else if (fixBrokenMarkdownLinks) {
                        val target = linkMatch.target.trimStart('/')
                        if (target.startsWith("r/", ignoreCase = true)) {
                            val subName = target.substring(2)
                            pushStringAnnotation("SUB", subName)
                            pushStringAnnotation("URL", "https://www.reddit.com/r/$subName")
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                            append(linkMatch.label)
                            pop()
                            pop()
                            pop()
                        } else {
                            val userName = target.substring(2)
                            pushStringAnnotation("USER", userName)
                            pushStringAnnotation("URL", "https://www.reddit.com/user/$userName")
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                            append(linkMatch.label)
                            pop()
                            pop()
                            pop()
                        }
                    } else {
                        append(raw.substring(linkMatch.start, linkMatch.endExclusive))
                    }
                    i = linkMatch.endExclusive
                    plainStart = i
                    continue
                }
            }

            // 4. Inline code: `[^`\n]+`
            if (ch == '`' && i >= noBacktickCloseUntil) {
                if (i + 2 < n && raw[i + 1] != '`' && raw[i + 1] != '\n') {
                    var k = i + 2
                    var foundEnd = -1
                    while (k < n) {
                        val c = raw[k]
                        if (c == '\n') break
                        if (c == '`') {
                            foundEnd = k + 1
                            break
                        }
                        k++
                    }
                    if (foundEnd != -1) {
                        flushPlainUpTo(i)
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBgColor,
                                color = codeTextColor
                            )
                        )
                        append(raw.substring(i + 1, foundEnd - 1))
                        pop()
                        i = foundEnd
                        plainStart = i
                        continue
                    } else {
                        noBacktickCloseUntil = k
                    }
                }
            }

            // 5. Bold: **[^*\n]+**
            if (ch == '*' && i + 4 < n && raw[i + 1] == '*' && i >= noDoubleStarCloseUntil) {
                val firstInner = raw[i + 2]
                if (firstInner != '*' && firstInner != '\n') {
                    var k = i + 3
                    var foundEnd = -1
                    var hitSingleStar = -1
                    while (k < n) {
                        val c = raw[k]
                        if (c == '\n') break
                        if (c == '*') {
                            if (k + 1 < n && raw[k + 1] == '*') {
                                foundEnd = k + 2
                            } else {
                                hitSingleStar = k
                            }
                            break
                        }
                        k++
                    }
                    if (foundEnd != -1) {
                        flushPlainUpTo(i)
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(raw.substring(i + 2, foundEnd - 2))
                        pop()
                        i = foundEnd
                        plainStart = i
                        continue
                    } else {
                        noDoubleStarCloseUntil = if (hitSingleStar != -1) hitSingleStar + 1 else k
                    }
                }
            }

            // 6. Strikethrough: ~~[^~\n]+~~
            if (ch == '~' && i + 4 < n && raw[i + 1] == '~' && i >= noTildeCloseUntil) {
                val firstInner = raw[i + 2]
                if (firstInner != '~' && firstInner != '\n') {
                    var k = i + 3
                    var foundEnd = -1
                    var hitSingleTilde = -1
                    while (k < n) {
                        val c = raw[k]
                        if (c == '\n') break
                        if (c == '~') {
                            if (k + 1 < n && raw[k + 1] == '~') {
                                foundEnd = k + 2
                            } else {
                                hitSingleTilde = k
                            }
                            break
                        }
                        k++
                    }
                    if (foundEnd != -1) {
                        flushPlainUpTo(i)
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(raw.substring(i + 2, foundEnd - 2))
                        pop()
                        i = foundEnd
                        plainStart = i
                        continue
                    } else {
                        noTildeCloseUntil = if (hitSingleTilde != -1) hitSingleTilde + 1 else k
                    }
                }
            }

            // 7. Italic: *[^*\n]+*
            if (ch == '*' && i >= noStarCloseUntil) {
                if (i + 2 < n && raw[i + 1] != '*' && raw[i + 1] != '\n') {
                    var k = i + 2
                    var foundEnd = -1
                    while (k < n) {
                        val c = raw[k]
                        if (c == '\n') break
                        if (c == '*') {
                            foundEnd = k + 1
                            break
                        }
                        k++
                    }
                    if (foundEnd != -1) {
                        flushPlainUpTo(i)
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(raw.substring(i + 1, foundEnd - 1))
                        pop()
                        i = foundEnd
                        plainStart = i
                        continue
                    } else {
                        noStarCloseUntil = k
                    }
                }
            }

            // 8. Bare URL: https?://[^\s<>\)\]]+
            if (ch == 'h') {
                val schemeLen = when {
                    raw.startsWith("https://", i) -> 8
                    raw.startsWith("http://", i) -> 7
                    else -> 0
                }
                if (schemeLen > 0) {
                    var k = i + schemeLen
                    while (k < n && !isRegexWhitespace(raw[k]) && raw[k] != '<' && raw[k] != '>' && raw[k] != ')' && raw[k] != ']') {
                        k++
                    }
                    if (k > i + schemeLen) {
                        flushPlainUpTo(i)
                        val url = raw.substring(i, k)
                        pushStringAnnotation("URL", url)
                        pushStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                        append(url)
                        pop()
                        pop()
                        i = k
                        plainStart = i
                        continue
                    }
                }
            }

            // 9 & 10. /?r/sub and /?u/user with (?<!\w)
            if ((ch == '/' || ch == 'r' || ch == 'u') && (i == 0 || !isRegexWordChar(raw[i - 1]))) {
                val hasSlash = ch == '/'
                val prefixOffset = if (hasSlash) i + 1 else i
                if (prefixOffset + 2 < n && raw[prefixOffset + 1] == '/') {
                    val kind = raw[prefixOffset]
                    if (kind == 'r') {
                        var k = prefixOffset + 2
                        while (k < n && isRegexWordChar(raw[k])) {
                            k++
                        }
                        if (k > prefixOffset + 2) {
                            flushPlainUpTo(i)
                            val token = raw.substring(i, k)
                            val subName = raw.substring(prefixOffset + 2, k)
                            pushStringAnnotation("SUB", subName)
                            pushStringAnnotation("URL", "https://www.reddit.com/r/$subName")
                            pushStyle(SpanStyle(color = linkColor))
                            append(token)
                            pop()
                            pop()
                            pop()
                            i = k
                            plainStart = i
                            continue
                        }
                    } else if (kind == 'u') {
                        var k = prefixOffset + 2
                        while (k < n && isRedditNameChar(raw[k])) {
                            k++
                        }
                        if (k > prefixOffset + 2) {
                            flushPlainUpTo(i)
                            val token = raw.substring(i, k)
                            val userName = raw.substring(prefixOffset + 2, k)
                            pushStringAnnotation("USER", userName)
                            pushStringAnnotation("URL", "https://www.reddit.com/user/$userName")
                            pushStyle(SpanStyle(color = linkColor))
                            append(token)
                            pop()
                            pop()
                            pop()
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
    return ParsedMarkdownBlock(annotatedString = annotated, spoilerCount = spoilerCount)
}

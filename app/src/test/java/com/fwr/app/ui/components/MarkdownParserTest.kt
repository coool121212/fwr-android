package com.fwr.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.fwr.app.data.model.MediaImageSource
import com.fwr.app.data.model.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun splitMarkdownBlocksUnescapesEntitiesAndGroupsQuotes() {
        val raw = "&gt; quoted line 1\n&gt; quoted &amp; line 2\nnormal &lt;text&gt;"
        val blocks = splitMarkdownBlocks(raw)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0].isQuote)
        assertEquals(listOf("quoted line 1", "quoted & line 2"), blocks[0].lines)
        assertFalse(blocks[1].isQuote)
        assertEquals(listOf("normal <text>"), blocks[1].lines)
    }

    @Test
    fun buildAnnotatedStringParsesFormattingLinksAndSubredditUserMentions() {
        val raw = "**bold** *italic* ~~strike~~ `code` [link](https://example.com) " +
            "[sublink](r/kotlin) [userlink](/u/spez) https://bare.dev /r/android /u/test_user"

        val parsed = buildRedditMarkdownAnnotatedString(
            raw = raw,
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        val text = parsed.annotatedString.text

        assertTrue(text.contains("bold"))
        assertTrue(text.contains("italic"))
        assertTrue(text.contains("strike"))
        assertTrue(text.contains("code"))
        assertTrue(text.contains("link"))
        assertTrue(text.contains("sublink"))
        assertTrue(text.contains("userlink"))

        val spanStyles = parsed.annotatedString.spanStyles
        assertTrue(spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(spanStyles.any { it.item.fontFamily == FontFamily.Monospace })

        val urlAnns = parsed.annotatedString.getStringAnnotations("URL", 0, text.length).map { it.item }
        assertTrue("https://example.com" in urlAnns)
        assertTrue("https://bare.dev" in urlAnns)

        val subAnns = parsed.annotatedString.getStringAnnotations("SUB", 0, text.length).map { it.item }
        assertTrue("kotlin" in subAnns)
        assertTrue("android" in subAnns)

        val userAnns = parsed.annotatedString.getStringAnnotations("USER", 0, text.length).map { it.item }
        assertTrue("spez" in userAnns)
        assertTrue("test_user" in userAnns)
    }

    @Test
    fun spoilersTrackIdsAcrossBlocksAndToggleReveal() {
        val block1 = buildRedditMarkdownAnnotatedString(
            raw = "First >!secret1!< and >!secret2!<",
            baseColor = Color.White,
            revealedSpoilers = mapOf(0 to true),
            spoilerIdStart = 0
        )
        assertEquals(2, block1.spoilerCount)
        val anns1 = block1.annotatedString.getStringAnnotations("SPOILER", 0, block1.annotatedString.length)
        assertEquals(listOf("0", "1"), anns1.map { it.item })

        val block2 = buildRedditMarkdownAnnotatedString(
            raw = "Second >!secret3!<",
            baseColor = Color.White,
            revealedSpoilers = mapOf(0 to true),
            spoilerIdStart = block1.spoilerCount
        )
        assertEquals(1, block2.spoilerCount)
        val anns2 = block2.annotatedString.getStringAnnotations("SPOILER", 0, block2.annotatedString.length)
        assertEquals(listOf("2"), anns2.map { it.item })
    }

    @Test
    fun extractInlineImageUrlsResolvesGiphyMetadataAndCapsAtFour() {
        val metadata = mapOf(
            "m1" to MediaMetadata(s = MediaImageSource(gif = "https://i.redd.it/one.gif&amp;v=1")),
            "m2" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/two.png&amp;s=abc")),
            "m3" to MediaMetadata(p = listOf(MediaImageSource(u = "https://preview.redd.it/three.jpg")))
        )
        val raw = """
            ![gif](giphy|abc123)
            ![img](m1)
            ![img](m2)
            ![img](m3)
            https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Ffour.webp
            https://i.imgur.com/five.jpg
        """.trimIndent()

        val urls = extractInlineImageUrls(
            raw = raw,
            unwrapRedditMedia = true,
            mediaMetadata = metadata
        )

        assertEquals(4, urls.size)
        assertEquals(
            listOf(
                "https://i.giphy.com/media/abc123/giphy.gif",
                "https://i.redd.it/one.gif&v=1",
                "https://preview.redd.it/two.png&s=abc",
                "https://preview.redd.it/three.jpg"
            ),
            urls
        )

        val unwrappedOnly = extractInlineImageUrls(
            raw = "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Ffour.webp",
            unwrapRedditMedia = true,
            mediaMetadata = null
        )
        assertEquals(listOf("https://i.redd.it/four.webp"), unwrappedOnly)
    }

    @Test
    fun normalizeRedditEmbedTokensStripsOrConvertsToLinks() {
        val raw = "Look at this ![gif](giphy|xyz999) and [My cool cat](https://i.redd.it/cat.jpg)"
        val inlineUrls = listOf("https://i.giphy.com/media/xyz999/giphy.gif", "https://i.redd.it/cat.jpg")

        val stripped = normalizeRedditEmbedTokensForDisplay(
            raw = raw,
            stripForInlineImages = true,
            unwrapRedditMedia = true,
            mediaMetadata = null,
            inlineImageUrls = inlineUrls
        )
        assertEquals("Look at this  and My cool cat", stripped)

        val linked = normalizeRedditEmbedTokensForDisplay(
            raw = "![gif](giphy|xyz999)",
            stripForInlineImages = false,
            unwrapRedditMedia = true,
            mediaMetadata = null,
            inlineImageUrls = emptyList()
        )
        assertEquals("[GIF](https://i.giphy.com/media/xyz999/giphy.gif)", linked)
    }

    @Test
    fun splitMarkdownBlocksPreservesLineLeadingAndIndentedSpoilers() {
        val leadingBlocks = splitMarkdownBlocks("&gt;!Darth Vader is Luke's father!&lt;")
        assertEquals(1, leadingBlocks.size)
        assertFalse(leadingBlocks[0].isQuote)
        assertEquals(listOf(">!Darth Vader is Luke's father!<"), leadingBlocks[0].lines)

        val parsedLeading = buildRedditMarkdownAnnotatedString(
            raw = leadingBlocks[0].lines.joinToString("\n"),
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        assertEquals(1, parsedLeading.spoilerCount)
        assertEquals("Darth Vader is Luke's father", parsedLeading.annotatedString.text)

        val indentedBlocks = splitMarkdownBlocks("  &gt;!Indented spoiler!&lt;")
        assertEquals(1, indentedBlocks.size)
        assertFalse(indentedBlocks[0].isQuote)
        val parsedIndented = buildRedditMarkdownAnnotatedString(
            raw = indentedBlocks[0].lines.joinToString("\n"),
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        assertEquals(1, parsedIndented.spoilerCount)

        val mixedBlocks = splitMarkdownBlocks("&gt; quote line\n&gt;!spoiler line!&lt;\n&gt; &gt;!quoted spoiler!&lt;")
        assertEquals(3, mixedBlocks.size)
        assertTrue(mixedBlocks[0].isQuote)
        assertEquals(listOf("quote line"), mixedBlocks[0].lines)
        assertFalse(mixedBlocks[1].isQuote)
        assertEquals(listOf(">!spoiler line!<"), mixedBlocks[1].lines)
        assertTrue(mixedBlocks[2].isQuote)
        assertEquals(listOf(">!quoted spoiler!<"), mixedBlocks[2].lines)
    }

    @Test
    fun parserScalesLinearlyOn8000UnclosedAndNearMissInputs() {
        val inputs = listOf(
            "[abc ".repeat(8000),
            "[abc ".repeat(8000) + "]",
            "[abc ".repeat(8000) + "](not_a_url)",
            ">!abc ".repeat(8000),
            ">!abc ".repeat(8000) + "!<",
            "**abc ".repeat(8000),
            "`abc ".repeat(8000)
        )
        // Warmup
        buildRedditMarkdownAnnotatedString("[abc ".repeat(100), Color.White, emptyMap(), 0)

        val startNs = System.nanoTime()
        for (input in inputs) {
            val parsed = buildRedditMarkdownAnnotatedString(input, Color.White, emptyMap(), 0)
            assertTrue(parsed.annotatedString.text.isNotEmpty())
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected linear runtime (< 500ms) across 7 8000-token inputs, got ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test
    fun fifthAndLaterEmbedsRemainVisibleWhenCappedAtFourInlineImages() {
        val metadata = mapOf(
            "m1" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/img1.png")),
            "m2" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/img2.png")),
            "m3" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/img3.png")),
            "m4" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/img4.png")),
            "m5" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/img5.png"))
        )
        val raw = "![img](m1)\n![img](m2)\n![img](m3)\n![img](m4)\n![img](m5)"

        val inlineUrls = extractInlineImageUrls(raw, unwrapRedditMedia = true, mediaMetadata = metadata)
        assertEquals(4, inlineUrls.size)

        val display = normalizeRedditEmbedTokensForDisplay(
            raw = raw,
            stripForInlineImages = true,
            unwrapRedditMedia = true,
            mediaMetadata = metadata,
            inlineImageUrls = inlineUrls
        )
        assertTrue("5th embed must not be silently erased", display.contains("https://preview.redd.it/img5.png"))
        assertFalse(display.contains("https://preview.redd.it/img1.png"))
        val parsed = buildRedditMarkdownAnnotatedString(display, Color.White, emptyMap(), 0)
        val links = parsed.annotatedString.getStringAnnotations("URL", 0, parsed.annotatedString.length).map { it.item }
        assertEquals(listOf("https://preview.redd.it/img5.png"), links)
    }

    @Test
    fun linkScannerAndNormalizerScaleLinearlyWhilePreservingSubsequentValidLinks() {
        val metadata = mapOf(
            "m1" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/img1.png"))
        )
        val parserEvil = "[a](http://x]".repeat(8000)
        val normalizerEvil = "![img](m1) " + "[a](https://x".repeat(8000)
        val manyLines = (1..8000).joinToString("\n") { "line $it" }

        // Warmup
        buildRedditMarkdownAnnotatedString("[a](http://x]".repeat(50), Color.White, emptyMap(), 0)
        normalizeRedditEmbedTokensForDisplay(
            raw = "![img](m1) " + "[a](https://x".repeat(50),
            stripForInlineImages = true,
            unwrapRedditMedia = true,
            mediaMetadata = metadata,
            inlineImageUrls = listOf("https://preview.redd.it/img1.png")
        )
        splitMarkdownBlocks("line\n".repeat(50))

        val startNs = System.nanoTime()
        val parsedEvil = buildRedditMarkdownAnnotatedString(parserEvil, Color.White, emptyMap(), 0)
        val normalizedEvil = normalizeRedditEmbedTokensForDisplay(
            raw = normalizerEvil,
            stripForInlineImages = true,
            unwrapRedditMedia = true,
            mediaMetadata = metadata,
            inlineImageUrls = listOf("https://preview.redd.it/img1.png")
        )
        val blocks = splitMarkdownBlocks(manyLines)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue(parsedEvil.annotatedString.text.isNotEmpty())
        assertTrue(normalizedEvil.isNotEmpty())
        assertEquals(1, blocks.size)
        assertEquals(8000, blocks[0].lines.size)
        assertTrue(
            "Expected linear runtime (< 500ms) for 8000-token scanner/normalizer/block-split inputs, got ${elapsedMs}ms",
            elapsedMs < 500L
        )

        val brokenThenValid = buildRedditMarkdownAnnotatedString(
            raw = "[broken](https://a real [ok](https://b.com)",
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        val brokenThenValidUrls = brokenThenValid.annotatedString
            .getStringAnnotations("URL", 0, brokenThenValid.annotatedString.length)
            .map { it.item }
        assertTrue("https://b.com" in brokenThenValidUrls)

        val nestedThenValid = buildRedditMarkdownAnnotatedString(
            raw = "[outer [inner](bad) [good](https://ok.com)",
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        val nestedThenValidUrls = nestedThenValid.annotatedString
            .getStringAnnotations("URL", 0, nestedThenValid.annotatedString.length)
            .map { it.item }
        assertEquals(listOf("https://ok.com"), nestedThenValidUrls)
    }
}

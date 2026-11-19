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

    @Test
    fun automoderatorFormattingWithSpacedUrlHrAndItalicRelativeLinkParsesCorrectly() {
        val raw = """
            Welcome to r/science! Our [normal comment rules]( https://www.reddit.com/r/science/wiki/rules#wiki_comment_rules) apply.

            ---

            User: u/mvea

            *I am a bot, and this action was performed automatically. Please [contact the moderators of this subreddit](/message/compose/?to=/r/science) if you have any questions or concerns.*
        """.trimIndent()

        val blocks = splitMarkdownBlocks(raw)
        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlockKind.PARAGRAPH, blocks[0].kind)
        assertEquals(MarkdownBlockKind.HORIZONTAL_RULE, blocks[1].kind)
        assertEquals(MarkdownBlockKind.PARAGRAPH, blocks[2].kind)
        assertEquals(MarkdownBlockKind.PARAGRAPH, blocks[3].kind)

        val parsed0 = buildRedditMarkdownAnnotatedString(
            raw = blocks[0].lines.joinToString("\n"),
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0,
            linkIdStart = 0
        )
        assertEquals(
            "Welcome to r/science! Our normal comment rules apply.",
            parsed0.annotatedString.text
        )
        assertEquals(2, parsed0.links.size)
        assertEquals("r/science", parsed0.links[0].label)
        assertEquals("science", parsed0.links[0].subreddit)
        assertEquals("normal comment rules", parsed0.links[1].label)
        assertEquals(
            "https://www.reddit.com/r/science/wiki/rules#wiki_comment_rules",
            parsed0.links[1].url
        )

        val linkIdxAnns0 = parsed0.annotatedString
            .getStringAnnotations("LINK_INDEX", 0, parsed0.annotatedString.length)
            .map { it.item }
        assertEquals(listOf("0", "1"), linkIdxAnns0)

        // Both bare r/science and markdown link should be underlined
        assertTrue(parsed0.annotatedString.spanStyles.count { it.item.textDecoration == TextDecoration.Underline } >= 2)

        val parsed3 = buildRedditMarkdownAnnotatedString(
            raw = blocks[3].lines.joinToString("\n"),
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0,
            linkIdStart = 2
        )
        assertEquals(
            "I am a bot, and this action was performed automatically. Please contact the moderators of this subreddit if you have any questions or concerns.",
            parsed3.annotatedString.text
        )
        assertTrue(parsed3.annotatedString.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertEquals(1, parsed3.links.size)
        assertEquals("contact the moderators of this subreddit", parsed3.links[0].label)
        assertEquals(
            "https://www.reddit.com/message/compose/?to=/r/science",
            parsed3.links[0].url
        )
        val linkIdxAnns3 = parsed3.annotatedString
            .getStringAnnotations("LINK_INDEX", 0, parsed3.annotatedString.length)
            .map { it.item }
        assertEquals(listOf("2"), linkIdxAnns3)
    }

    @Test
    fun headingsListsTablesCodeBlocksSuperscriptsUnderscoresAndParensParseCorrectly() {
        val doc = """
            ### Section Title ##
            * *Italic bullet* item
            1. Ordered item
            >> Nested quote
            ```kotlin
            val x = **not_bold**
            ```
            | Col A | Col B |
            | :--- | ---: |
            | **Bold** | [Wiki](https://en.wikipedia.org/wiki/Kotlin_(programming_language)) |

                indented_code()
        """.trimIndent()

        val blocks = splitMarkdownBlocks(doc)
        assertEquals(MarkdownBlockKind.HEADING, blocks[0].kind)
        assertEquals(3, blocks[0].headingLevel)
        assertEquals(listOf("Section Title"), blocks[0].lines)

        assertEquals(MarkdownBlockKind.LIST_ITEM, blocks[1].kind)
        assertEquals("•", blocks[1].listMarker)
        assertEquals(listOf("*Italic bullet* item"), blocks[1].lines)

        assertEquals(MarkdownBlockKind.LIST_ITEM, blocks[2].kind)
        assertEquals("1.", blocks[2].listMarker)

        assertEquals(MarkdownBlockKind.QUOTE, blocks[3].kind)
        assertEquals(2, blocks[3].quoteDepth)

        assertEquals(MarkdownBlockKind.CODE_BLOCK, blocks[4].kind)
        assertEquals(listOf("val x = **not_bold**"), blocks[4].lines)

        assertEquals(MarkdownBlockKind.TABLE, blocks[5].kind)
        assertEquals(listOf("Col A", "Col B"), blocks[5].tableHeaders)
        assertEquals(listOf(MarkdownTableAlign.LEFT, MarkdownTableAlign.RIGHT), blocks[5].tableAlignments)
        assertEquals(1, blocks[5].tableRows.size)

        assertEquals(MarkdownBlockKind.CODE_BLOCK, blocks[6].kind)
        assertEquals(listOf("indented_code()"), blocks[6].lines)

        val inline = buildRedditMarkdownAnnotatedString(
            raw = "snake_case_var _em_ __strong__ ***both*** ^sup ^(two words) \\*escaped\\* " +
                "https://example.com/a\\_b.html, [Wiki](https://en.wikipedia.org/wiki/Kotlin_(programming_language))",
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        val outText = inline.annotatedString.text
        assertTrue(outText.contains("snake_case_var"))
        assertTrue(outText.contains("*escaped*"))
        assertTrue(inline.annotatedString.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(inline.annotatedString.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(inline.annotatedString.spanStyles.any { it.item.baselineShift != null })
        val urls = inline.annotatedString.getStringAnnotations("URL", 0, outText.length).map { it.item }
        assertTrue("https://example.com/a_b.html" in urls)
        assertTrue("https://en.wikipedia.org/wiki/Kotlin_(programming_language)" in urls)

        val userLinkParsed = buildRedditMarkdownAnnotatedString(
            raw = "[Spez](/user/spez)",
            baseColor = Color.White,
            revealedSpoilers = emptyMap(),
            spoilerIdStart = 0
        )
        assertEquals(1, userLinkParsed.links.size)
        assertEquals("spez", userLinkParsed.links[0].username)
        assertEquals("https://www.reddit.com/user/spez", userLinkParsed.links[0].url)
        val userAnns = userLinkParsed.annotatedString
            .getStringAnnotations("USER", 0, userLinkParsed.annotatedString.length)
            .map { it.item }
        assertEquals(listOf("spez"), userAnns)
    }

    @Test
    fun newInlineConstructsScaleLinearlyOn8000UnclosedInputs() {
        val inputs = listOf(
            "_abc ".repeat(8000),
            "__abc ".repeat(8000),
            "^(abc ".repeat(8000),
            "[a](https://example.com/(x".repeat(8000)
        )
        buildRedditMarkdownAnnotatedString("_abc ".repeat(100), Color.White, emptyMap(), 0)
        val startNs = System.nanoTime()
        for (input in inputs) {
            val parsed = buildRedditMarkdownAnnotatedString(input, Color.White, emptyMap(), 0)
            assertTrue(parsed.annotatedString.text.isNotEmpty())
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected linear runtime (< 500ms), got ${elapsedMs}ms", elapsedMs < 500L)
    }
}

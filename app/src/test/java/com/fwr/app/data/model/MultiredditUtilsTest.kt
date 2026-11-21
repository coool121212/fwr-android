package com.fwr.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiredditUtilsTest {

    @Test
    fun parseMultiredditPath_handlesSupportedFormsAndRejectsInvalid() {
        assertEquals(
            ParsedMultiredditPath(owner = "alice", name = "tech"),
            MultiredditUtils.parseMultiredditPath("user/alice/m/tech")
        )
        assertEquals(
            ParsedMultiredditPath(owner = "alice", name = "tech"),
            MultiredditUtils.parseMultiredditPath("/u/alice/m/tech/")
        )
        assertEquals(
            ParsedMultiredditPath(owner = null, name = "news"),
            MultiredditUtils.parseMultiredditPath("me/m/news")
        )
        assertEquals(
            ParsedMultiredditPath(owner = null, name = "news"),
            MultiredditUtils.parseMultiredditPath("/m/news/")
        )

        assertNull(MultiredditUtils.parseMultiredditPath("kotlin"))
        assertNull(MultiredditUtils.parseMultiredditPath("/r/kotlin"))
        assertNull(MultiredditUtils.parseMultiredditPath("user/alice/m/tech/extra"))
        assertNull(MultiredditUtils.parseMultiredditPath("user/alice/submitted"))
        assertNull(MultiredditUtils.parseMultiredditPath("m/"))
        assertNull(MultiredditUtils.parseMultiredditPath(""))

        assertTrue(MultiredditUtils.isMultiredditTarget("/U/Bob/M/Dev"))
        assertFalse(MultiredditUtils.isMultiredditTarget("r/android"))
    }

    @Test
    fun normalizeFeedTarget_handlesMultisWithAndWithoutUsernameAndSubreddits() {
        assertEquals(
            "user/alice/m/tech",
            MultiredditUtils.normalizeFeedTarget("/u/alice/m/tech/", currentUsername = "bob")
        )
        assertEquals(
            "user/bob/m/news",
            MultiredditUtils.normalizeFeedTarget("/m/news", currentUsername = "bob")
        )
        assertEquals(
            "me/m/news",
            MultiredditUtils.normalizeFeedTarget("/m/news", currentUsername = null)
        )
        assertEquals(
            "me/m/news",
            MultiredditUtils.normalizeFeedTarget("me/m/news", currentUsername = "  ")
        )
        assertEquals(
            "Kotlin",
            MultiredditUtils.normalizeFeedTarget("R/Kotlin/", currentUsername = "bob")
        )
        assertEquals(
            "",
            MultiredditUtils.normalizeFeedTarget("r/", currentUsername = "bob")
        )
        assertEquals(
            "",
            MultiredditUtils.normalizeFeedTarget("/r/", currentUsername = "bob")
        )
    }

    @Test
    fun formatFeedHeaderTitleAndWebUrl_coverAllFeedTypes() {
        assertEquals(
            "reddit: the front page of the internet",
            MultiredditUtils.formatFeedHeaderTitle("frontpage", currentUsername = "alice")
        )
        assertEquals(
            "/m/Tech Feed",
            MultiredditUtils.formatFeedHeaderTitle(
                "user/alice/m/tech",
                displayNameOverride = "Tech Feed",
                currentUsername = "ALICE"
            )
        )
        assertEquals(
            "/u/bob/m/science",
            MultiredditUtils.formatFeedHeaderTitle("user/bob/m/science", currentUsername = "alice")
        )
        assertEquals(
            "/m/news",
            MultiredditUtils.formatFeedHeaderTitle("me/m/news", currentUsername = null)
        )
        assertEquals(
            "/r/androiddev",
            MultiredditUtils.formatFeedHeaderTitle("androiddev", currentUsername = "alice")
        )

        assertEquals(
            "https://www.reddit.com/best",
            MultiredditUtils.formatFeedWebUrl("frontpage", "best")
        )
        assertEquals(
            "https://www.reddit.com/user/alice/m/tech/top",
            MultiredditUtils.formatFeedWebUrl("user/alice/m/tech", "top")
        )
        assertEquals(
            "https://www.reddit.com/me/m/news/hot",
            MultiredditUtils.formatFeedWebUrl("me/m/news", "hot")
        )
        assertEquals(
            "https://www.reddit.com/r/kotlin/new",
            MultiredditUtils.formatFeedWebUrl("kotlin", "new")
        )
    }

    @Test
    fun parseMultiredditsJson_handlesTopLevelArrayAndListingDedupesAndSorts() {
        val arrayPayload = """
            [
              {
                "kind": "LabeledMulti",
                "data": {
                  "name": "z_feed",
                  "display_name": "Zebra Feed",
                  "path": "/user/alice/m/z_feed",
                  "owner": "alice",
                  "visibility": "public",
                  "can_edit": true,
                  "subreddits": [{"name": "zoology"}, {"name": "Art"}, {"name": "art"}, {"name": " "}]
                }
              },
              {
                "kind": "LabeledMulti",
                "data": {
                  "name": "a_feed",
                  "display_name": "Alpha Feed",
                  "path": "/user/alice/m/a_feed",
                  "owner": "alice",
                  "visibility": "private",
                  "can_edit": true,
                  "subreddits": [{"name": "android"}]
                }
              },
              {
                "kind": "LabeledMulti",
                "data": {
                  "name": "a_feed",
                  "display_name": "Alpha Duplicate",
                  "path": "/user/alice/m/a_feed",
                  "owner": "alice"
                }
              }
            ]
        """.trimIndent()

        val fromArray = MultiredditUtils.parseMultiredditsJson(Json.parseToJsonElement(arrayPayload))
        assertEquals(listOf("Alpha Feed", "Zebra Feed"), fromArray.map { it.resolvedDisplayName })
        assertEquals(listOf("Art", "zoology"), fromArray[1].subredditNames)
        assertEquals("user/alice/m/a_feed", fromArray[0].canonicalFeedKey)

        val listingPayload = """
            {
              "kind": "Listing",
              "data": {
                "children": [
                  {
                    "kind": "LabeledMulti",
                    "data": {
                      "name": "coding",
                      "display_name": "",
                      "path": "/user/bob/m/coding",
                      "subreddits": [{"name": "kotlin"}, {"name": "programming"}]
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val fromListing = MultiredditUtils.parseMultiredditsJson(Json.parseToJsonElement(listingPayload))
        assertEquals(1, fromListing.size)
        assertEquals("bob", fromListing[0].resolvedOwner)
        assertEquals("coding", fromListing[0].resolvedDisplayName)
        assertEquals(listOf("kotlin", "programming"), fromListing[0].subredditNames)
    }

    @Test
    fun modelBuildersAndSanitizeMultiSlug_produceExpectedJsonAndSlugs() {
        val modelJson = MultiredditUtils.buildMultiModelJson(
            displayName = "  Tech & News  ",
            descriptionMd = "My feeds",
            visibility = "PUBLIC",
            subreddits = listOf("/r/kotlin", "r/android/", "Kotlin", " ")
        )
        val parsedObj = Json.parseToJsonElement(modelJson)
        assertEquals(
            """{"display_name":"Tech & News","description_md":"My feeds","visibility":"public","subreddits":[{"name":"kotlin"},{"name":"android"}]}""",
            parsedObj.toString()
        )

        assertEquals(
            """{"name":"androiddev"}""",
            MultiredditUtils.buildMultiSubredditModelJson("/r/androiddev/")
        )

        assertEquals("tech_news_2026", MultiredditUtils.sanitizeMultiSlug("  Tech & News - 2026! "))
        assertEquals("custom_feed", MultiredditUtils.sanitizeMultiSlug("!!!"))
        assertEquals(50, MultiredditUtils.sanitizeMultiSlug("a".repeat(80)).length)
    }
}

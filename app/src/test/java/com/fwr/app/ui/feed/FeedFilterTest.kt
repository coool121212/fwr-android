package com.fwr.app.ui.feed

import com.fwr.app.data.model.RedditPost
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedFilterTest {

    private fun post(
        id: String,
        title: String,
        subreddit: String = "android",
        over18: Boolean = false
    ) = RedditPost(
        id = id,
        title = title,
        subreddit = subreddit,
        over_18 = over18
    )

    @Test
    fun filterPosts_keywordMatchesCaseInsensitively() {
        val posts = listOf(
            post("1", "Huge SPOILER inside"),
            post("2", "New Ai framework released"),
            post("3", "Learning kotlin coroutines"),
            post("4", "Normal discussion thread")
        )

        val filtered = FeedFilter.filterPosts(
            posts = posts,
            hiddenPostIds = emptySet(),
            nsfwAllowed = true,
            blockedSubreddits = emptySet(),
            keywordFilters = setOf("SPOILER", "AI", "  Kotlin  ")
        )

        assertEquals(listOf("4"), filtered.map { it.id })
    }

    @Test
    fun filterPosts_blankKeywordsDoNotFilterPosts() {
        val posts = listOf(
            post("1", "First post"),
            post("2", "Second post")
        )

        val filtered = FeedFilter.filterPosts(
            posts = posts,
            hiddenPostIds = emptySet(),
            nsfwAllowed = true,
            blockedSubreddits = emptySet(),
            keywordFilters = setOf("", "   ", "\t")
        )

        assertEquals(listOf("1", "2"), filtered.map { it.id })
    }

    @Test
    fun filterPosts_blockedSubredditsAndNsfwAndHiddenIdsFilterCorrectly() {
        val posts = listOf(
            post("1", "Visible post", subreddit = "kotlin"),
            post("2", "Hidden post", subreddit = "kotlin"),
            post("3", "NSFW post", subreddit = "kotlin", over18 = true),
            post("4", "Blocked sub post", subreddit = "Politics"),
            post("5", "Hidden by fullname", subreddit = "kotlin")
        )

        val filtered = FeedFilter.filterPosts(
            posts = posts,
            hiddenPostIds = setOf("2", "t3_5"),
            nsfwAllowed = false,
            blockedSubreddits = setOf("  politics "),
            keywordFilters = emptySet()
        )

        assertEquals(listOf("1"), filtered.map { it.id })
    }

    @Test
    fun filterPosts_blockedSubredditsNormalizeRSlashPrefixAndCase() {
        val posts = listOf(
            post("a", "Post A", subreddit = "AskReddit"),
            post("b", "Post B", subreddit = "askreddit"),
            post("c", "Post C", subreddit = "kotlin"),
            post("d", "Post D", subreddit = "Android")
        )

        val filtered = FeedFilter.filterPosts(
            posts = posts,
            hiddenPostIds = emptySet(),
            nsfwAllowed = true,
            blockedSubreddits = setOf("r/AskReddit", " /R/android/ "),
            keywordFilters = emptySet()
        )

        assertEquals(listOf("c"), filtered.map { it.id })
    }

    @Test
    fun filterPosts_prefixOnlyBlockedSubredditsDoNotBecomeRAndFilterSubredditR() {
        val posts = listOf(
            post("1", "Post in r", subreddit = "r"),
            post("2", "Post in kotlin", subreddit = "kotlin")
        )

        val filtered = FeedFilter.filterPosts(
            posts = posts,
            hiddenPostIds = emptySet(),
            nsfwAllowed = true,
            blockedSubreddits = setOf("r/", "/r/", "  /R/  "),
            keywordFilters = emptySet()
        )

        assertEquals(listOf("1", "2"), filtered.map { it.id })
    }
}

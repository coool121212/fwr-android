package com.fwr.app.ui.feed

import com.fwr.app.data.model.RedditPost
import java.util.Locale

/**
 * Pure Kotlin helper for filtering visible feed posts according to hidden IDs,
 * NSFW preference, blocked subreddits, and case-insensitive keyword filters.
 */
object FeedFilter {
    private fun normalizeSubreddit(raw: String): String {
        var cleaned = raw.trim().trimStart('/')
        if (cleaned.startsWith("r/", ignoreCase = true)) {
            cleaned = cleaned.substring(2)
        }
        return cleaned.trim('/').lowercase(Locale.ROOT)
    }

    fun filterPosts(
        posts: List<RedditPost>,
        hiddenPostIds: Set<String>,
        nsfwAllowed: Boolean,
        blockedSubreddits: Set<String>,
        keywordFilters: Set<String>
    ): List<RedditPost> {
        val normalizedBlockedSubs = blockedSubreddits
            .asSequence()
            .map { normalizeSubreddit(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        val normalizedKeywords = keywordFilters
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toList()

        if (hiddenPostIds.isEmpty() &&
            nsfwAllowed &&
            normalizedBlockedSubs.isEmpty() &&
            normalizedKeywords.isEmpty()
        ) {
            return posts
        }

        return posts.filter { post ->
            val postKey = post.id.ifEmpty { post.permalink }
            if (postKey in hiddenPostIds || post.id in hiddenPostIds || post.resolvedFullname in hiddenPostIds) {
                return@filter false
            }
            if (!nsfwAllowed && post.over_18) {
                return@filter false
            }
            if (normalizedBlockedSubs.isNotEmpty() &&
                normalizeSubreddit(post.subreddit) in normalizedBlockedSubs
            ) {
                return@filter false
            }
            if (normalizedKeywords.isNotEmpty()) {
                val titleLower = post.title.lowercase(Locale.ROOT)
                if (normalizedKeywords.any { kw -> titleLower.contains(kw) }) {
                    return@filter false
                }
            }
            true
        }
    }
}

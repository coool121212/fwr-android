package com.fwr.app.ui.feed

import androidx.compose.runtime.Immutable
import com.fwr.app.data.model.RedditPost

/**
 * UI state hierarchy for the subreddit post feed.
 */
@Immutable
sealed interface FeedUiState {
    @Immutable
    data object Loading : FeedUiState

    @Immutable
    data class Success(
        val posts: List<RedditPost>,
        val after: String?,
        val isLoadingMore: Boolean = false,
        val isRefreshing: Boolean = false,
        val paginationError: String? = null
    ) : FeedUiState

    @Immutable
    data class Error(
        val message: String
    ) : FeedUiState
}

package com.fwr.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fwr.app.data.model.RedditPost
import com.fwr.app.ui.feed.FeedViewModel

/**
 * HomeScreen entry point for RIF's classic subreddit feed, action strip selection,
 * and slide-out subreddit navigation drawer.
 */
@Composable
fun HomeScreen(
    viewModel: FeedViewModel,
    modifier: Modifier = Modifier,
    onPostClick: (RedditPost) -> Unit = {},
    onThumbnailClick: ((RedditPost) -> Unit)? = null,
    onOpenSettingsClick: (() -> Unit)? = null,
    onOpenProfileClick: ((String) -> Unit)? = null,
    onOpenInboxClick: ((String) -> Unit)? = null
) {
    FeedScreen(
        viewModel = viewModel,
        modifier = modifier,
        onPostClick = onPostClick,
        onThumbnailClick = onThumbnailClick,
        onOpenSettingsClick = onOpenSettingsClick,
        onOpenProfileClick = onOpenProfileClick,
        onOpenInboxClick = onOpenInboxClick
    )
}

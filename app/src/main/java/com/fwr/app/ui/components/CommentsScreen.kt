package com.fwr.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fwr.app.data.model.RedditPost
import com.fwr.app.ui.comments.CommentsScreen as RealCommentsScreen

@Composable
fun CommentsScreen(
    post: RedditPost,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    RealCommentsScreen(
        post = post,
        onBackClick = onBackClick,
        modifier = modifier
    )
}

package com.fwr.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fwr.app.data.model.RedditAward
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val NON_IMAGE_THUMBNAILS = setOf("self", "default", "nsfw", "spoiler", "image", "")

/**
 * Jetpack Compose implementation of RWF's classic 3-column Dark Theme thread row
 * and expandable action bar:
 *
 * - Selected row background: `#232A35` (`RwfPalette.SelectedPostRowDark`).
 * - Left column (44dp wide): Upvote arrow / Score / Downvote arrow.
 * - Middle column: Flair + Title + `(domain)`, `"[num] comments  [subreddit]"`, and `"[time] ago by [author]"`.
 * - Right column (70dp x 70dp): Square thumbnail or classic placeholder icon.
 * - Action row underneath when selected:
 *   `share`   `save`   `hide`   `...`   `comments`
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostCard(
    post: RedditPost,
    modifier: Modifier = Modifier,
    isRead: Boolean = false,
    isExpanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    onPostClick: (RedditPost) -> Unit = {},
    onThumbnailClick: (RedditPost) -> Unit = {},
    onMarkRead: (() -> Unit)? = null,
    onHidePost: ((RedditPost) -> Unit)? = null,
    onBlockSubreddit: ((String) -> Unit)? = null,
    onSelectSubreddit: ((String) -> Unit)? = null,
    onOpenAuthorProfile: ((String) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") amoledBlackBackground: Boolean = false,
    leftHandedMode: Boolean = false,
    showLinkFlair: Boolean = true,
    titleFontSizeSp: Int = 15,
    isOpHeaderInComments: Boolean = false,
    cardsView: Boolean = false,
    labelNsfw: Boolean = true,
    shouldShowThumbnails: Boolean = true,
    allowHighResPreview: Boolean = true,
    thumbnailSizeDp: Int = 70,
    showInlineImages: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val initialVoteDirection = remember(post.id, post.likes) {
        when (post.likes) {
            true -> 1
            false -> -1
            null -> 0
        }
    }
    var voteDirection by remember(post.id, post.likes) { mutableIntStateOf(initialVoteDirection) }
    var clickedLocally by remember(post.id) { mutableStateOf(false) }
    var internalExpanded by remember(post.id) { mutableStateOf(false) }
    var isSaved by remember(post.id, post.saved) { mutableStateOf(post.saved) }

    val expanded = isExpanded ?: internalExpanded
    var spoilerRevealed by remember(post.id, post.spoiler, expanded) { mutableStateOf(!post.spoiler) }
    val toggleExpanded = {
        clickedLocally = true
        onMarkRead?.invoke()
        if (onToggleExpand != null) {
            onToggleExpand()
        } else {
            internalExpanded = !internalExpanded
        }
    }

    val effectiveRead = isRead || clickedLocally || expanded
    val domainText = remember(post.id, post.domain, post.is_self, post.subreddit, post.url) {
        resolveDomain(post)
    }
    val thumbnailUrl = remember(
        post.id,
        post.thumbnail,
        post.spoiler,
        post.preview,
        post.media_metadata,
        post.url,
        shouldShowThumbnails,
        allowHighResPreview
    ) {
        if (shouldShowThumbnails) {
            resolveThumbnailUrl(post, allowHighRes = allowHighResPreview)
        } else {
            null
        }
    }
    val cardBannerUrl = remember(
        post.id,
        post.preview,
        post.media_metadata,
        post.thumbnail,
        post.url,
        post.spoiler,
        post.over_18,
        cardsView,
        shouldShowThumbnails,
        allowHighResPreview,
        isOpHeaderInComments
    ) {
        if (cardsView && !isOpHeaderInComments && shouldShowThumbnails && !post.spoiler && !post.over_18) {
            resolveCardBannerUrl(post, allowHighResPreview)
        } else {
            null
        }
    }
    val isGallery = post.is_gallery || post.gallery_data != null
    val showPlaceholderIcon = shouldShowThumbnails && thumbnailUrl == null
    val showCompactThumbnail = cardBannerUrl == null && (thumbnailUrl != null || showPlaceholderIcon)
    val clampedThumbnailDp = thumbnailSizeDp.coerceIn(48, 128)

    val titleColor = when {
        post.stickied -> RwfPalette.StickyTitleDark
        effectiveRead -> RwfPalette.TitleReadDark
        else -> RwfPalette.TitleUnreadDark
    }

    val baseBackgroundColor = if (cardsView && !isOpHeaderInComments) {
        RwfPalette.CardBackgroundDark
    } else {
        RwfPalette.BackgroundMaterialDark
    }

    val targetRowBackground by animateColorAsState(
        targetValue = if (expanded) RwfPalette.SelectedPostRowDark else baseBackgroundColor,
        animationSpec = tween(durationMillis = 150),
        label = "postRowSelectionColor"
    )

    val flairLabel = remember(post.id, post.link_flair_text, isGallery, post.gallery_data, showLinkFlair) {
        if (showLinkFlair) {
            buildString {
                if (!post.link_flair_text.isNullOrBlank()) {
                    append(post.link_flair_text.trim())
                } else if (isGallery) {
                    val count = post.gallery_data?.items?.size ?: 0
                    append(if (count > 0) "GALLERY ($count)" else "GALLERY")
                }
            }
        } else {
            ""
        }
    }

    val containerModifier = if (cardsView && !isOpHeaderInComments) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(targetRowBackground)
            .border(1.dp, RwfPalette.DividerLineDark, RoundedCornerShape(6.dp))
    } else {
        modifier
            .fillMaxWidth()
            .background(targetRowBackground)
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = clampedThumbnailDp.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (leftHandedMode && showCompactThumbnail) {
                RifRightThumbnailOrPlaceholder(
                    thumbnailUrl = thumbnailUrl,
                    isSelf = post.is_self,
                    isNsfw = post.over_18 && labelNsfw,
                    isSpoiler = post.spoiler,
                    isGallery = isGallery,
                    thumbnailSizeDp = clampedThumbnailDp,
                    onClick = {
                        clickedLocally = true
                        onMarkRead?.invoke()
                        onThumbnailClick(post)
                    }
                )
            } else if (!leftHandedMode) {
                // Column 1 (Far Left): Vote column (44dp width, 70dp minHeight)
                RifVoteColumn(
                    score = post.score + (voteDirection - initialVoteDirection),
                    voteDirection = voteDirection,
                    onUpvote = {
                        val nextDir = if (voteDirection == 1) 0 else 1
                        voteDirection = nextDir
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.vote(post.resolvedFullname, nextDir)
                            }
                        }
                    },
                    onDownvote = {
                        val nextDir = if (voteDirection == -1) 0 else -1
                        voteDirection = nextDir
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.vote(post.resolvedFullname, nextDir)
                            }
                        }
                    }
                )
            }

            // Column 2 (Middle): Thread info layout — tapping toggles selection & action bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = clampedThumbnailDp.dp)
                    .clickable {
                        toggleExpanded()
                    }
                    .padding(
                        start = 5.dp,
                        end = 5.dp,
                        top = if (isOpHeaderInComments) 8.dp else 4.dp,
                        bottom = if (isOpHeaderInComments) 8.dp else 4.dp
                    ),
                verticalArrangement = Arrangement.Center
            ) {
                // Line 1: Flair/Tag + Title + (domain)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    if (flairLabel.isNotEmpty()) {
                        Surface(
                            color = RwfPalette.FlairBackgroundDark,
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = flairLabel,
                                color = RwfPalette.FlairTextDark,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (post.gilded > 0 || post.all_awardings.isNotEmpty()) {
                        RifAwardBadges(
                            gildedCount = post.gilded,
                            awardings = post.all_awardings,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    val domainTextColor = RwfPalette.DomainTextDark
                    val titleAnnotated = remember(
                        post.title,
                        domainText,
                        titleColor,
                        titleFontSizeSp,
                        domainTextColor
                    ) {
                        buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = titleColor,
                                    fontSize = titleFontSizeSp.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            ) {
                                append(post.title)
                            }
                            if (domainText.isNotEmpty()) {
                                append(" ")
                                withStyle(
                                    SpanStyle(
                                        color = domainTextColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                ) {
                                    append("($domainText)")
                                }
                            }
                        }
                    }
                    if (isOpHeaderInComments) {
                        SelectionContainer {
                            Text(
                                text = titleAnnotated,
                                lineHeight = 20.sp
                            )
                        }
                    } else {
                        Text(
                            text = titleAnnotated,
                            lineHeight = (titleFontSizeSp + 4).sp
                        )
                    }
                }

                // Line 2: [nsfw] [spoiler] "[num] comments" "[subreddit]"
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (post.over_18 && labelNsfw) {
                        Text(
                            text = "nsfw",
                            color = RwfPalette.NsfwRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (post.spoiler) {
                        Text(
                            text = "spoiler",
                            color = RwfPalette.SpoilerYellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isOpHeaderInComments) {
                        Text(
                            text = "posted to ${post.subreddit}",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 12.sp
                        )
                    } else {
                        // Tapping the comment count opens CommentsScreen
                        Text(
                            text = "${post.num_comments} comments",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                clickedLocally = true
                                onMarkRead?.invoke()
                                onPostClick(post)
                            }
                        )

                        Text(
                            text = post.subreddit,
                            color = RwfPalette.DomainTextDark,
                            fontSize = 12.sp
                        )
                    }
                }

                // Line 3: Timestamp & author (e.g., "7 hours ago by dont_quote_me_please")
                val relativeTime = remember(post.created_utc) { formatRelativeTime(post.created_utc) }
                Text(
                    text = buildString {
                        if (relativeTime.isNotEmpty()) {
                            append(relativeTime)
                            append(" ")
                        }
                        append("by ")
                        append(post.author)
                    },
                    color = RwfPalette.DomainTextDark,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }

            // Column 3 (Far Right): Scaled square thumbnail or placeholder icon
            if (!leftHandedMode && showCompactThumbnail) {
                RifRightThumbnailOrPlaceholder(
                    thumbnailUrl = thumbnailUrl,
                    isSelf = post.is_self,
                    isNsfw = post.over_18 && labelNsfw,
                    isSpoiler = post.spoiler,
                    isGallery = isGallery,
                    thumbnailSizeDp = clampedThumbnailDp,
                    onClick = {
                        clickedLocally = true
                        onMarkRead?.invoke()
                        onThumbnailClick(post)
                    }
                )
            } else if (leftHandedMode) {
                RifVoteColumn(
                    score = post.score + (voteDirection - initialVoteDirection),
                    voteDirection = voteDirection,
                    onUpvote = {
                        val nextDir = if (voteDirection == 1) 0 else 1
                        voteDirection = nextDir
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.vote(post.resolvedFullname, nextDir)
                            }
                        }
                    },
                    onDownvote = {
                        val nextDir = if (voteDirection == -1) 0 else -1
                        voteDirection = nextDir
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.vote(post.resolvedFullname, nextDir)
                            }
                        }
                    }
                )
            }
        }

        if (cardBannerUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(RwfPalette.DarkGraySurface)
                    .clickable {
                        clickedLocally = true
                        onMarkRead?.invoke()
                        onThumbnailClick(post)
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = cardBannerUrl,
                    contentDescription = "Post card media preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Animated slide-down RIF Action Bar underneath the post row when selected
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(durationMillis = 170)) +
                fadeIn(animationSpec = tween(durationMillis = 140)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 150)) +
                fadeOut(animationSpec = tween(durationMillis = 110))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RwfPalette.SelectedPostRowDark)
            ) {
                // Optional inline selftext preview box if self-post has body text
                if (post.is_self && post.selftext.isNotBlank()) {
                    if (!spoilerRevealed) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .background(RwfPalette.DarkGraySurface, RoundedCornerShape(2.dp))
                                .clickable { spoilerRevealed = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tap to reveal spoiler",
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .background(RwfPalette.CardBackgroundDark, RoundedCornerShape(2.dp))
                                .border(1.dp, RwfPalette.DividerLineDark, RoundedCornerShape(2.dp))
                                .clickable {
                                    clickedLocally = true
                                    onMarkRead?.invoke()
                                    onPostClick(post)
                                }
                                .padding(10.dp)
                        ) {
                            RifMarkdownText(
                                text = post.selftext.trim().lines().take(8).joinToString("\n"),
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                showInlineImages = showInlineImages,
                                onSubredditClick = onSelectSubreddit,
                                onUserClick = onOpenAuthorProfile,
                                onNonLinkClick = {
                                    clickedLocally = true
                                    onMarkRead?.invoke()
                                    onPostClick(post)
                                }
                            )
                        }
                    }
                }

                RifThreadActionStrip(
                    post = post,
                    isSaved = isSaved,
                    onShare = {
                        shareRedditPost(context, post)
                    },
                    onToggleSave = {
                        val nextSaved = !isSaved
                        isSaved = nextSaved
                        coroutineScope.launch {
                            runSuspendCatching {
                                if (nextSaved) {
                                    RedditNetworkClient.apiService.save(post.resolvedFullname)
                                } else {
                                    RedditNetworkClient.apiService.unsave(post.resolvedFullname)
                                }
                            }
                        }
                        val msg = if (nextSaved) "Saved post" else "Unsaved post"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    onHide = {
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.hide(post.resolvedFullname)
                            }
                        }
                        if (onHidePost != null) {
                            onHidePost(post)
                        } else {
                            toggleExpanded()
                        }
                        Toast.makeText(context, "Hidden post", Toast.LENGTH_SHORT).show()
                    },
                    onOpenComments = {
                        clickedLocally = true
                        onMarkRead?.invoke()
                        onPostClick(post)
                    },
                    onBlockSubreddit = onBlockSubreddit,
                    onSelectSubreddit = onSelectSubreddit,
                    onOpenAuthorProfile = onOpenAuthorProfile
                )
            }
        }

        // 1dp solid divider line below each row item (@color/gray_25 / #FF3F3F3F)
        if (!cardsView || isOpHeaderInComments) {
            HorizontalDivider(
                thickness = 1.dp,
                color = RwfPalette.DividerLineDark
            )
        }
    }
}

/**
 * Classic horizontal action row below a selected thread row:
 *
 * `share`      `save`      `hide`      `...`      `comments`
 */
@Composable
private fun RifThreadActionStrip(
    post: RedditPost,
    isSaved: Boolean,
    onShare: () -> Unit,
    onToggleSave: () -> Unit,
    onHide: () -> Unit,
    onOpenComments: () -> Unit,
    onBlockSubreddit: ((String) -> Unit)? = null,
    onSelectSubreddit: ((String) -> Unit)? = null,
    onOpenAuthorProfile: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showMoreDialog by remember { mutableStateOf(false) }

    if (showMoreDialog) {
        RifMoreActionsDialog(
            post = post,
            onDismiss = { showMoreDialog = false },
            onViewSubreddit = {
                showMoreDialog = false
                onSelectSubreddit?.invoke(post.subreddit)
            },
            onViewAuthorProfile = {
                showMoreDialog = false
                if (onOpenAuthorProfile != null) {
                    onOpenAuthorProfile(post.author)
                } else {
                    MediaResolver.openExternalLink(
                        context,
                        "https://www.reddit.com/user/${post.author}"
                    )
                }
            },
            onOpenLinkInBrowser = {
                showMoreDialog = false
                val targetUrl = post.url.ifBlank {
                    "https://www.reddit.com${post.permalink}"
                }
                MediaResolver.openSystemBrowser(context, targetUrl)
            },
            onOpenCommentsInBrowser = {
                showMoreDialog = false
                val permalinkUrl = if (post.permalink.startsWith("http")) {
                    post.permalink
                } else {
                    "https://www.reddit.com${post.permalink}"
                }
                MediaResolver.openSystemBrowser(context, permalinkUrl)
            },
            onReportPost = {
                showMoreDialog = false
                coroutineScope.launch {
                    val token = UserPreferencesRepository.getInstance(context)
                        .userAccessToken.first()
                    if (token.isNullOrBlank()) {
                        Toast.makeText(
                            context,
                            "Sign in via Settings to report posts to Reddit",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        runSuspendCatching {
                            RedditNetworkClient.apiService.reportThing(
                                thingId = post.resolvedFullname,
                                reason = "other"
                            ).close()
                        }.onSuccess {
                            Toast.makeText(context, "Reported post to Reddit", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(
                                context,
                                "Report failed: ${err.localizedMessage ?: "Error"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onBlockSubreddit = {
                showMoreDialog = false
                if (onBlockSubreddit != null) {
                    onBlockSubreddit(post.subreddit)
                } else {
                    Toast.makeText(context, "Filtered /r/${post.subreddit}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(RwfPalette.SelectedPostRowDark),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. share
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(onClick = onShare),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "share",
                color = RwfPalette.ActionStripTextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // 2. save
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(onClick = onToggleSave),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSaved) "unsave" else "save",
                color = if (isSaved) RwfPalette.AccentDarkTheme else RwfPalette.ActionStripTextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // 3. hide
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(onClick = onHide),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "hide",
                color = RwfPalette.ActionStripTextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // 4. ... (More actions dialog)
        Box(
            modifier = Modifier
                .weight(0.75f)
                .fillMaxSize()
                .clickable { showMoreDialog = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "...",
                color = RwfPalette.ActionStripTextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // 5. comments
        Box(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxSize()
                .clickable(onClick = onOpenComments),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "comments",
                color = RwfPalette.ActionStripTextDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * Centered modal dialog when `...` is tapped in the post action bar:
 * - View /r/{subreddit}
 * - {author}'s profile
 * - Open link in browser
 * - Open comments in browser
 * - Report post
 * - Block /r/{subreddit}
 */
@Composable
private fun RifMoreActionsDialog(
    post: RedditPost,
    onDismiss: () -> Unit,
    onViewSubreddit: () -> Unit,
    onViewAuthorProfile: () -> Unit,
    onOpenLinkInBrowser: () -> Unit,
    onOpenCommentsInBrowser: () -> Unit,
    onReportPost: () -> Unit,
    onBlockSubreddit: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                RifDialogOptionRow(
                    text = "View /r/${post.subreddit}",
                    onClick = onViewSubreddit
                )
                RifDialogOptionRow(
                    text = "${post.author}'s profile",
                    onClick = onViewAuthorProfile
                )
                RifDialogOptionRow(
                    text = "Open link in browser",
                    onClick = onOpenLinkInBrowser
                )
                RifDialogOptionRow(
                    text = "Open comments in browser",
                    onClick = onOpenCommentsInBrowser
                )
                RifDialogOptionRow(
                    text = "Report post",
                    onClick = onReportPost
                )
                RifDialogOptionRow(
                    text = "Block /r/${post.subreddit}",
                    onClick = onBlockSubreddit
                )
            }
        }
    }
}

@Composable
private fun RifDialogOptionRow(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = RwfPalette.TitleUnreadDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * Column 1: 44dp wide x 70dp tall voting column with:
 * - Upward triangle arrow (36dp x 22dp touch target)
 * - Bold 14sp score text
 * - Downward triangle arrow (36dp x 22dp touch target)
 */
@Composable
private fun RifVoteColumn(
    score: Int,
    voteDirection: Int,
    onUpvote: () -> Unit,
    onDownvote: () -> Unit
) {
    val scoreColor = when (voteDirection) {
        1 -> RwfPalette.VoteArrowUpvoteRed
        -1 -> RwfPalette.VoteArrowDownvoteBlue
        else -> RwfPalette.VoteArrowNeutralGray
    }

    val upArrowColor = if (voteDirection == 1) {
        RwfPalette.VoteArrowUpvoteRed
    } else {
        RwfPalette.VoteArrowNeutralGray
    }

    val downArrowColor = if (voteDirection == -1) {
        RwfPalette.VoteArrowDownvoteBlue
    } else {
        RwfPalette.VoteArrowNeutralGray
    }

    Column(
        modifier = Modifier
            .width(44.dp)
            .heightIn(min = 70.dp)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Up arrow triangle button
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(22.dp)
                .clickable(onClick = onUpvote),
            contentAlignment = Alignment.Center
        ) {
            VoteTriangleIcon(
                pointingUp = true,
                color = upArrowColor,
                modifier = Modifier.size(width = 14.dp, height = 8.dp)
            )
        }

        // Score text (14sp bold, singleLine, @color/arrow_gray)
        Text(
            text = formatScore(score),
            color = scoreColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp)
        )

        // Down arrow triangle button
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(22.dp)
                .clickable(onClick = onDownvote),
            contentAlignment = Alignment.Center
        ) {
            VoteTriangleIcon(
                pointingUp = false,
                color = downArrowColor,
                modifier = Modifier.size(width = 14.dp, height = 8.dp)
            )
        }
    }
}

@Composable
private fun VoteTriangleIcon(
    pointingUp: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            if (pointingUp) {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(path = path, color = color)
    }
}

/**
 * Column 3: Square 70dp x 70dp thumbnail or RIF self/spoiler/nsfw placeholder icon flush against the right edge.
 */
@Composable
private fun RifRightThumbnailOrPlaceholder(
    thumbnailUrl: String?,
    isSelf: Boolean,
    isNsfw: Boolean,
    isSpoiler: Boolean,
    isGallery: Boolean,
    thumbnailSizeDp: Int = 70,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val sizeDp = thumbnailSizeDp.dp
    var loadFailed by remember(thumbnailUrl) { mutableStateOf(false) }
    val placeholderBgColor = RwfPalette.DarkGraySurface
    val placeholderPainter = remember(placeholderBgColor) { ColorPainter(placeholderBgColor) }
    if (thumbnailUrl != null && !loadFailed) {
        val imageRequest = remember(thumbnailUrl) {
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .crossfade(true)
                .build()
        }
        Box(
            modifier = Modifier
                .size(sizeDp)
                .background(placeholderBgColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = "Post thumbnail",
                contentScale = ContentScale.Crop,
                placeholder = placeholderPainter,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize()
            )

            if (isGallery) {
                Surface(
                    color = Color(0xBF000000),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        text = "ALBUM",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    } else {
        RifThumbnailPlaceholderIcon(
            isSelf = isSelf,
            isNsfw = isNsfw,
            isSpoiler = isSpoiler,
            modifier = Modifier
                .size(sizeDp)
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun RifThumbnailPlaceholderIcon(
    isSelf: Boolean,
    isNsfw: Boolean,
    isSpoiler: Boolean,
    modifier: Modifier = Modifier
) {
    // Classic RIF Self / Spoiler / Default square placeholder icon
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (isSpoiler) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF616161)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "!",
                    color = Color(0xFF212121),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        } else if (isNsfw) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(2.dp, RwfPalette.NsfwRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "NSFW",
                    color = RwfPalette.NsfwRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Classic thinking Snoo / ? or ! placeholder from RIF
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Canvas(modifier = Modifier.size(34.dp)) {
                    val strokeColor = Color(0xFF757575)
                    // Antenna
                    drawLine(
                        color = strokeColor,
                        start = Offset(size.width * 0.5f, size.height * 0.25f),
                        end = Offset(size.width * 0.65f, size.height * 0.08f),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = strokeColor,
                        radius = size.width * 0.07f,
                        center = Offset(size.width * 0.72f, size.height * 0.08f)
                    )
                    // Head circle
                    drawCircle(
                        color = strokeColor,
                        radius = size.width * 0.34f,
                        center = Offset(size.width * 0.48f, size.height * 0.6f),
                        style = Stroke(width = 3f)
                    )
                }
                Text(
                    text = if (isSelf) "!" else "?",
                    color = Color(0xFF6699CC),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun shareRedditPost(context: Context, post: RedditPost) {
    val permalinkUrl = if (post.permalink.startsWith("http")) {
        post.permalink
    } else {
        "https://www.reddit.com${post.permalink}"
    }
    val shareText = "${post.title}\n$permalinkUrl"
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, post.title)
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share via"))
}

internal fun formatScore(score: Int): String {
    val absScore = abs(score)
    return when {
        absScore >= 100_000 -> String.format(Locale.US, "%dk", score / 1_000)
        absScore >= 10_000 -> String.format(Locale.US, "%.1fk", score / 1_000.0)
        else -> score.toString()
    }
}

internal fun formatCount(value: Int): String = formatScore(value)

private fun resolveThumbnailUrl(post: RedditPost, allowHighRes: Boolean = true): String? {
    if (post.spoiler) {
        return null
    }

    val sanitized = post.thumbnail?.trim()?.replace("&amp;", "&")
    if (!sanitized.isNullOrEmpty() &&
        sanitized.lowercase(Locale.US) !in NON_IMAGE_THUMBNAILS &&
        (sanitized.startsWith("http://") || sanitized.startsWith("https://"))
    ) {
        return sanitized
    }

    // Fallback to preview resolutions/source if thumbnail is a placeholder keyword or missing
    val previewResolution = post.preview?.images?.firstOrNull()?.let { img ->
        if (allowHighRes) {
            img.resolutions.firstOrNull { it.width >= 108 }?.url ?: img.source?.url
        } else {
            img.resolutions.firstOrNull()?.url ?: img.source?.url
        }
    }?.trim()?.replace("&amp;", "&")
    if (!previewResolution.isNullOrEmpty() &&
        (previewResolution.startsWith("http://") || previewResolution.startsWith("https://"))
    ) {
        return previewResolution
    }

    // Fallback to gallery media_metadata preview if thumbnail is missing/default on a gallery post
    val firstMedia = post.media_metadata?.values?.firstOrNull()
    val previewUrl = if (allowHighRes) {
        firstMedia?.p?.firstOrNull()?.u ?: firstMedia?.s?.u ?: firstMedia?.s?.gif
    } else {
        firstMedia?.p?.firstOrNull()?.u ?: firstMedia?.s?.u ?: firstMedia?.s?.gif
    }
    val sanitizedPreview = previewUrl?.trim()?.replace("&amp;", "&")
    if (!sanitizedPreview.isNullOrEmpty() &&
        (sanitizedPreview.startsWith("http://") || sanitizedPreview.startsWith("https://"))
    ) {
        return sanitizedPreview
    }

    // Fallback to direct image URL (i.redd.it, i.imgur.com, .jpg/.png/.webp/.gif)
    val directUrl = MediaResolver.extractDirectImageUrl(post)?.trim()?.replace("&amp;", "&")
    if (!directUrl.isNullOrEmpty() &&
        (directUrl.startsWith("http://") || directUrl.startsWith("https://"))
    ) {
        return directUrl
    }

    // Fallback to crosspost parent thumbnail
    val crosspost = post.crosspost_parent_list.firstOrNull()
    if (crosspost != null) {
        return resolveThumbnailUrl(crosspost, allowHighRes = allowHighRes)
    }

    return null
}

private fun resolveCardBannerUrl(post: RedditPost, allowHighRes: Boolean): String? {
    val previewSource = post.preview?.images?.firstOrNull()?.let { img ->
        if (allowHighRes) {
            img.source?.url ?: img.resolutions.lastOrNull()?.url
        } else {
            img.resolutions.firstOrNull { it.width >= 216 }?.url ?: img.resolutions.firstOrNull()?.url
        }
    }?.trim()?.replace("&amp;", "&")
    if (!previewSource.isNullOrEmpty() &&
        (previewSource.startsWith("http://") || previewSource.startsWith("https://"))
    ) {
        return previewSource
    }

    val firstMedia = post.media_metadata?.values?.firstOrNull()
    val mediaUrl = if (allowHighRes) {
        firstMedia?.s?.u ?: firstMedia?.p?.lastOrNull()?.u
    } else {
        firstMedia?.p?.firstOrNull()?.u
    }?.trim()?.replace("&amp;", "&")
    if (!mediaUrl.isNullOrEmpty() &&
        (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://"))
    ) {
        return mediaUrl
    }

    return resolveThumbnailUrl(post, allowHighRes = allowHighRes)
}

private fun resolveDomain(post: RedditPost): String {
    if (!post.domain.isNullOrBlank()) {
        return post.domain
    }
    if (post.is_self && post.subreddit.isNotBlank()) {
        return "self.${post.subreddit}"
    }
    return runCatching {
        Uri.parse(post.url).host?.removePrefix("www.") ?: ""
    }.getOrDefault("")
}

private fun formatRelativeTime(createdUtcSeconds: Double): String {
    if (createdUtcSeconds <= 0.0) return ""
    val nowSeconds = System.currentTimeMillis() / 1000L
    val diffSeconds = max(0L, nowSeconds - createdUtcSeconds.toLong())
    return when {
        diffSeconds < 60L -> "${diffSeconds} seconds ago"
        diffSeconds < 3600L -> {
            val mins = diffSeconds / 60L
            if (mins == 1L) "1 minute ago" else "$mins minutes ago"
        }
        diffSeconds < 86400L -> {
            val hours = diffSeconds / 3600L
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        diffSeconds < 2592000L -> {
            val days = diffSeconds / 86400L
            if (days == 1L) "1 day ago" else "$days days ago"
        }
        diffSeconds < 31536000L -> {
            val months = diffSeconds / 2592000L
            if (months == 1L) "1 month ago" else "$months months ago"
        }
        else -> {
            val years = diffSeconds / 31536000L
            if (years == 1L) "1 year ago" else "$years years ago"
        }
    }
}

/**
 * Renders inline Reddit awards (`all_awardings`) and gilding badges (`gilded`) next to post/comment metadata.
 */
@Composable
fun RifAwardBadges(
    gildedCount: Int,
    awardings: List<RedditAward>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (awardings.isNotEmpty()) {
            awardings.take(5).forEach { award ->
                val iconUrl = award.bestIconUrl
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (iconUrl.isNotBlank()) {
                        AsyncImage(
                            model = iconUrl,
                            contentDescription = award.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (award.count > 1) {
                        Text(
                            text = "x${award.count}",
                            color = RwfPalette.SpoilerYellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else if (gildedCount > 0) {
            Surface(
                color = Color(0xFF4A3D12),
                shape = CircleShape
            ) {
                Text(
                    text = if (gildedCount > 1) "★x$gildedCount" else "★",
                    color = RwfPalette.SpoilerYellow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

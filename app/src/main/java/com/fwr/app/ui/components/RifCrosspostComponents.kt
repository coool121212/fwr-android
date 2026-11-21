package com.fwr.app.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Compact single-line badge displayed in feed rows when a post is a crosspost.
 */
@Composable
fun RifCrosspostBadgeRow(
    parentSubreddit: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = RwfIcons.Crosspost,
            contentDescription = "Crossposted",
            tint = RwfPalette.CyanLinkDark,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = if (!parentSubreddit.isNullOrBlank()) {
                "crossposted from /r/$parentSubreddit"
            } else {
                "crossposted"
            },
            color = RwfPalette.CyanLinkDark,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Embedded card displaying the original crossposted thread's subreddit, title, metadata,
 * optional domain cue, and optional selftext (with spoiler reveal and detail collapse support).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RifCrosspostPreviewCard(
    parent: RedditPost?,
    fallbackParentId: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    showParentSelftext: Boolean = false,
    selectable: Boolean = false,
    showInlineImages: Boolean = true,
    labelNsfw: Boolean = true,
    spoilerRevealed: Boolean = true,
    onRevealSpoiler: (() -> Unit)? = null,
    onOpenOriginalPost: () -> Unit,
    onSelectSubreddit: ((String) -> Unit)? = null,
    onOpenAuthorProfile: ((String) -> Unit)? = null
) {
    if (parent == null && fallbackParentId.isNullOrBlank()) return

    var parentSelftextCollapsed by remember(parent?.id) { mutableStateOf(false) }

    Surface(
        color = RwfPalette.CardBackgroundDark,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenOriginalPost)
    ) {
        if (parent == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = RwfIcons.Crosspost,
                        contentDescription = "Crosspost",
                        tint = RwfPalette.AccentDarkTheme,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Crossposted post ($fallbackParentId)",
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Original thread →",
                    color = RwfPalette.CyanLinkDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header: icon + Crossposted from /r/{sub} + Original thread ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = RwfIcons.Crosspost,
                    contentDescription = "Crosspost",
                    tint = RwfPalette.AccentDarkTheme,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Crossposted from ",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 11.sp
                )
                if (parent.subreddit.isNotBlank()) {
                    Text(
                        text = "/r/${parent.subreddit}",
                        color = RwfPalette.CyanLinkDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = if (onSelectSubreddit != null) {
                            Modifier.clickable { onSelectSubreddit(parent.subreddit) }
                        } else {
                            Modifier
                        }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Original thread →",
                    color = RwfPalette.CyanLinkDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onOpenOriginalPost)
                )
            }

            // Original post title
            if (parent.title.isNotBlank()) {
                val titleText: @Composable () -> Unit = {
                    Text(
                        text = parent.title,
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = if (compact) 13.sp else 14.sp,
                        lineHeight = if (compact) 17.sp else 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (compact) 3 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectable) {
                    SelectionContainer { titleText() }
                } else {
                    titleText()
                }
            }

            // Metadata row
            val relTime = remember(parent.created_utc) { formatCrosspostRelativeTime(parent.created_utc) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                if (parent.over_18 && labelNsfw) {
                    Text(
                        text = "nsfw",
                        color = RwfPalette.NsfwRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (parent.spoiler) {
                    Text(
                        text = "spoiler",
                        color = RwfPalette.SpoilerYellow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!parent.link_flair_text.isNullOrBlank()) {
                    Surface(
                        color = RwfPalette.FlairBackgroundDark,
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            text = parent.link_flair_text.trim(),
                            color = RwfPalette.FlairTextDark,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "${formatScore(parent.score)} pts • ${parent.num_comments} comments",
                    color = RwfPalette.DomainTextDark,
                    fontSize = 11.sp
                )
                val timePart = if (relTime.isNotEmpty()) "$relTime • " else ""
                val authorText = "${timePart}by u/${parent.author}"
                val canOpenAuthor = onOpenAuthorProfile != null &&
                    parent.author.isNotBlank() &&
                    parent.author != "[deleted]"
                Text(
                    text = authorText,
                    color = if (canOpenAuthor) RwfPalette.CyanLinkDark else RwfPalette.DomainTextDark,
                    fontSize = 11.sp,
                    modifier = if (canOpenAuthor) {
                        Modifier.clickable { onOpenAuthorProfile!!.invoke(parent.author) }
                    } else {
                        Modifier
                    }
                )
            }

            // External domain hint when original post is a link post
            if (!parent.is_self) {
                val parentDomain = remember(parent.id, parent.domain, parent.url) {
                    parent.domain?.takeIf { it.isNotBlank() }
                        ?: runCatching { Uri.parse(parent.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
                }
                if (parentDomain.isNotBlank()) {
                    Text(
                        text = "($parentDomain)",
                        color = RwfPalette.DomainTextDark,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Optional parent selftext body
            if (showParentSelftext && parent.is_self && parent.selftext.isNotBlank()) {
                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                if (parent.spoiler && !spoilerRevealed) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .background(RwfPalette.DarkGraySurface, RoundedCornerShape(2.dp))
                            .clickable { onRevealSpoiler?.invoke() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tap to reveal spoiler",
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (compact) {
                    RifMarkdownText(
                        text = parent.selftext.trim().lines().take(6).joinToString("\n"),
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        mediaMetadata = parent.media_metadata,
                        showInlineImages = showInlineImages,
                        onSubredditClick = onSelectSubreddit,
                        onUserClick = onOpenAuthorProfile,
                        onNonLinkClick = onOpenOriginalPost
                    )
                } else if (parentSelftextCollapsed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { parentSelftextCollapsed = false }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "(original selftext collapsed — tap to expand)",
                            color = RwfPalette.MetadataTextDark,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand original selftext",
                            tint = RwfPalette.MetadataTextDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Collapse original selftext",
                                tint = RwfPalette.MetadataTextDark,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { parentSelftextCollapsed = true }
                            )
                        }
                        RifMarkdownText(
                            text = parent.selftext,
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            mediaMetadata = parent.media_metadata,
                            selectable = selectable,
                            showInlineImages = showInlineImages,
                            onSubredditClick = onSelectSubreddit,
                            onUserClick = onOpenAuthorProfile,
                            onNonLinkClick = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modal dialog for submitting a crosspost of [sourcePost] to another subreddit via `POST /api/submit`.
 */
@Composable
fun RifCrosspostSubmitDialog(
    sourcePost: RedditPost,
    activeUsername: String,
    onDismiss: () -> Unit,
    onCrossposted: (targetSub: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()

    var targetSub by remember(sourcePost.id) { mutableStateOf("") }
    var crosspostTitle by remember(sourcePost.id) { mutableStateOf(sourcePost.title) }
    var sendRepliesToInbox by remember(sourcePost.id) { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = RwfPalette.CardBackgroundDark,
        titleContentColor = RwfPalette.TitleUnreadDark,
        title = { Text("Crosspost") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Posting as $activeUsername",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 12.sp
                )
                Surface(
                    color = RwfPalette.DarkGraySurface,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Original in /r/${sourcePost.subreddit}",
                            color = RwfPalette.CyanLinkDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = sourcePost.title,
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                OutlinedTextField(
                    value = targetSub,
                    onValueChange = { targetSub = it },
                    label = { Text("Destination subreddit (without /r/)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = crosspostTitle,
                    onValueChange = { crosspostTitle = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sendRepliesToInbox = !sendRepliesToInbox }
                ) {
                    Checkbox(
                        checked = sendRepliesToInbox,
                        onCheckedChange = { sendRepliesToInbox = it }
                    )
                    Text(
                        text = "Send replies to my inbox",
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSubmitting && targetSub.isNotBlank() && crosspostTitle.isNotBlank(),
                onClick = {
                    if (authPrefs.userAccessToken.isNullOrBlank()) {
                        Toast.makeText(
                            context,
                            "Sign in via Manage accounts / BYOK Settings to crosspost",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@TextButton
                    }
                    val cleanSub = targetSub.trim().trimStart('/')
                        .let { s -> if (s.startsWith("r/", ignoreCase = true)) s.substring(2) else s }
                        .trim('/')
                    if (cleanSub.isEmpty()) return@TextButton
                    isSubmitting = true
                    coroutineScope.launch {
                        runSuspendCatching {
                            val responseBody = RedditNetworkClient.apiService.submitPost(
                                subreddit = cleanSub,
                                kind = "crosspost",
                                title = crosspostTitle.trim(),
                                sendReplies = sendRepliesToInbox,
                                crosspostFullname = sourcePost.resolvedFullname
                            ).use { it.string() }
                            RedditOAuthManager.extractRedditApiError(responseBody)?.let { apiErr ->
                                throw IllegalStateException(apiErr)
                            }
                            responseBody
                        }.onSuccess {
                            isSubmitting = false
                            Toast.makeText(context, "Crossposted to /r/$cleanSub", Toast.LENGTH_SHORT).show()
                            onCrossposted(cleanSub)
                        }.onFailure { err ->
                            isSubmitting = false
                            if (err.message?.contains("403") == true) {
                                Toast.makeText(
                                    context,
                                    "Submit permission required — opening Reddit authorization…",
                                    Toast.LENGTH_LONG
                                ).show()
                                RedditOAuthManager.launchReauthorization(context, prefsRepo)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Crosspost failed: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            ) {
                Text(
                    text = if (isSubmitting) "Crossposting…" else "Crosspost",
                    color = RwfPalette.AccentDarkTheme
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = onDismiss
            ) {
                Text("Cancel", color = RwfPalette.DomainTextDark)
            }
        }
    )
}

private fun formatCrosspostRelativeTime(createdUtcSeconds: Double): String {
    if (createdUtcSeconds <= 0.0) return ""
    val nowSeconds = System.currentTimeMillis() / 1000L
    val diffSeconds = max(0L, nowSeconds - createdUtcSeconds.toLong())
    return when {
        diffSeconds < 60L -> "${diffSeconds}s ago"
        diffSeconds < 3600L -> "${diffSeconds / 60L}m ago"
        diffSeconds < 86400L -> "${diffSeconds / 3600L}h ago"
        diffSeconds < 2592000L -> "${diffSeconds / 86400L}d ago"
        diffSeconds < 31536000L -> "${diffSeconds / 2592000L}mo ago"
        else -> "${diffSeconds / 31536000L}y ago"
    }
}

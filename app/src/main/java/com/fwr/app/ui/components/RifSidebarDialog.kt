package com.fwr.app.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.model.SubredditAbout
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Subreddit Sidebar Dialog:
 * - Header: `/r/{subreddit}` and `"{subscribers} subscribers • {active} online"`
 * - 3 equal-width action tiles:
 *   1. `[ +frontpage ]` (toggle subscription)
 *   2. `[ Share ]`
 *   3. `[ Moderators ]`
 * - Divider + scrollable sidebar markdown description.
 */
@Composable
fun RifSidebarDialog(
    subreddit: String,
    onDismiss: () -> Unit,
    onSelectSubreddit: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()

    val cleanSub = subreddit.trim().removePrefix("/r/").removePrefix("r/")
    var about by remember(cleanSub) { mutableStateOf<SubredditAbout?>(null) }
    var isLoading by remember(cleanSub) { mutableStateOf(true) }
    var errorText by remember(cleanSub) { mutableStateOf<String?>(null) }
    var isSubscribed by remember(cleanSub) { mutableStateOf(false) }

    LaunchedEffect(cleanSub) {
        if (cleanSub.equals("all", ignoreCase = true) ||
            cleanSub.equals("popular", ignoreCase = true) ||
            cleanSub.equals("frontpage", ignoreCase = true)
        ) {
            about = SubredditAbout(
                display_name = cleanSub,
                title = "/r/$cleanSub",
                description = "Aggregate Reddit feed: /r/$cleanSub."
            )
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        errorText = null
        runSuspendCatching {
            RedditNetworkClient.apiService.getSubredditAbout(cleanSub)
        }.onSuccess { wrapper ->
            about = wrapper.data
            isSubscribed = wrapper.data.user_is_subscriber
            isLoading = false
        }.onFailure { err ->
            errorText = err.localizedMessage ?: "Failed to load /r/$cleanSub sidebar"
            isLoading = false
        }
    }

    val numFormat = remember { NumberFormat.getIntegerInstance(Locale.US) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "/r/${about?.display_name?.ifBlank { cleanSub } ?: cleanSub}",
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val countLabel = if (isLoading) {
                            "Loading subscribers…"
                        } else if (about != null && about!!.subscribers > 0) {
                            val active = about!!.accounts_active ?: about!!.active_user_count
                            buildString {
                                append("${numFormat.format(about!!.subscribers)} subscribers")
                                if (active != null) {
                                    append(" • ${numFormat.format(active)} online")
                                }
                            }
                        } else {
                            about?.title ?: "Subreddit info"
                        }
                        Text(
                            text = countLabel,
                            color = RwfPalette.DomainTextDark,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close sidebar",
                            tint = RwfPalette.TitleUnreadDark
                        )
                    }
                }

                // 3-Button Row: +frontpage | Share | Moderators
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SidebarActionTile(
                        icon = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                        label = if (isSubscribed) "-frontpage" else "+frontpage",
                        tint = if (isSubscribed) RwfPalette.AccentDarkTheme else RwfPalette.TitleUnreadDark,
                        onClick = {
                            val nextSub = !isSubscribed
                            isSubscribed = nextSub
                            coroutineScope.launch {
                                prefsRepo.toggleLocalSubscription(cleanSub, nextSub)
                                runSuspendCatching {
                                    RedditNetworkClient.apiService.setSubredditSubscription(
                                        action = if (nextSub) "sub" else "unsub",
                                        subredditName = cleanSub
                                    )
                                }
                            }
                            Toast.makeText(
                                context,
                                if (nextSub) "Subscribed to /r/$cleanSub" else "Unsubscribed from /r/$cleanSub",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    SidebarActionTile(
                        icon = Icons.Default.Share,
                        label = "Share",
                        tint = RwfPalette.TitleUnreadDark,
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://www.reddit.com/r/$cleanSub")
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share /r/$cleanSub"))
                        },
                        modifier = Modifier.weight(1f)
                    )

                    SidebarActionTile(
                        icon = Icons.Default.Person,
                        label = "Moderators",
                        tint = RwfPalette.TitleUnreadDark,
                        onClick = {
                            MediaResolver.openExternalLink(
                                context,
                                "https://www.reddit.com/r/$cleanSub/about/moderators"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
                        }
                    }
                    errorText != null -> {
                        Text(
                            text = errorText!!,
                            color = RwfPalette.VoteArrowUpvoteRed,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    else -> {
                        val bodyMarkdown = about?.description?.ifBlank { about?.public_description }
                            ?: "No sidebar description."
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            RifMarkdownText(
                                text = bodyMarkdown,
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                showInlineImages = appSettings.showInlineImages,
                                onSubredditClick = { sub ->
                                    onDismiss()
                                    onSelectSubreddit?.invoke(sub)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp
        )
    }
}

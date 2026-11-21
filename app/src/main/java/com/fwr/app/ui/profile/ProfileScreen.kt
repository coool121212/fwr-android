package com.fwr.app.ui.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.MultiredditUtils
import com.fwr.app.data.model.RedditAward
import com.fwr.app.data.model.RedditComment
import com.fwr.app.data.model.RedditMultireddit
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.model.TrophyItem
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.components.PostCard
import com.fwr.app.ui.components.RifAwardBadges
import com.fwr.app.ui.components.RifMarkdownText
import com.fwr.app.ui.components.RwfIcons
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

private data class RifProfileSectionOption(
    val labelTemplate: String,
    val endpointSection: String
) {
    fun formatLabel(username: String): String = labelTemplate.replace("{username}", username)
}

private val PUBLIC_PROFILE_SECTION_OPTIONS = listOf(
    RifProfileSectionOption("overview for {username}", "overview"),
    RifProfileSectionOption("comments by {username}", "comments"),
    RifProfileSectionOption("submitted by {username}", "submitted")
)

private val PRIVATE_OWN_PROFILE_SECTION_OPTIONS = listOf(
    RifProfileSectionOption("upvoted by {username}", "upvoted"),
    RifProfileSectionOption("downvoted by {username}", "downvoted"),
    RifProfileSectionOption("hidden by {username}", "hidden"),
    RifProfileSectionOption("saved by {username}", "saved")
)

/**
 * Full 1:1 RIF Dark Theme User Profile Screen matching RIF's classic `ProfileActivity`:
 * - Top App Bar: Left back arrow, title dropdown spinner (`overview for {username} ▼`),
 *   right envelope icon (send message) and 3-dot overflow menu.
 * - Karma Header Row: Two equal columns centered (`link_karma` over `post karma`, `comment_karma` over `comment karma`).
 * - Trophy Case Section: `"{count} trophies"` grey header and horizontal `LazyRow` of 70x70dp trophy icons,
 *   white title, and grey subtitle (`description`).
 * - Mixed Overview Feed: Renders `t1` (`RedditComment`) cards in RIF's profile comment format and
 *   `t3` (`RedditPost`) items using the compact 3-column [PostCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    username: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPostClick: (RedditPost) -> Unit = {},
    onThumbnailClick: (RedditPost) -> Unit = {},
    onSelectSubreddit: (String) -> Unit = {},
    onOpenAuthorProfile: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()

    val cleanUsername = username.removePrefix("/u/").removePrefix("u/").trim().ifEmpty {
        authPrefs.username ?: "reddit"
    }

    val isLoggedIn = !authPrefs.userAccessToken.isNullOrBlank()
    val isOwnAuthenticatedProfile = isLoggedIn &&
        !authPrefs.username.isNullOrBlank() &&
        cleanUsername.equals(authPrefs.username, ignoreCase = true)
    val visibleSectionOptions = remember(isOwnAuthenticatedProfile) {
        if (isOwnAuthenticatedProfile) {
            PUBLIC_PROFILE_SECTION_OPTIONS + PRIVATE_OWN_PROFILE_SECTION_OPTIONS
        } else {
            PUBLIC_PROFILE_SECTION_OPTIONS
        }
    }

    var selectedSection by remember(cleanUsername) {
        mutableStateOf(PUBLIC_PROFILE_SECTION_OPTIONS.first())
    }
    LaunchedEffect(visibleSectionOptions) {
        if (selectedSection !in visibleSectionOptions) {
            selectedSection = visibleSectionOptions.first()
        }
    }
    var sectionDropdownExpanded by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showComposeMessageDialog by remember { mutableStateOf(false) }

    var linkKarma by remember(cleanUsername) {
        mutableIntStateOf(if (cleanUsername.equals(authPrefs.username, ignoreCase = true)) authPrefs.linkKarma else 0)
    }
    var commentKarma by remember(cleanUsername) {
        mutableIntStateOf(if (cleanUsername.equals(authPrefs.username, ignoreCase = true)) authPrefs.commentKarma else 0)
    }
    var trophies by remember(cleanUsername) { mutableStateOf<List<TrophyItem>>(emptyList()) }
    var userMultireddits by remember(cleanUsername) { mutableStateOf<List<RedditMultireddit>>(emptyList()) }
    var historyItems by remember(cleanUsername, selectedSection) { mutableStateOf<List<Any>>(emptyList()) }
    var isLoading by remember(cleanUsername, selectedSection) { mutableStateOf(true) }
    var isRefreshing by remember(cleanUsername, selectedSection) { mutableStateOf(false) }
    var errorMessage by remember(cleanUsername, selectedSection) { mutableStateOf<String?>(null) }
    var expandedPostId by remember { mutableStateOf<String?>(null) }

    fun loadProfileData(isSwipeRefresh: Boolean = false) {
        if (isSwipeRefresh && historyItems.isNotEmpty()) {
            isRefreshing = true
        } else {
            isLoading = true
        }
        errorMessage = null
        coroutineScope.launch {
            val aboutDeferred = async {
                runSuspendCatching { RedditNetworkClient.apiService.getUserAbout(cleanUsername) }
            }
            val trophiesDeferred = async {
                runSuspendCatching { RedditNetworkClient.apiService.getUserTrophies(cleanUsername) }
            }
            val multisDeferred = async {
                runSuspendCatching {
                    val element = if (isOwnAuthenticatedProfile) {
                        RedditNetworkClient.apiService.getMyMultireddits()
                    } else {
                        RedditNetworkClient.apiService.getUserMultireddits(cleanUsername)
                    }
                    MultiredditUtils.parseMultiredditsJson(element)
                }
            }
            val sectionDeferred = async {
                runSuspendCatching {
                    RedditNetworkClient.apiService.getUserProfileSection(
                        username = cleanUsername,
                        section = selectedSection.endpointSection
                    )
                }
            }

            aboutDeferred.await().onSuccess { wrapper ->
                val user = wrapper.data
                linkKarma = user.link_karma
                commentKarma = user.comment_karma
                if (cleanUsername.equals(authPrefs.username, ignoreCase = true) && user.name.isNotBlank()) {
                    prefsRepo.saveUserIdentity(
                        username = user.name,
                        linkKarma = user.link_karma,
                        commentKarma = user.comment_karma
                    )
                }
            }

            trophiesDeferred.await().onSuccess { response ->
                trophies = response.data.trophies.map { it.data }.filter { it.name.isNotBlank() }
            }

            multisDeferred.await().onSuccess { multis ->
                userMultireddits = multis
            }

            sectionDeferred.await()
                .onSuccess { listing ->
                    historyItems = listing.data.children.mapNotNull { child ->
                        when (val item = child.data) {
                            is RedditComment -> item
                            is RedditPost -> item
                            else -> null
                        }
                    }
                    isLoading = false
                    isRefreshing = false
                }
                .onFailure { err ->
                    val errMsg = err.localizedMessage ?: "Failed to load profile history"
                    if (isSwipeRefresh && historyItems.isNotEmpty()) {
                        Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        errorMessage = errMsg
                    }
                    isLoading = false
                    isRefreshing = false
                }
        }
    }

    LaunchedEffect(cleanUsername, selectedSection) {
        loadProfileData()
    }

    if (showComposeMessageDialog) {
        var subject by remember { mutableStateOf("") }
        var messageBody by remember { mutableStateOf("") }
        var isSendingMessage by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!isSendingMessage) showComposeMessageDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Send message to /u/$cleanUsername") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = messageBody,
                        onValueChange = { messageBody = it },
                        label = { Text("Message") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSendingMessage && subject.isNotBlank() && messageBody.isNotBlank(),
                    onClick = {
                        if (authPrefs.userAccessToken.isNullOrBlank()) {
                            showComposeMessageDialog = false
                            Toast.makeText(
                                context,
                                "Sign in via Manage accounts / BYOK Settings to send messages",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }
                        isSendingMessage = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                val responseBody = RedditNetworkClient.apiService.composeMessage(
                                    recipient = cleanUsername,
                                    subject = subject.trim(),
                                    body = messageBody.trim()
                                ).use { it.string() }
                                RedditOAuthManager.extractRedditApiError(responseBody)?.let { apiErr ->
                                    throw IllegalStateException(apiErr)
                                }
                                responseBody
                            }.onSuccess {
                                isSendingMessage = false
                                showComposeMessageDialog = false
                                Toast.makeText(
                                    context,
                                    "Message sent to /u/$cleanUsername",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { err ->
                                isSendingMessage = false
                                Toast.makeText(
                                    context,
                                    "Failed: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isSendingMessage) "Sending…" else "Send",
                        color = RwfPalette.AccentDarkTheme
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSendingMessage,
                    onClick = { showComposeMessageDialog = false }
                ) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    val backgroundColor = RwfPalette.BackgroundMaterialDark
    val topBarContentColor = if (RwfPalette.PrimaryDarkTheme.luminance() > 0.5f) {
        Color(0xFF1A1A1A)
    } else {
        Color.White
    }

    val numberFormatter = remember { NumberFormat.getIntegerInstance(Locale.US) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = topBarContentColor
                        )
                    }
                },
                title = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { sectionDropdownExpanded = true }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedSection.formatLabel(cleanUsername),
                                color = topBarContentColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select profile section",
                                tint = topBarContentColor
                            )
                        }

                        DropdownMenu(
                            expanded = sectionDropdownExpanded,
                            onDismissRequest = { sectionDropdownExpanded = false },
                            modifier = Modifier
                                .width(260.dp)
                                .background(RwfPalette.CardBackgroundDark)
                        ) {
                            visibleSectionOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.formatLabel(cleanUsername),
                                            color = RwfPalette.TitleUnreadDark,
                                            fontSize = 16.sp
                                        )
                                    },
                                    onClick = {
                                        sectionDropdownExpanded = false
                                        selectedSection = option
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isLoggedIn) {
                        IconButton(onClick = { showComposeMessageDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = "Send message",
                                tint = topBarContentColor
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = topBarContentColor
                            )
                        }
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false },
                            modifier = Modifier
                                .width(220.dp)
                                .background(RwfPalette.CardBackgroundDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Refresh", color = RwfPalette.TitleUnreadDark, fontSize = 16.sp) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    loadProfileData()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Open in browser", color = RwfPalette.TitleUnreadDark, fontSize = 16.sp) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    MediaResolver.openSystemBrowser(
                                        context,
                                        "https://www.reddit.com/user/$cleanUsername"
                                    )
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RwfPalette.PrimaryDarkTheme,
                    titleContentColor = topBarContentColor,
                    navigationIconContentColor = topBarContentColor,
                    actionIconContentColor = topBarContentColor
                )
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadProfileData(isSwipeRefresh = true) },
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
            // 1. Karma Header Row (Post Karma & Comment Karma) + Trophy Case
            if (selectedSection.endpointSection == "overview") {
                item(key = "profile_karma_and_trophies", contentType = "profile_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(backgroundColor)
                            .padding(top = 12.dp, bottom = 8.dp)
                    ) {
                        // Two equal columns centered at the top
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = numberFormatter.format(linkKarma),
                                    color = RwfPalette.TitleUnreadDark,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "post karma",
                                    color = RwfPalette.DomainTextDark,
                                    fontSize = 13.sp
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = numberFormatter.format(commentKarma),
                                    color = RwfPalette.TitleUnreadDark,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "comment karma",
                                    color = RwfPalette.DomainTextDark,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // Trophy Case Section
                        if (trophies.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${trophies.size} ${if (trophies.size == 1) "trophy" else "trophies"}",
                                color = RwfPalette.DomainTextDark,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    items = trophies,
                                    key = { trophy -> "${trophy.id ?: trophy.name}_${trophy.description}" },
                                    contentType = { "trophy" }
                                ) { trophy ->
                                    RifTrophyCard(trophy = trophy)
                                }
                            }
                        }

                        // Public / Own Multireddits Section
                        if (userMultireddits.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${userMultireddits.size} ${if (userMultireddits.size == 1) "multireddit" else "multireddits"}",
                                color = RwfPalette.DomainTextDark,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = userMultireddits,
                                    key = { multi -> multi.canonicalFeedKey },
                                    contentType = { "profile_multi" }
                                ) { multi ->
                                    Surface(
                                        color = RwfPalette.CardBackgroundDark,
                                        shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
                                        modifier = Modifier.clickable {
                                            onSelectSubreddit(multi.canonicalFeedKey)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = RwfIcons.Multireddit,
                                                contentDescription = null,
                                                tint = RwfPalette.AccentDarkTheme,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = multi.resolvedDisplayName,
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "(${multi.subredditNames.size})",
                                                color = RwfPalette.DomainTextDark,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                    }
                }
            }

            // 2. Loading / Error / Mixed History Listing (t1 Comments + t3 Posts)
            if (isLoading) {
                item(key = "profile_loading", contentType = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
                    }
                }
            } else if (errorMessage != null) {
                item(key = "profile_error", contentType = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "Failed to load profile",
                            color = RwfPalette.VoteArrowUpvoteRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { loadProfileData() }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (historyItems.isEmpty()) {
                item(key = "profile_empty", contentType = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "there doesn't seem to be anything here",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(
                    items = historyItems,
                    key = { item ->
                        when (item) {
                            is RedditComment -> "t1_${item.id}"
                            is RedditPost -> "t3_${item.id}"
                            else -> item.hashCode().toString()
                        }
                    },
                    contentType = { item ->
                        when (item) {
                            is RedditComment -> "profile_comment"
                            is RedditPost -> "profile_post"
                            else -> "unknown"
                        }
                    }
                ) { item ->
                    when (item) {
                        is RedditComment -> {
                            RifProfileCommentCard(
                                comment = item,
                                amoledBlackBackground = appSettings.amoledBlackBackground,
                                showInlineImages = appSettings.showInlineImages,
                                onSelectSubreddit = onSelectSubreddit,
                                onOpenAuthorProfile = onOpenAuthorProfile,
                                onClick = {
                                    val articleId = item.link_id?.removePrefix("t3_").orEmpty()
                                    if (articleId.isNotBlank()) {
                                        onPostClick(
                                            RedditPost(
                                                id = articleId,
                                                title = item.link_title ?: "Post in /r/${item.subreddit}",
                                                subreddit = item.subreddit,
                                                author = item.author,
                                                permalink = item.link_permalink ?: "/r/${item.subreddit}/comments/$articleId"
                                            )
                                        )
                                    }
                                }
                            )
                        }

                        is RedditPost -> {
                            val postKey = item.id.ifEmpty { item.permalink }
                            PostCard(
                                post = item,
                                isExpanded = expandedPostId == postKey,
                                onToggleExpand = {
                                    expandedPostId = if (expandedPostId == postKey) null else postKey
                                },
                                onPostClick = onPostClick,
                                onThumbnailClick = onThumbnailClick,
                                onSelectSubreddit = onSelectSubreddit,
                                onOpenAuthorProfile = onOpenAuthorProfile,
                                onOpenCrosspostParent = onPostClick,
                                amoledBlackBackground = appSettings.amoledBlackBackground,
                                leftHandedMode = appSettings.leftHandedMode,
                                showLinkFlair = appSettings.showLinkFlair,
                                showInlineImages = appSettings.showInlineImages
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Renders a single trophy item in the horizontal trophy case:
 * 70x70dp icon (`icon_70`), white trophy name below, and optional grey subtitle (`description`).
 */
@Composable
private fun RifTrophyCard(trophy: TrophyItem) {
    Column(
        modifier = Modifier.widthIn(min = 76.dp, max = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val iconUrl = trophy.icon_70.ifBlank { trophy.icon_40 }
        if (iconUrl.isNotBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = trophy.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(70.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(RwfPalette.DarkGraySurface, RoundedCornerShape(4.dp))
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = trophy.name,
            color = RwfPalette.TitleUnreadDark,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
        if (!trophy.description.isNullOrBlank()) {
            Text(
                text = trophy.description,
                color = RwfPalette.DomainTextDark,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp
            )
        }
    }
}

/**
 * Exact 1:1 RIF profile comment card (`t1` item in user overview/comments listing):
 * - Header line: orange upvote arrow + `"commented · {link_title}"` in italic light grey.
 * - Subheader: `"{score} points {relative_time} /r/{subreddit}"` (plus author flair & awards if present).
 * - Body: Comment body text in white.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RifProfileCommentCard(
    comment: RedditComment,
    amoledBlackBackground: Boolean,
    showInlineImages: Boolean,
    onSelectSubreddit: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onClick: () -> Unit
) {
    val bgColor = if (amoledBlackBackground) Color.Black else RwfPalette.BackgroundMaterialDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Line 1: Orange upvote arrow + "commented · {link_title}" in italic light grey
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 3.dp)
        ) {
            Canvas(modifier = Modifier.size(width = 11.dp, height = 7.dp)) {
                val path = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = path, color = RwfPalette.VoteArrowUpvoteRed)
            }
            Spacer(modifier = Modifier.width(6.dp))
            val linkTitle = comment.link_title?.takeIf { it.isNotBlank() } ?: comment.subreddit
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = RwfPalette.MetadataTextDark,
                            fontStyle = FontStyle.Italic,
                            fontSize = 13.sp
                        )
                    ) {
                        append("commented · ")
                        append(linkTitle)
                    }
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Line 2: "{score} points {relative_time} /r/{subreddit}" + optional flair & awards
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            val ptsLabel = if (comment.score == 1) "1 point" else "${comment.score} points"
            val relTime = formatProfileRelativeTime(comment.created_utc)
            val subLabel = if (comment.subreddit.isNotBlank()) "/r/${comment.subreddit}" else ""

            Text(
                text = listOf(ptsLabel, relTime, subLabel).filter { it.isNotBlank() }.joinToString(" "),
                color = RwfPalette.DomainTextDark,
                fontSize = 12.sp
            )

            if (!comment.author_flair_text.isNullOrBlank()) {
                Surface(
                    color = RwfPalette.FlairBackgroundDark,
                    shape = RoundedCornerShape(2.dp)
                ) {
                    Text(
                        text = comment.author_flair_text.trim(),
                        color = RwfPalette.FlairTextDark,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            if (comment.gilded > 0 || comment.all_awardings.isNotEmpty()) {
                RifAwardBadges(
                    gildedCount = comment.gilded,
                    awardings = comment.all_awardings
                )
            }
        }

        // Line 3: Comment body rendered as Reddit Markdown
        RifMarkdownText(
            text = comment.body.trim(),
            color = RwfPalette.TitleUnreadDark,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            mediaMetadata = comment.media_metadata,
            showInlineImages = showInlineImages,
            onSubredditClick = onSelectSubreddit,
            onUserClick = onOpenAuthorProfile,
            onNonLinkClick = onClick
        )
    }

    HorizontalDivider(
        thickness = 1.dp,
        color = RwfPalette.DividerLineDark
    )
}

private fun formatProfileRelativeTime(createdUtcSeconds: Double): String {
    if (createdUtcSeconds <= 0.0) return ""
    val nowSeconds = System.currentTimeMillis() / 1000L
    val diffSeconds = max(0L, nowSeconds - createdUtcSeconds.toLong())
    return when {
        diffSeconds < 60L -> "${diffSeconds} seconds ago"
        diffSeconds < 3600L -> {
            val m = diffSeconds / 60L
            if (m == 1L) "1 minute ago" else "$m minutes ago"
        }
        diffSeconds < 86400L -> {
            val h = diffSeconds / 3600L
            if (h == 1L) "1 hour ago" else "$h hours ago"
        }
        diffSeconds < 2592000L -> {
            val d = diffSeconds / 86400L
            if (d == 1L) "1 day ago" else "$d days ago"
        }
        diffSeconds < 31536000L -> {
            val mo = diffSeconds / 2592000L
            if (mo == 1L) "1 month ago" else "$mo months ago"
        }
        else -> {
            val y = diffSeconds / 31536000L
            if (y == 1L) "1 year ago" else "$y years ago"
        }
    }
}

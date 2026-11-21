package com.fwr.app.ui.inbox

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.RedditMessage
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.components.RifCommentReplyDialog
import com.fwr.app.ui.components.RifMarkdownText
import com.fwr.app.ui.components.RwfIcons
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.launch
import kotlin.math.max

private data class RifInboxSection(
    val label: String,
    val where: String
)

private val INBOX_SECTIONS = listOf(
    RifInboxSection("inbox: all", "inbox"),
    RifInboxSection("inbox: unread", "unread"),
    RifInboxSection("inbox: messages", "messages"),
    RifInboxSection("inbox: comment replies", "comments"),
    RifInboxSection("inbox: post replies", "selfreply"),
    RifInboxSection("inbox: sent", "sent"),
    RifInboxSection("modmail", "moderator")
)

/**
 * Inbox & Modmail Screen:
 * - Top bar with back button, folder dropdown (`all`, `unread`, `messages`, `comment replies`, `post replies`, `sent`, `modmail`),
 *   compose message icon, and refresh button.
 * - Each row renders subject + italic link title, sender/subreddit/timestamp, and Markdown body.
 * - Selecting a row expands the message actions strip (`Permalink | Context | Upvote | Downvote | Mark Unread | Reply`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    initialWhere: String = "inbox",
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenContextPost: ((RedditPost) -> Unit)? = null,
    onOpenAuthorProfile: ((String) -> Unit)? = null,
    onSelectSubreddit: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()
    val activeUsername = authPrefs.username?.takeIf { it.isNotBlank() } ?: "guest"

    var selectedSection by remember(initialWhere) {
        mutableStateOf(INBOX_SECTIONS.firstOrNull { it.where == initialWhere } ?: INBOX_SECTIONS.first())
    }
    val isLoggedIn = !authPrefs.userAccessToken.isNullOrBlank()
    var dropdownExpanded by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<RedditMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }

    val unreadOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val voteOverrides = remember { mutableStateMapOf<String, Int>() }

    var replyTargetMessage by remember { mutableStateOf<RedditMessage?>(null) }
    var isSendingReply by remember { mutableStateOf(false) }
    var showComposeDialog by remember { mutableStateOf(false) }
    var showScopeReauthDialog by remember { mutableStateOf(false) }

    fun loadMessages(isSwipeRefresh: Boolean = false) {
        if (authPrefs.userAccessToken.isNullOrBlank()) {
            isLoading = false
            isRefreshing = false
            errorMessage = "Sign in via Manage accounts / BYOK Settings to view your Reddit inbox."
            return
        }
        if (isSwipeRefresh) {
            isRefreshing = true
        } else {
            isLoading = true
        }
        errorMessage = null
        coroutineScope.launch {
            runSuspendCatching {
                RedditNetworkClient.apiService.getMessages(where = selectedSection.where)
            }.onSuccess { listing ->
                messages = listing.data.children.map { it.data }
                isLoading = false
                isRefreshing = false
            }.onFailure { err ->
                if (isSwipeRefresh) {
                    Toast.makeText(
                        context,
                        err.localizedMessage ?: "Failed to refresh ${selectedSection.label}",
                        Toast.LENGTH_SHORT
                    ).show()
                    isRefreshing = false
                } else {
                    if (err.message?.contains("403") == true) {
                        showScopeReauthDialog = true
                    }
                    errorMessage = err.localizedMessage ?: "Failed to load ${selectedSection.label}"
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(selectedSection, authPrefs.userAccessToken) {
        loadMessages()
    }

    if (showScopeReauthDialog) {
        AlertDialog(
            onDismissRequest = { showScopeReauthDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Reddit Permission Update Needed") },
            text = {
                Text(
                    text = "Your current Reddit login needs updated inbox & reply permissions (privatemessages, submit). Tap Re-authorize to grant permission in your browser.",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showScopeReauthDialog = false
                        coroutineScope.launch {
                            RedditOAuthManager.launchReauthorization(context, prefsRepo)
                        }
                    }
                ) {
                    Text("Re-authorize", color = RwfPalette.AccentDarkTheme, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScopeReauthDialog = false }) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    if (replyTargetMessage != null) {
        val targetMsg = replyTargetMessage!!
        RifCommentReplyDialog(
            recipientName = targetMsg.author ?: targetMsg.dest ?: "reddit",
            activeUsername = activeUsername,
            parentTextToQuote = targetMsg.body,
            isSending = isSendingReply,
            onDismiss = { replyTargetMessage = null },
            onSend = { text ->
                isSendingReply = true
                coroutineScope.launch {
                    runSuspendCatching {
                        val responseBody = RedditNetworkClient.apiService.postComment(
                            parentFullname = targetMsg.resolvedFullname,
                            markdownText = text
                        ).use { it.string() }
                        RedditOAuthManager.extractRedditApiError(responseBody)?.let { apiErr ->
                            throw IllegalStateException(apiErr)
                        }
                        responseBody
                    }.onSuccess {
                        isSendingReply = false
                        replyTargetMessage = null
                        Toast.makeText(context, "Reply sent!", Toast.LENGTH_SHORT).show()
                        loadMessages()
                    }.onFailure { err ->
                        isSendingReply = false
                        if (err.message?.contains("403") == true) {
                            showScopeReauthDialog = true
                        }
                        Toast.makeText(
                            context,
                            "Failed to send reply: ${err.localizedMessage ?: "Error"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    if (showComposeDialog) {
        var toUser by remember { mutableStateOf("") }
        var subject by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showComposeDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Compose Private Message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = toUser,
                        onValueChange = { toUser = it },
                        label = { Text("To (username)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("Message") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (authPrefs.userAccessToken.isNullOrBlank()) {
                            Toast.makeText(
                                context,
                                "Sign in via Manage accounts / BYOK Settings to send messages",
                                Toast.LENGTH_SHORT
                            ).show()
                            showComposeDialog = false
                            return@TextButton
                        }
                        if (toUser.isBlank() || subject.isBlank() || body.isBlank()) return@TextButton
                        showComposeDialog = false
                        coroutineScope.launch {
                            runSuspendCatching {
                                val responseBody = RedditNetworkClient.apiService.composeMessage(
                                    recipient = toUser.trim(),
                                    subject = subject.trim(),
                                    body = body.trim()
                                ).use { it.string() }
                                RedditOAuthManager.extractRedditApiError(responseBody)?.let { apiErr ->
                                    throw IllegalStateException(apiErr)
                                }
                                responseBody
                            }.onSuccess {
                                Toast.makeText(context, "Message sent to /u/${toUser.trim()}", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                if (err.message?.contains("403") == true) {
                                    showScopeReauthDialog = true
                                }
                                Toast.makeText(
                                    context,
                                    "Failed: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text("Send", color = RwfPalette.AccentDarkTheme)
                }
            },
            dismissButton = {
                TextButton(onClick = { showComposeDialog = false }) {
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
                                .clickable { dropdownExpanded = true }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedSection.label,
                                color = topBarContentColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select inbox folder",
                                tint = topBarContentColor
                            )
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier
                                .width(230.dp)
                                .background(RwfPalette.CardBackgroundDark)
                        ) {
                            INBOX_SECTIONS.forEach { section ->
                                DropdownMenuItem(
                                    text = { Text(section.label, color = RwfPalette.TitleUnreadDark, fontSize = 16.sp) },
                                    onClick = {
                                        dropdownExpanded = false
                                        selectedSection = section
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isLoggedIn) {
                        IconButton(onClick = {
                            if (authPrefs.userAccessToken.isNullOrBlank()) {
                                Toast.makeText(
                                    context,
                                    "Sign in via Manage accounts / BYOK Settings to send messages",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showComposeDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = "Compose message",
                                tint = topBarContentColor
                            )
                        }
                    }
                    IconButton(onClick = { loadMessages() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh inbox",
                            tint = topBarContentColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RwfPalette.PrimaryDarkTheme,
                    titleContentColor = topBarContentColor
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = RwfPalette.MetadataTextDark,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = { loadMessages() }) {
                            Text("Retry")
                        }
                    }
                }
                messages.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "there doesn't seem to be anything here",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { loadMessages(isSwipeRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(
                                items = messages,
                                key = { it.resolvedFullname },
                                contentType = { "inbox_message" }
                            ) { msg ->
                            val isSelected = selectedMessageId == msg.resolvedFullname
                            val isUnread = unreadOverrides[msg.resolvedFullname] ?: msg.new
                            val initialVote = when (msg.likes) {
                                true -> 1
                                false -> -1
                                null -> 0
                            }
                            val voteDir = voteOverrides[msg.resolvedFullname] ?: initialVote

                            RifInboxMessageRow(
                                message = msg,
                                isSelected = isSelected,
                                isUnread = isUnread,
                                voteDirection = voteDir,
                                showInlineImages = appSettings.showInlineImages,
                                baseBackgroundColor = backgroundColor,
                                onToggleSelect = {
                                    selectedMessageId = if (isSelected) null else msg.resolvedFullname
                                    if (isUnread) {
                                        unreadOverrides[msg.resolvedFullname] = false
                                        coroutineScope.launch {
                                            runSuspendCatching {
                                                RedditNetworkClient.apiService.markMessageRead(msg.resolvedFullname)
                                            }
                                        }
                                    }
                                },
                                onPermalink = {
                                    val target = msg.context?.let { "https://www.reddit.com$it" }
                                        ?: "https://www.reddit.com/message/messages/${msg.id}"
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, target)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share message"))
                                },
                                onContext = {
                                    val ctxPath = msg.context
                                    if (!ctxPath.isNullOrBlank() && onOpenContextPost != null) {
                                        val articleId = ctxPath.substringAfter("/comments/").substringBefore("/")
                                        if (articleId.isNotBlank()) {
                                            onOpenContextPost(
                                                RedditPost(
                                                    id = articleId,
                                                    title = msg.link_title ?: msg.subject,
                                                    subreddit = msg.subreddit.orEmpty(),
                                                    permalink = ctxPath
                                                )
                                            )
                                        }
                                    } else if (!ctxPath.isNullOrBlank()) {
                                        MediaResolver.openExternalLink(context, "https://www.reddit.com$ctxPath")
                                    }
                                },
                                onUpvote = {
                                    val next = if (voteDir == 1) 0 else 1
                                    voteOverrides[msg.resolvedFullname] = next
                                    coroutineScope.launch {
                                        runSuspendCatching { RedditNetworkClient.apiService.vote(msg.resolvedFullname, next) }
                                    }
                                },
                                onDownvote = {
                                    val next = if (voteDir == -1) 0 else -1
                                    voteOverrides[msg.resolvedFullname] = next
                                    coroutineScope.launch {
                                        runSuspendCatching { RedditNetworkClient.apiService.vote(msg.resolvedFullname, next) }
                                    }
                                },
                                onMarkUnread = {
                                    val nextUnread = !isUnread
                                    unreadOverrides[msg.resolvedFullname] = nextUnread
                                    coroutineScope.launch {
                                        runSuspendCatching {
                                            if (nextUnread) {
                                                RedditNetworkClient.apiService.markMessageUnread(msg.resolvedFullname)
                                            } else {
                                                RedditNetworkClient.apiService.markMessageRead(msg.resolvedFullname)
                                            }
                                        }
                                    }
                                },
                                onReply = {
                                    if (authPrefs.userAccessToken.isNullOrBlank()) {
                                        Toast.makeText(
                                            context,
                                            "Sign in via Manage accounts / BYOK Settings to send messages",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        replyTargetMessage = msg
                                    }
                                },
                                onOpenAuthorProfile = onOpenAuthorProfile,
                                onSelectSubreddit = onSelectSubreddit
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun RifInboxMessageRow(
    message: RedditMessage,
    isSelected: Boolean,
    isUnread: Boolean,
    voteDirection: Int,
    showInlineImages: Boolean,
    baseBackgroundColor: Color,
    onToggleSelect: () -> Unit,
    onPermalink: () -> Unit,
    onContext: () -> Unit,
    onUpvote: () -> Unit,
    onDownvote: () -> Unit,
    onMarkUnread: () -> Unit,
    onReply: () -> Unit,
    onOpenAuthorProfile: ((String) -> Unit)?,
    onSelectSubreddit: ((String) -> Unit)?
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) RwfPalette.SelectedPostRowDark else baseBackgroundColor,
        animationSpec = tween(durationMillis = 120),
        label = "inboxRowBg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onToggleSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Line 1: Subject + · + link_title
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = if (isUnread) RwfPalette.OrangeHighlight else RwfPalette.TitleUnreadDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                ) {
                    append(message.subject.ifBlank { "comment reply" })
                }
                if (!message.link_title.isNullOrBlank()) {
                    withStyle(
                        SpanStyle(
                            color = RwfPalette.MetadataTextDark,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" · ")
                    }
                    withStyle(
                        SpanStyle(
                            color = RwfPalette.MetadataTextDark,
                            fontStyle = FontStyle.Italic,
                            fontSize = 13.sp
                        )
                    ) {
                        append(message.link_title)
                    }
                }
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Line 2: from author · via /r/sub · sent_time
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val senderText = when {
                !message.author.isNullOrBlank() -> "from ${message.author}"
                !message.dest.isNullOrBlank() -> "to ${message.dest}"
                else -> ""
            }
            if (senderText.isNotEmpty()) {
                Text(
                    text = senderText,
                    color = if (isUnread) RwfPalette.OrangeHighlight else RwfPalette.DomainTextDark,
                    fontSize = 12.sp,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable {
                        message.author?.let { onOpenAuthorProfile?.invoke(it) }
                    }
                )
            }
            if (!message.subreddit.isNullOrBlank()) {
                Text(
                    text = "via /r/${message.subreddit}",
                    color = RwfPalette.DomainTextDark,
                    fontSize = 12.sp
                )
            }
            val relTime = formatInboxTime(message.created_utc)
            if (relTime.isNotEmpty()) {
                Text(
                    text = relTime,
                    color = RwfPalette.DomainTextDark,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        RifMarkdownText(
            text = message.body.trim(),
            color = RwfPalette.TitleUnreadDark,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            showInlineImages = showInlineImages,
            onSubredditClick = onSelectSubreddit,
            onUserClick = onOpenAuthorProfile,
            onNonLinkClick = onToggleSelect
        )

        // Expanded #message_actions row
        AnimatedVisibility(visible = isSelected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onPermalink, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Permalink",
                        tint = RwfPalette.ActionStripTextDark,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (message.was_comment && !message.context.isNullOrBlank()) {
                    IconButton(onClick = onContext, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Context",
                            tint = RwfPalette.ActionStripTextDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onUpvote, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Upvote",
                            tint = if (voteDirection == 1) RwfPalette.VoteArrowUpvoteRed else RwfPalette.ActionStripTextDark,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onDownvote, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Downvote",
                            tint = if (voteDirection == -1) RwfPalette.VoteArrowDownvoteBlue else RwfPalette.ActionStripTextDark,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                IconButton(onClick = onMarkUnread, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = if (isUnread) "Mark read" else "Mark unread",
                        tint = if (isUnread) RwfPalette.OrangeHighlight else RwfPalette.ActionStripTextDark,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onReply, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = RwfIcons.Reply,
                        contentDescription = "Reply",
                        tint = RwfPalette.ActionStripTextDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
}

private fun formatInboxTime(createdUtcSeconds: Double): String {
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

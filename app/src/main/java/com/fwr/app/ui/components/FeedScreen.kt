package com.fwr.app.ui.components

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.drawer.SubredditDrawer
import com.fwr.app.ui.feed.FeedFilter
import com.fwr.app.ui.feed.FeedUiState
import com.fwr.app.ui.feed.FeedViewModel
import com.fwr.app.ui.inbox.InboxScreen
import com.fwr.app.ui.profile.ProfileScreen
import com.fwr.app.ui.settings.RifScaleTweakerDialog
import com.fwr.app.ui.settings.SettingsScreen
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val RIF_TABS_FRONTPAGE = listOf("BEST", "HOT", "NEW", "SAVED", "TOP", "CONTROVERSIAL", "RISING")
private val RIF_TABS_SUBREDDIT = listOf("HOT", "NEW", "SAVED", "TOP", "CONTROVERSIAL", "RISING")
private val RIF_TIME_FILTERS = listOf(
    "hour" to "past hour",
    "day" to "past 24 hours",
    "week" to "past week",
    "month" to "past month",
    "year" to "past year",
    "all" to "all time"
)

/**
 * Classic Dark Theme main feed screen:
 * - Top header bar (`#FF212121`): Hamburger menu button, `/r/{subreddit}` title, Refresh icon, and 3-dot Overflow menu.
 * - 3-Dot Overflow menu:
 *   `[username]'s profile`, `Search posts`, `Submit`, `Inbox`, `Modmail`, `Settings`, `Theme`,
 *   `reddit in browser`, `Manage accounts`, `Logout: [username]`.
 * - Scrollable tab row (`HOT`, `NEW`, `SAVED`, `TOP`, `CONTROVERSIAL`) with active `#FFAADFF1` indicator.
 * - High-density 3-column [PostCard] list with `#232A35` selected row highlight and expandable action strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    modifier: Modifier = Modifier,
    isOverlayActive: Boolean = false,
    onPostClick: (RedditPost) -> Unit = {},
    onThumbnailClick: ((RedditPost) -> Unit)? = null,
    onOpenSettingsClick: (() -> Unit)? = null,
    onOpenProfileClick: ((String) -> Unit)? = null,
    onOpenInboxClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subreddit by viewModel.subreddit.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val activeSearchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val activeTimeFilter by viewModel.timeFilter.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showInternalSettings by remember { mutableStateOf(false) }
    var expandedPostId by remember { mutableStateOf<String?>(null) }
    var hiddenPostIds by remember { mutableStateOf(setOf<String>()) }

    var showProfileUsername by remember { mutableStateOf<String?>(null) }
    var showInternalInboxWhere by remember { mutableStateOf<String?>(null) }
    var showSidebarDialog by remember { mutableStateOf(false) }
    var pendingTimeFilterSort by remember { mutableStateOf<String?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showSubmitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showScaleDialog by remember { mutableStateOf(false) }

    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()
    val blockedSubreddits by prefsRepo.blockedSubreddits.collectAsStateWithLifecycle()
    val keywordFilters by prefsRepo.keywordFilters.collectAsStateWithLifecycle()
    val clickedPostIds by prefsRepo.clickedPostIds.collectAsStateWithLifecycle()
    val searchHistory by prefsRepo.searchHistory.collectAsStateWithLifecycle()
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val userAccessToken = authPrefs.userAccessToken
    val isLoggedIn = !userAccessToken.isNullOrBlank()
    val activeUsername = authPrefs.username?.takeIf { it.isNotBlank() } ?: "guest"
    var showQuitConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(userAccessToken, authPrefs.username) {
        if (!userAccessToken.isNullOrBlank() && authPrefs.username.isNullOrBlank()) {
            RedditNetworkClient.getOAuthManager(context).fetchAndStoreMeIdentity(userAccessToken)
        }
    }

    val baseTitleSp = when (appSettings.fontSize) {
        "Tiny" -> 11
        "Small" -> 13
        "Medium" -> 14
        "Huge" -> 17
        "Extra Huge" -> 19
        else -> 15
    }
    val scaleOffsetSp = when (appSettings.displayScale) {
        "Compact" -> -1
        "Large" -> 2
        else -> 0
    }
    val rawTitleSp = (baseTitleSp + scaleOffsetSp).coerceAtLeast(10)
    val titleFontSizeSp = (rawTitleSp * (appSettings.postTitleScalePercent / 100f))
        .roundToInt()
        .coerceIn(10, 32)
    val thumbnailSizeDp = (70f * (appSettings.thumbnailScalePercent / 100f))
        .roundToInt()
        .coerceIn(48, 128)
    val feedBackgroundColor = RwfPalette.BackgroundMaterialDark
    val topBarContentColor = if (RwfPalette.PrimaryDarkTheme.luminance() > 0.5f) {
        Color(0xFF1A1A1A)
    } else {
        Color.White
    }
    val isOnWifi = remember(subreddit, sort, activeSearchQuery) { MediaResolver.isCurrentlyOnWifi(context) }
    val shouldShowThumbnails = when {
        appSettings.webThumbnailsMode == "Never show thumbnails" -> false
        appSettings.loadThumbnailsMode == "Never" -> false
        appSettings.loadThumbnailsMode == "Wi-Fi only" -> isOnWifi
        else -> true
    }
    val allowHighResPreview = when (appSettings.prefetchThumbnails) {
        "Never" -> false
        "Always" -> true
        else -> isOnWifi
    }

    if (showInternalSettings && onOpenSettingsClick == null) {
        val oauthManager = remember(context) { RedditNetworkClient.getOAuthManager(context) }
        SettingsScreen(
            preferencesRepository = prefsRepo,
            oauthManager = oauthManager,
            onBackClick = {
                showInternalSettings = false
            }
        )
        return
    }

    if (showProfileUsername != null && onOpenProfileClick == null) {
        val targetUser = showProfileUsername!!
        BackHandler {
            showProfileUsername = null
        }
        ProfileScreen(
            username = targetUser,
            onBackClick = { showProfileUsername = null },
            onPostClick = onPostClick,
            onThumbnailClick = { post ->
                if (onThumbnailClick != null) {
                    onThumbnailClick(post)
                } else {
                    onPostClick(post)
                }
            },
            onSelectSubreddit = { chosenSub ->
                showProfileUsername = null
                viewModel.selectSubreddit(chosenSub)
            },
            onOpenAuthorProfile = { author ->
                showProfileUsername = author
            }
        )
        return
    }

    if (showInternalInboxWhere != null && onOpenInboxClick == null) {
        BackHandler {
            showInternalInboxWhere = null
        }
        InboxScreen(
            initialWhere = showInternalInboxWhere!!,
            onBackClick = { showInternalInboxWhere = null },
            onOpenContextPost = { contextPost ->
                showInternalInboxWhere = null
                onPostClick(contextPost)
            },
            onOpenAuthorProfile = { author ->
                showInternalInboxWhere = null
                showProfileUsername = author
            },
            onSelectSubreddit = { chosenSub ->
                showInternalInboxWhere = null
                viewModel.selectSubreddit(chosenSub)
            }
        )
        return
    }

    val openSettings = {
        if (onOpenSettingsClick != null) {
            onOpenSettingsClick()
        } else {
            showInternalSettings = true
        }
    }

    val openProfile: (String) -> Unit = { targetUsername ->
        if (onOpenProfileClick != null) {
            onOpenProfileClick(targetUsername)
        } else {
            showProfileUsername = targetUsername
        }
    }

    val openInbox: (String) -> Unit = { where ->
        if (onOpenInboxClick != null) {
            onOpenInboxClick(where)
        } else {
            showInternalInboxWhere = where
        }
    }

    // Sidebar Info Dialog
    if (showSidebarDialog) {
        RifSidebarDialog(
            subreddit = subreddit,
            onDismiss = { showSidebarDialog = false },
            onSelectSubreddit = { chosenSub ->
                showSidebarDialog = false
                expandedPostId = null
                viewModel.selectSubreddit(chosenSub)
            }
        )
    }

    // Time-Range Dialog for TOP / CONTROVERSIAL tabs
    if (pendingTimeFilterSort != null) {
        val targetSort = pendingTimeFilterSort!!
        AlertDialog(
            onDismissRequest = { pendingTimeFilterSort = null },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Links from (${targetSort.uppercase(Locale.US)})") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RIF_TIME_FILTERS.forEach { (apiValue, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingTimeFilterSort = null
                                    expandedPostId = null
                                    viewModel.selectSort(targetSort, apiValue)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = activeTimeFilter == apiValue && sort == targetSort,
                                onClick = {
                                    pendingTimeFilterSort = null
                                    expandedPostId = null
                                    viewModel.selectSort(targetSort, apiValue)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, color = RwfPalette.TitleUnreadDark, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingTimeFilterSort = null }) {
                    Text("Cancel", color = RwfPalette.AccentDarkTheme)
                }
            }
        )
    }

    // Server-Side Search Dialog
    if (showSearchDialog) {
        var queryInput by remember { mutableStateOf(activeSearchQuery ?: "") }
        val isAggregateSub = subreddit.equals("frontpage", ignoreCase = true) ||
            subreddit.equals("all", ignoreCase = true) ||
            subreddit.equals("popular", ignoreCase = true)
        var restrictToSub by remember { mutableStateOf(!isAggregateSub) }
        var includeNsfw by remember { mutableStateOf(appSettings.nsfwAllowed) }
        var selectedSearchSort by remember { mutableStateOf("relevance") }

        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Search reddit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = { queryInput = it },
                        label = { Text("Search terms") },
                        placeholder = { Text("Enter search keywords…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (searchHistory.isNotEmpty()) {
                        Text(
                            text = "Recent searches:",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 12.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            searchHistory.take(4).forEach { recent ->
                                Surface(
                                    color = RwfPalette.FlairBackgroundDark,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable { queryInput = recent }
                                ) {
                                    Text(
                                        text = recent,
                                        color = RwfPalette.FlairTextDark,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (!isAggregateSub) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restrictToSub = !restrictToSub }
                        ) {
                            Checkbox(
                                checked = restrictToSub,
                                onCheckedChange = { restrictToSub = it }
                            )
                            Text(
                                text = "Limit my search to /r/$subreddit",
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeNsfw = !includeNsfw }
                    ) {
                        Checkbox(
                            checked = includeNsfw,
                            onCheckedChange = { includeNsfw = it }
                        )
                        Text(
                            text = "Include NSFW results",
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 13.sp
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("relevance", "top", "new").forEach { sOpt ->
                            val isSelected = selectedSearchSort == sOpt
                            Surface(
                                color = if (isSelected) RwfPalette.AccentDarkTheme else RwfPalette.FlairBackgroundDark,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { selectedSearchSort = sOpt }
                            ) {
                                Text(
                                    text = sOpt,
                                    color = if (isSelected) Color.Black else RwfPalette.FlairTextDark,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (queryInput.isNotBlank()) {
                            val trimmedQuery = queryInput.trim()
                            showSearchDialog = false
                            coroutineScope.launch {
                                prefsRepo.addSearchQuery(trimmedQuery)
                            }
                            viewModel.performSearch(
                                query = trimmedQuery,
                                restrictToSub = restrictToSub,
                                includeNsfw = includeNsfw,
                                sort = selectedSearchSort
                            )
                        }
                    }
                ) {
                    Text("Search", color = RwfPalette.AccentDarkTheme)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSearchDialog = false
                        viewModel.clearSearch()
                    }
                ) {
                    Text(if (activeSearchQuery != null) "Clear search" else "Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    // Submit Post Dialog
    if (showSubmitDialog) {
        var isLinkPost by remember { mutableStateOf(true) }
        var submitTitle by remember { mutableStateOf("") }
        var submitUrl by remember { mutableStateOf("") }
        var submitSelftext by remember { mutableStateOf("") }
        var targetSub by remember {
            mutableStateOf(
                if (subreddit.equals("all", ignoreCase = true) ||
                    subreddit.equals("popular", ignoreCase = true) ||
                    subreddit.equals("frontpage", ignoreCase = true)
                ) {
                    ""
                } else {
                    subreddit
                }
            )
        }
        var sendRepliesToInbox by remember { mutableStateOf(true) }
        var isSubmitting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSubmitting) showSubmitDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Submit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Posting as $activeUsername",
                        color = RwfPalette.MetadataTextDark,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true to "LINK", false to "TEXT").forEach { (linkMode, label) ->
                            val selected = isLinkPost == linkMode
                            Surface(
                                color = if (selected) RwfPalette.AccentDarkTheme else RwfPalette.FlairBackgroundDark,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { isLinkPost = linkMode }
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color.Black else RwfPalette.FlairTextDark,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = targetSub,
                        onValueChange = { targetSub = it },
                        label = { Text("Subreddit (without /r/)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = submitTitle,
                        onValueChange = { submitTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isLinkPost) {
                        OutlinedTextField(
                            value = submitUrl,
                            onValueChange = { submitUrl = it },
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        RifMarkdownFormattingBar(
                            onInsertSnippet = { snippet ->
                                submitSelftext += snippet
                            }
                        )
                        OutlinedTextField(
                            value = submitSelftext,
                            onValueChange = { submitSelftext = it },
                            label = { Text("Text (optional)") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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
                    enabled = !isSubmitting && targetSub.isNotBlank() && submitTitle.isNotBlank(),
                    onClick = {
                        if (userAccessToken.isNullOrBlank()) {
                            Toast.makeText(
                                context,
                                "Sign in via Manage accounts / BYOK Settings to submit posts",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }
                        isSubmitting = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                val responseBody = RedditNetworkClient.apiService.submitPost(
                                    subreddit = targetSub.trim().removePrefix("/r/").removePrefix("r/"),
                                    kind = if (isLinkPost) "link" else "self",
                                    title = submitTitle.trim(),
                                    url = if (isLinkPost) submitUrl.trim() else null,
                                    text = if (!isLinkPost) submitSelftext.trim() else null,
                                    sendReplies = sendRepliesToInbox
                                ).use { it.string() }
                                RedditOAuthManager.extractRedditApiError(responseBody)?.let { apiErr ->
                                    throw IllegalStateException(apiErr)
                                }
                                responseBody
                            }.onSuccess {
                                isSubmitting = false
                                showSubmitDialog = false
                                Toast.makeText(context, "Submitted to /r/$targetSub", Toast.LENGTH_SHORT).show()
                                viewModel.selectSubreddit(targetSub.trim())
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
                                        "Submit failed: ${err.localizedMessage ?: "Error"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isSubmitting) "Submitting…" else "Submit", color = RwfPalette.AccentDarkTheme)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSubmitting,
                    onClick = { showSubmitDialog = false }
                ) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    // Quick Theme Toggle Dialog (Dark, Light, AMOLED Black)
    if (showThemeDialog) {
        val currentThemeSelection = when {
            appSettings.amoledBlackBackground -> "AMOLED Black"
            appSettings.darkTheme -> "Dark"
            else -> "Light"
        }
        val themeOptions = listOf("Dark", "Light", "AMOLED Black")

        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Theme") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    themeOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch {
                                        preferencesRepositoryUpdateTheme(prefsRepo, option)
                                    }
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == currentThemeSelection,
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesRepositoryUpdateTheme(prefsRepo, option)
                                    }
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option,
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Cancel", color = RwfPalette.AccentDarkTheme)
                }
            }
        )
    }

    if (showScaleDialog) {
        RifScaleTweakerDialog(
            preferencesRepository = prefsRepo,
            onDismiss = { showScaleDialog = false }
        )
    }

    if (showQuitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showQuitConfirmDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Quit FWR?") },
            text = {
                Text(
                    text = "Exit the application?",
                    color = RwfPalette.TitleUnreadDark
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showQuitConfirmDialog = false
                        (context as? Activity)?.finish()
                    }
                ) {
                    Text("Quit", color = RwfPalette.AccentDarkTheme)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitConfirmDialog = false }) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    val scrollTarget by viewModel.scrollTarget.collectAsStateWithLifecycle()
    val isFeedSuccess = uiState is FeedUiState.Success

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (viewModel.uiState.value is FeedUiState.Success) {
                    viewModel.saveScrollPosition(index, offset)
                }
            }
    }

    LaunchedEffect(scrollTarget.version, isFeedSuccess) {
        if (scrollTarget.version > 0 && isFeedSuccess) {
            listState.scrollToItem(scrollTarget.index, scrollTarget.offset)
        }
    }

    val canPopSubredditBackStack = viewModel.canNavigateBackInFeed()
    val shouldGoHomeOnBack = !isOverlayActive &&
        !drawerState.isOpen &&
        appSettings.backGoesHome &&
        (canPopSubredditBackStack || !subreddit.equals("frontpage", ignoreCase = true))

    BackHandler(enabled = shouldGoHomeOnBack) {
        expandedPostId = null
        if (!viewModel.navigateBackInFeed()) {
            viewModel.selectSubreddit("frontpage")
        }
    }

    BackHandler(enabled = !isOverlayActive && !drawerState.isOpen && !shouldGoHomeOnBack && appSettings.confirmQuit) {
        showQuitConfirmDialog = true
    }

    // Close the slide-out drawer on system back when open
    BackHandler(enabled = !isOverlayActive && drawerState.isOpen) {
        coroutineScope.launch {
            drawerState.close()
        }
    }

    // Detect when the user scrolls within 5 items of the bottom to trigger pagination
    val shouldLoadNextPage by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 5
        }
    }

    val successState = uiState as? FeedUiState.Success
    val canAutoPaginate = appSettings.endlessReddit &&
        successState != null &&
        successState.after != null &&
        !successState.isLoadingMore &&
        !successState.isRefreshing &&
        successState.paginationError == null

    LaunchedEffect(shouldLoadNextPage, canAutoPaginate) {
        if (shouldLoadNextPage && canAutoPaginate) {
            viewModel.loadNextPage()
        }
    }

    val activeTabs = if (subreddit.equals("frontpage", ignoreCase = true)) {
        if (isLoggedIn) RIF_TABS_FRONTPAGE else RIF_TABS_FRONTPAGE.filterNot { it.equals("SAVED", ignoreCase = true) }
    } else {
        if (isLoggedIn) RIF_TABS_SUBREDDIT else RIF_TABS_SUBREDDIT.filterNot { it.equals("SAVED", ignoreCase = true) }
    }

    LaunchedEffect(isLoggedIn, sort, subreddit) {
        if (!isLoggedIn && sort.equals("saved", ignoreCase = true)) {
            val fallbackSort = if (subreddit.equals("frontpage", ignoreCase = true)) "best" else "hot"
            viewModel.selectSort(fallbackSort)
        }
    }

    val selectedTabIndex = activeTabs.indexOfFirst {
        it.equals(sort, ignoreCase = true)
    }.coerceAtLeast(0)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SubredditDrawer(
                currentSubreddit = subreddit,
                onSelectSubreddit = { chosenSubreddit ->
                    coroutineScope.launch {
                        drawerState.close()
                    }
                    expandedPostId = null
                    viewModel.selectSubreddit(chosenSubreddit)
                },
                onOpenSettings = {
                    coroutineScope.launch {
                        drawerState.close()
                    }
                    openSettings()
                }
            )
        }
    ) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .background(feedBackgroundColor),
            containerColor = feedBackgroundColor,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RwfPalette.PrimaryDarkTheme)
                ) {
                    // 1. RIF Top Header Bar (Hamburger, Subreddit Title, Refresh, 3-Dot Overflow Menu)
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        if (drawerState.isOpen) {
                                            drawerState.close()
                                        } else {
                                            drawerState.open()
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open subreddit drawer",
                                    tint = topBarContentColor
                                )
                            }
                        },
                        title = {
                            val headerTitle = if (subreddit.equals("frontpage", ignoreCase = true)) {
                                "reddit: the front page of the internet"
                            } else {
                                "/r/$subreddit"
                            }
                            Text(
                                text = headerTitle,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = topBarContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clickable {
                                        coroutineScope.launch {
                                            drawerState.open()
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        },
                        actions = {
                            if (!subreddit.equals("frontpage", ignoreCase = true) &&
                                !subreddit.equals("all", ignoreCase = true) &&
                                !subreddit.equals("popular", ignoreCase = true)
                            ) {
                                IconButton(onClick = { showSidebarDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = "Sidebar",
                                        tint = topBarContentColor
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.refresh() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh feed",
                                    tint = topBarContentColor
                                )
                            }
                            Box {
                                IconButton(onClick = { overflowMenuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                        tint = topBarContentColor
                                    )
                                }
                                // 3-Dot Overflow Menu
                                DropdownMenu(
                                    expanded = overflowMenuExpanded,
                                    onDismissRequest = { overflowMenuExpanded = false },
                                    modifier = Modifier
                                        .width(230.dp)
                                        .background(RwfPalette.CardBackgroundDark)
                                ) {
                                    if (isLoggedIn) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "$activeUsername's profile",
                                                    color = RwfPalette.TitleUnreadDark,
                                                    fontSize = 16.sp
                                                )
                                            },
                                            onClick = {
                                                overflowMenuExpanded = false
                                                openProfile(activeUsername)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Search posts",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            showSearchDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Submit",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            if (!isLoggedIn) {
                                                Toast.makeText(
                                                    context,
                                                    "Sign in via Manage accounts / BYOK Settings to submit posts",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                showSubmitDialog = true
                                            }
                                        }
                                    )
                                    if (isLoggedIn) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Inbox",
                                                    color = RwfPalette.TitleUnreadDark,
                                                    fontSize = 16.sp
                                                )
                                            },
                                            onClick = {
                                                overflowMenuExpanded = false
                                                openInbox("inbox")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Modmail",
                                                    color = RwfPalette.TitleUnreadDark,
                                                    fontSize = 16.sp
                                                )
                                            },
                                            onClick = {
                                                overflowMenuExpanded = false
                                                openInbox("moderator")
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Sidebar",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            showSidebarDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Settings",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            openSettings()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Theme",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            showThemeDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Scale settings",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            showScaleDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "reddit in browser",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            MediaResolver.openSystemBrowser(
                                                context,
                                                "https://www.reddit.com/r/$subreddit/$sort"
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Manage accounts",
                                                color = RwfPalette.TitleUnreadDark,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            openSettings()
                                        }
                                    )
                                    if (isLoggedIn) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Logout: $activeUsername",
                                                    color = RwfPalette.TitleUnreadDark,
                                                    fontSize = 16.sp
                                                )
                                            },
                                            onClick = {
                                                overflowMenuExpanded = false
                                                coroutineScope.launch {
                                                    prefsRepo.clearTokens()
                                                    RedditNetworkClient.setOAuthToken(null)
                                                    Toast.makeText(
                                                        context,
                                                        "Logged out $activeUsername",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    viewModel.refresh()
                                                }
                                            }
                                        )
                                    }
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

                    // 2. Scrollable Tab Row (HOT, NEW, SAVED, TOP, CONTROVERSIAL) with active cyan/teal underline
                    ScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = RwfPalette.PrimaryDarkTheme,
                        contentColor = RwfPalette.TabTextSelectedDark,
                        edgePadding = 0.dp,
                        indicator = { tabPositions ->
                            if (selectedTabIndex in tabPositions.indices) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                    height = 3.dp,
                                    color = RwfPalette.AccentDarkTheme
                                )
                            }
                        },
                        divider = {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = RwfPalette.DividerLineDark
                            )
                        }
                    ) {
                        activeTabs.forEachIndexed { index, tabTitle ->
                            val isSelected = index == selectedTabIndex
                            Tab(
                                selected = isSelected,
                                onClick = {
                                    expandedPostId = null
                                    val targetSort = tabTitle.lowercase(Locale.US)
                                    if (targetSort == "top" || targetSort == "controversial") {
                                        pendingTimeFilterSort = targetSort
                                    } else {
                                        viewModel.selectSort(targetSort)
                                    }
                                },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 88.dp)
                                    .height(38.dp),
                                selectedContentColor = RwfPalette.TabTextSelectedDark,
                                unselectedContentColor = RwfPalette.TabTextUnselectedDark,
                                text = {
                                    Text(
                                        text = tabTitle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) {
                                            RwfPalette.TabTextSelectedDark
                                        } else {
                                            RwfPalette.TabTextUnselectedDark
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(feedBackgroundColor)
                    .padding(innerPadding)
            ) {
                when (val state = uiState) {
                    is FeedUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
                        }
                    }

                    is FeedUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = RwfPalette.VoteArrowUpvoteRed
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadInitialFeed() }) {
                                Text(text = "Retry")
                            }
                        }
                    }

                    is FeedUiState.Success -> {
                        PullToRefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (!activeSearchQuery.isNullOrBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(RwfPalette.CardBackgroundDark)
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Search: \"$activeSearchQuery\"",
                                            color = RwfPalette.AccentDarkTheme,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "CLEAR",
                                            color = RwfPalette.VoteArrowUpvoteRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { viewModel.clearSearch() }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                                }
                                if (state.isRefreshing) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = RwfPalette.AccentDarkTheme
                                    )
                                }
                                val visiblePosts = remember(
                                    state.posts,
                                    hiddenPostIds,
                                    appSettings.nsfwAllowed,
                                    blockedSubreddits,
                                    keywordFilters
                                ) {
                                    FeedFilter.filterPosts(
                                        posts = state.posts,
                                        hiddenPostIds = hiddenPostIds,
                                        nsfwAllowed = appSettings.nsfwAllowed,
                                        blockedSubreddits = blockedSubreddits,
                                        keywordFilters = keywordFilters
                                    )
                                }
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(feedBackgroundColor),
                                    contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                    items(
                                        items = visiblePosts,
                                        key = { post -> post.id.ifEmpty { post.permalink } },
                                        contentType = { "post" }
                                    ) { post ->
                                        val postKey = post.id.ifEmpty { post.permalink }
                                        val isRead = postKey in clickedPostIds
                                        PostCard(
                                            post = post,
                                            isRead = isRead,
                                            isExpanded = expandedPostId == postKey,
                                            onToggleExpand = {
                                                expandedPostId = if (expandedPostId == postKey) {
                                                    null
                                                } else {
                                                    postKey
                                                }
                                            },
                                            onPostClick = { clicked ->
                                                coroutineScope.launch {
                                                    prefsRepo.markPostClicked(postKey)
                                                }
                                                onPostClick(clicked)
                                            },
                                            onThumbnailClick = { clickedPost ->
                                                coroutineScope.launch {
                                                    prefsRepo.markPostClicked(postKey)
                                                }
                                                if (onThumbnailClick != null) {
                                                    onThumbnailClick(clickedPost)
                                                } else {
                                                    onPostClick(clickedPost)
                                                }
                                            },
                                            onMarkRead = {
                                                coroutineScope.launch {
                                                    prefsRepo.markPostClicked(postKey)
                                                }
                                            },
                                            onHidePost = { hiddenPost ->
                                                val key = hiddenPost.id.ifEmpty { hiddenPost.permalink }
                                                hiddenPostIds = hiddenPostIds + key
                                                if (expandedPostId == key) {
                                                    expandedPostId = null
                                                }
                                            },
                                            onBlockSubreddit = { blockedSub ->
                                                coroutineScope.launch {
                                                    prefsRepo.addBlockedSubreddit(blockedSub)
                                                    expandedPostId = null
                                                }
                                                Toast.makeText(
                                                    context,
                                                    "Blocked /r/$blockedSub",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onSelectSubreddit = { chosenSub ->
                                                expandedPostId = null
                                                viewModel.selectSubreddit(chosenSub)
                                            },
                                            onOpenAuthorProfile = { authorName ->
                                                openProfile(authorName)
                                            },
                                            amoledBlackBackground = appSettings.amoledBlackBackground,
                                            leftHandedMode = appSettings.leftHandedMode,
                                            showLinkFlair = appSettings.showLinkFlair,
                                            titleFontSizeSp = titleFontSizeSp,
                                            cardsView = appSettings.cardsView,
                                            labelNsfw = appSettings.labelNsfw,
                                            shouldShowThumbnails = shouldShowThumbnails,
                                            allowHighResPreview = allowHighResPreview &&
                                                (!post.over_18 || appSettings.prefetchNsfw),
                                            thumbnailSizeDp = thumbnailSizeDp,
                                            showInlineImages = appSettings.showInlineImages
                                        )
                                    }

                                    if (state.isLoadingMore) {
                                        item(
                                            key = "loading_more_footer",
                                            contentType = "loading_footer"
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.5.dp,
                                                    color = RwfPalette.AccentDarkTheme
                                                )
                                            }
                                        }
                                    } else if (state.paginationError != null) {
                                        item(
                                            key = "pagination_error_footer",
                                            contentType = "error_footer"
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { viewModel.retryNextPage() }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "Failed to load more — Tap to retry",
                                                    color = RwfPalette.AccentDarkTheme,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = state.paginationError,
                                                    color = RwfPalette.MetadataTextDark,
                                                    fontSize = 12.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun preferencesRepositoryUpdateTheme(
    prefsRepo: UserPreferencesRepository,
    selectedTheme: String
) {
    when (selectedTheme) {
        "AMOLED Black" -> prefsRepo.updateAppSettings {
            it.copy(darkTheme = true, amoledBlackBackground = true)
        }
        "Light" -> prefsRepo.updateAppSettings {
            it.copy(darkTheme = false, amoledBlackBackground = false)
        }
        else -> prefsRepo.updateAppSettings {
            it.copy(darkTheme = true, amoledBlackBackground = false)
        }
    }
}

package com.fwr.app.ui.drawer

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwr.app.data.model.MultiredditUtils
import com.fwr.app.data.model.RedditMultireddit
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.components.RifAddSubredditToMultiDialog
import com.fwr.app.ui.components.RifCreateEditMultiredditDialog
import com.fwr.app.ui.components.RifMultiredditDialog
import com.fwr.app.ui.components.RifSidebarDialog
import com.fwr.app.ui.components.RwfIcons
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.launch
import java.util.Locale

private val DrawerBackgroundDark get() = RwfPalette.BackgroundMaterialDark
private val DrawerHeaderSurfaceDark get() = RwfPalette.CardBackgroundDark
private val DrawerSelectedRowDark get() = RwfPalette.SelectedPostRowDark

val DEFAULT_SUBSCRIBED_SUBREDDITS: List<String> = listOf(
    "android",
    "androiddev",
    "askreddit",
    "askscience",
    "books",
    "coding",
    "compsci",
    "dataisbeautiful",
    "diy",
    "explainlikeimfive",
    "futurology",
    "gadgets",
    "gaming",
    "hardware",
    "history",
    "internetisbeautiful",
    "kotlin",
    "linux",
    "movies",
    "music",
    "news",
    "opensource",
    "pcgaming",
    "photography",
    "programming",
    "science",
    "space",
    "technology",
    "todayilearned",
    "videos",
    "worldnews"
).sorted()

/**
 * Slide-out Subreddit Navigation Drawer:
 *
 * - Syncs subscribed subreddits via `GET /subreddits/mine/subscriber.json` when authenticated.
 * - Top quick feeds: `reddit: the front page` (`frontpage`), `/r/all`, and `/r/popular`.
 * - Each subreddit row displays `+frontpage`/`✓` toggle, `★` favorite star (pinned to top),
 *   subreddit name, and `⋮` popup (`Sidebar`, `Share`, `Star`, `Unsubscribe`).
 */
@Composable
fun SubredditDrawer(
    currentSubreddit: String,
    onSelectSubreddit: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    subreddits: List<String> = DEFAULT_SUBSCRIBED_SUBREDDITS,
    onOpenAuthorProfile: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val hideKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()
    val savedSubs by prefsRepo.subscribedSubreddits.collectAsStateWithLifecycle(initialValue = emptySet())
    val favoriteSubs by prefsRepo.favoriteSubreddits.collectAsStateWithLifecycle(initialValue = emptySet())
    val savedMultis by prefsRepo.savedMultireddits.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteMultis by prefsRepo.favoriteMultireddits.collectAsStateWithLifecycle(initialValue = emptySet())
    val blockedSubreddits by prefsRepo.blockedSubreddits.collectAsStateWithLifecycle()
    val isLoggedIn = !authPrefs.userAccessToken.isNullOrBlank()

    var query by remember { mutableStateOf("") }
    var sidebarTargetSub by remember { mutableStateOf<String?>(null) }
    var multisExpanded by remember { mutableStateOf(true) }
    var showCreateMultiDialog by remember { mutableStateOf(false) }
    var manageMultiTarget by remember { mutableStateOf<RedditMultireddit?>(null) }
    var addSubToMultiTarget by remember { mutableStateOf<String?>(null) }
    var deleteMultiConfirmTarget by remember { mutableStateOf<RedditMultireddit?>(null) }
    var isDeletingMulti by remember { mutableStateOf(false) }

    // One-time forced cache wipe on update + sync subscriptions & multireddits when authenticated
    LaunchedEffect(authPrefs.userAccessToken, authPrefs.username) {
        prefsRepo.ensureSubredditCacheSanitized()
        if (!isLoggedIn) {
            prefsRepo.clearSubscribedSubreddits()
            prefsRepo.clearMultireddits()
            return@LaunchedEffect
        }
        launch {
            runSuspendCatching {
                val collected = linkedSetOf<String>()
                var after: String? = null
                var pages = 0
                while (pages < 5) {
                    val listing = RedditNetworkClient.apiService.getSubscribedSubreddits(after = after)
                    listing.data.children
                        .map { it.data.display_name.trim().lowercase(Locale.US) }
                        .filter { it.isNotEmpty() }
                        .forEach { collected.add(it) }
                    after = listing.data.after?.takeIf { it.isNotBlank() }
                    pages++
                    if (after == null) break
                }
                collected.toSet()
            }.onSuccess { remoteNames ->
                prefsRepo.saveSubscribedSubreddits(remoteNames)
            }
        }
        launch {
            runSuspendCatching {
                val element = RedditNetworkClient.apiService.getMyMultireddits()
                MultiredditUtils.parseMultiredditsJson(element)
            }.onSuccess { remoteMultis ->
                prefsRepo.saveMultireddits(remoteMultis)
            }
        }
    }

    if (sidebarTargetSub != null) {
        RifSidebarDialog(
            subreddit = sidebarTargetSub!!,
            onDismiss = { sidebarTargetSub = null },
            onSelectSubreddit = { chosen ->
                sidebarTargetSub = null
                hideKeyboard()
                onSelectSubreddit(chosen)
            }
        )
    }

    if (showCreateMultiDialog) {
        RifCreateEditMultiredditDialog(
            onDismiss = { showCreateMultiDialog = false },
            onSaved = { created ->
                showCreateMultiDialog = false
                if (created.canonicalFeedKey.isNotBlank()) {
                    hideKeyboard()
                    onSelectSubreddit(created.canonicalFeedKey)
                }
            }
        )
    }

    if (manageMultiTarget != null) {
        val target = manageMultiTarget!!
        RifMultiredditDialog(
            multiPath = target.canonicalFeedKey,
            initialMulti = target,
            onDismiss = { manageMultiTarget = null },
            onSelectSubreddit = { chosenSub ->
                manageMultiTarget = null
                hideKeyboard()
                onSelectSubreddit(chosenSub)
            },
            onOpenAuthorProfile = { owner ->
                manageMultiTarget = null
                hideKeyboard()
                onOpenAuthorProfile?.invoke(owner)
            },
            onMultiDeleted = {
                manageMultiTarget = null
                if (MultiredditUtils.normalizeFeedTarget(currentSubreddit, authPrefs.username)
                        .equals(target.canonicalFeedKey, ignoreCase = true)
                ) {
                    onSelectSubreddit("frontpage")
                }
            }
        )
    }

    if (addSubToMultiTarget != null) {
        RifAddSubredditToMultiDialog(
            subreddit = addSubToMultiTarget!!,
            onDismiss = { addSubToMultiTarget = null }
        )
    }

    if (deleteMultiConfirmTarget != null) {
        val targetMulti = deleteMultiConfirmTarget!!
        val owner = targetMulti.resolvedOwner.ifBlank { authPrefs.username.orEmpty() }
        val slug = targetMulti.resolvedName
        AlertDialog(
            onDismissRequest = { if (!isDeletingMulti) deleteMultiConfirmTarget = null },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Delete multireddit?") },
            text = {
                Text(
                    text = "Delete /m/${targetMulti.resolvedDisplayName} permanently? This cannot be undone.",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeletingMulti && owner.isNotBlank() && slug.isNotBlank(),
                    onClick = {
                        isDeletingMulti = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.deleteMultireddit(owner, slug)
                            }.onSuccess {
                                isDeletingMulti = false
                                deleteMultiConfirmTarget = null
                                val key = targetMulti.canonicalFeedKey.lowercase(Locale.ROOT)
                                prefsRepo.saveMultireddits(
                                    savedMultis.filterNot {
                                        it.canonicalFeedKey.lowercase(Locale.ROOT) == key
                                    }
                                )
                                Toast.makeText(
                                    context,
                                    "Deleted /m/${targetMulti.resolvedDisplayName}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (MultiredditUtils.normalizeFeedTarget(currentSubreddit, authPrefs.username)
                                        .equals(targetMulti.canonicalFeedKey, ignoreCase = true)
                                ) {
                                    onSelectSubreddit("frontpage")
                                }
                            }.onFailure { err ->
                                isDeletingMulti = false
                                Toast.makeText(
                                    context,
                                    "Failed to delete: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isDeletingMulti) "Deleting…" else "Delete",
                        color = RwfPalette.VoteArrowUpvoteRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeletingMulti,
                    onClick = { deleteMultiConfirmTarget = null }
                ) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    val combinedSubreddits = remember(subreddits, savedSubs, favoriteSubs, blockedSubreddits, isLoggedIn) {
        val raw = if (!isLoggedIn) {
            subreddits.toSet() + favoriteSubs
        } else if (savedSubs.isNotEmpty()) {
            savedSubs + favoriteSubs
        } else {
            subreddits.toSet() + favoriteSubs
        }
        raw.map { it.lowercase(Locale.US) }
            .filter { it !in blockedSubreddits }
            .distinct()
    }

    val sortedFilteredSubreddits = remember(combinedSubreddits, favoriteSubs, query) {
        val trimmed = query.trim().removePrefix("/r/").removePrefix("r/")
        val filtered = if (trimmed.isEmpty()) {
            combinedSubreddits
        } else {
            combinedSubreddits.filter { it.contains(trimmed, ignoreCase = true) }
        }
        filtered.sortedWith(
            compareByDescending<String> { it in favoriteSubs }
                .thenBy { it }
        )
    }

    val filteredSortedMultis = remember(savedMultis, favoriteMultis, query) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            savedMultis
        } else {
            savedMultis.filter { multi ->
                multi.resolvedDisplayName.contains(trimmed, ignoreCase = true) ||
                    multi.resolvedName.contains(trimmed, ignoreCase = true) ||
                    multi.subredditNames.any { it.contains(trimmed, ignoreCase = true) }
            }
        }
        filtered.sortedWith(
            compareByDescending<RedditMultireddit> {
                it.canonicalFeedKey.lowercase(Locale.ROOT) in favoriteMultis
            }.thenBy { it.resolvedDisplayName.lowercase(Locale.ROOT) }
        )
    }

    val showMultiredditsSection = isLoggedIn || savedMultis.isNotEmpty()
    val normalizedCurrentFeed = remember(currentSubreddit, authPrefs.username) {
        MultiredditUtils.normalizeFeedTarget(currentSubreddit, authPrefs.username)
    }

    val submitTypedSubreddit = {
        val rawInput = query.trim()
        val parsedMulti = MultiredditUtils.parseMultiredditPath(rawInput)
        if (parsedMulti != null) {
            query = ""
            hideKeyboard()
            onSelectSubreddit(MultiredditUtils.normalizeFeedTarget(rawInput, authPrefs.username))
        } else {
            val target = rawInput.removePrefix("/r/").removePrefix("r/").trim('/')
            if (target.isNotEmpty()) {
                query = ""
                hideKeyboard()
                onSelectSubreddit(target)
            }
        }
    }

    ModalDrawerSheet(
        modifier = modifier
            .width(310.dp)
            .fillMaxHeight(),
        drawerShape = RectangleShape,
        drawerContainerColor = DrawerBackgroundDark,
        drawerContentColor = RwfPalette.TitleUnreadDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .background(DrawerBackgroundDark)
        ) {
            // 1. Compact "Go to subreddit" input bar + direct "GO" button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DrawerHeaderSurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            text = "Go to subreddit / multi",
                            fontSize = 13.sp,
                            color = RwfPalette.DomainTextDark
                        )
                    },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear input",
                                    tint = RwfPalette.MetadataTextDark,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitTypedSubreddit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RwfPalette.AccentDarkTheme,
                        unfocusedBorderColor = RwfPalette.DividerLineDark,
                        focusedTextColor = RwfPalette.TitleUnreadDark,
                        unfocusedTextColor = RwfPalette.TitleUnreadDark,
                        cursorColor = RwfPalette.AccentDarkTheme
                    ),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = submitTypedSubreddit,
                    enabled = query.isNotBlank(),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RwfPalette.FlairBackgroundDark,
                        contentColor = RwfPalette.AccentDarkTheme,
                        disabledContainerColor = RwfPalette.DarkGraySurface,
                        disabledContentColor = RwfPalette.DomainTextDark
                    )
                ) {
                    Text(
                        text = "GO",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

            // 2. Top Standard Feeds: 'reddit: front page', '/r/all', '/r/popular'
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DrawerHeaderSurfaceDark)
            ) {
                DrawerQuickFeedRow(
                    label = "reddit: front page",
                    subtitle = "Subscribed frontpage",
                    isSelected = currentSubreddit.equals("frontpage", ignoreCase = true) ||
                        currentSubreddit.equals("front page", ignoreCase = true),
                    onClick = {
                        hideKeyboard()
                        onSelectSubreddit("frontpage")
                    }
                )
                if (appSettings.showAllInDrawer) {
                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

                    DrawerQuickFeedRow(
                        label = "/r/all",
                        subtitle = "All subreddits",
                        isSelected = currentSubreddit.equals("all", ignoreCase = true),
                        onClick = {
                            hideKeyboard()
                            onSelectSubreddit("all")
                        },
                        onHide = {
                            coroutineScope.launch {
                                prefsRepo.updateAppSettings { it.copy(showAllInDrawer = false) }
                            }
                            Toast.makeText(
                                context,
                                "Hid /r/all — re-enable in Settings → Appearance",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
                if (appSettings.showPopularInDrawer) {
                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

                    DrawerQuickFeedRow(
                        label = "/r/popular",
                        subtitle = "Popular posts",
                        isSelected = currentSubreddit.equals("popular", ignoreCase = true),
                        onClick = {
                            hideKeyboard()
                            onSelectSubreddit("popular")
                        },
                        onHide = {
                            coroutineScope.launch {
                                prefsRepo.updateAppSettings { it.copy(showPopularInDrawer = false) }
                            }
                            Toast.makeText(
                                context,
                                "Hid /r/popular — re-enable in Settings → Appearance",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = RwfPalette.PaneDividerGray50)

            // 3. Multireddits + Subscribed / Favorite Subreddits List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DrawerBackgroundDark)
            ) {
                if (showMultiredditsSection) {
                    item(key = "multireddits_section_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DrawerHeaderSurfaceDark)
                                .clickable { multisExpanded = !multisExpanded }
                                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = RwfIcons.Multireddit,
                                contentDescription = "Multireddits",
                                tint = RwfPalette.AccentDarkTheme,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "MULTIREDDITS (${filteredSortedMultis.size})",
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (isLoggedIn) {
                                IconButton(
                                    onClick = { showCreateMultiDialog = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Create multireddit",
                                        tint = RwfPalette.AccentDarkTheme,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (multisExpanded) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = if (multisExpanded) "Collapse multireddits" else "Expand multireddits",
                                tint = RwfPalette.DomainTextDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                    }

                    if (multisExpanded) {
                        if (filteredSortedMultis.isEmpty() && isLoggedIn && query.isBlank()) {
                            item(key = "multireddits_empty_hint") {
                                Text(
                                    text = "No multireddits yet — tap + to create",
                                    color = RwfPalette.DomainTextDark,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = RwfPalette.DividerLineDark.copy(alpha = 0.6f)
                                )
                            }
                        } else {
                            items(
                                items = filteredSortedMultis,
                                key = { multi -> "multi_${multi.canonicalFeedKey}" },
                                contentType = { "multireddit_row" }
                            ) { multi ->
                                val canonicalKey = multi.canonicalFeedKey
                                val isMultiSelected = MultiredditUtils.isMultiredditTarget(currentSubreddit) &&
                                    normalizedCurrentFeed.equals(canonicalKey, ignoreCase = true)
                                val isFavorite = canonicalKey.lowercase(Locale.ROOT) in favoriteMultis
                                val isEditable = isLoggedIn && (
                                    multi.can_edit ||
                                        (!authPrefs.username.isNullOrBlank() &&
                                            multi.resolvedOwner.equals(authPrefs.username, ignoreCase = true))
                                    )
                                DrawerMultiredditRow(
                                    multi = multi,
                                    isSelected = isMultiSelected,
                                    isFavorite = isFavorite,
                                    isEditable = isEditable,
                                    onSelect = {
                                        query = ""
                                        hideKeyboard()
                                        onSelectSubreddit(canonicalKey)
                                    },
                                    onToggleFavorite = {
                                        coroutineScope.launch {
                                            prefsRepo.toggleFavoriteMultireddit(canonicalKey)
                                        }
                                    },
                                    onViewManage = {
                                        manageMultiTarget = multi
                                    },
                                    onShare = {
                                        val shareUrl = MultiredditUtils.formatFeedWebUrl(canonicalKey, "hot")
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(sendIntent, "Share ${multi.resolvedDisplayName}")
                                        )
                                    },
                                    onDelete = if (isEditable) {
                                        { deleteMultiConfirmTarget = multi }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }

                    item(key = "subreddits_section_header") {
                        Text(
                            text = "SUBREDDITS",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DrawerHeaderSurfaceDark)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                    }
                }

                items(
                    items = sortedFilteredSubreddits,
                    key = { sub -> sub },
                    contentType = { "subreddit_row" }
                ) { sub ->
                    val isSelected = currentSubreddit.equals(sub, ignoreCase = true)
                    val isFavorite = sub in favoriteSubs
                    val isSubscribed = isLoggedIn && sub in savedSubs
                    DrawerSubredditRow(
                        subreddit = sub,
                        isSelected = isSelected,
                        isFavorite = isFavorite,
                        isSubscribed = isSubscribed,
                        isLoggedIn = isLoggedIn,
                        onSelect = {
                            query = ""
                            hideKeyboard()
                            onSelectSubreddit(sub)
                        },
                        onToggleFavorite = {
                            coroutineScope.launch {
                                prefsRepo.toggleFavoriteSubreddit(sub)
                            }
                        },
                        onToggleSubscribe = {
                            val nextState = !isSubscribed
                            coroutineScope.launch {
                                prefsRepo.toggleLocalSubscription(sub, nextState)
                                runSuspendCatching {
                                    RedditNetworkClient.apiService.setSubredditSubscription(
                                        action = if (nextState) "sub" else "unsub",
                                        subredditName = sub
                                    )
                                }
                            }
                            Toast.makeText(
                                context,
                                if (nextState) "Subscribed to /r/$sub" else "Unsubscribed from /r/$sub",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onOpenSidebar = {
                            sidebarTargetSub = sub
                        },
                        onAddToMultireddit = {
                            addSubToMultiTarget = sub
                        },
                        onShare = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://www.reddit.com/r/$sub")
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share /r/$sub"))
                        }
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = RwfPalette.PaneDividerGray50)

            // 4. Bottom Item: Direct Shortcut to Open "Settings"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DrawerHeaderSurfaceDark)
                    .clickable {
                        hideKeyboard()
                        onOpenSettings()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = RwfPalette.AccentDarkTheme,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Settings (BYOK & OAuth)",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DrawerQuickFeedRow(
    label: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onHide: (() -> Unit)? = null
) {
    val bgColor = if (isSelected) DrawerSelectedRowDark else Color.Transparent
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(
                start = 14.dp,
                end = if (onHide != null) 4.dp else 14.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isSelected) RwfPalette.AccentDarkTheme else RwfPalette.TitleUnreadDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = RwfPalette.DomainTextDark,
                fontSize = 11.sp
            )
        }
        if (onHide != null) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "$label options",
                        tint = RwfPalette.DomainTextDark,
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(RwfPalette.CardBackgroundDark)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Hide from drawer",
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onHide()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Multireddit drawer list row:
 * `[ layers ]  [ ★ ]  [ ⋮ ]   Display Name   N subs`
 */
@Composable
private fun DrawerMultiredditRow(
    multi: RedditMultireddit,
    isSelected: Boolean,
    isFavorite: Boolean,
    isEditable: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onViewManage: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val bgColor = if (isSelected) DrawerSelectedRowDark else Color.Transparent
    var popupExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = RwfIcons.Multireddit,
                    contentDescription = "Multireddit",
                    tint = if (isSelected) RwfPalette.AccentDarkTheme else RwfPalette.DomainTextDark,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = if (isFavorite) "Unstar multireddit" else "Star multireddit",
                    tint = if (isFavorite) RwfPalette.SpoilerYellow else RwfPalette.DomainTextDark.copy(alpha = 0.25f),
                    modifier = Modifier.size(17.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { popupExpanded = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Multireddit actions",
                        tint = RwfPalette.DomainTextDark,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = popupExpanded,
                    onDismissRequest = { popupExpanded = false },
                    modifier = Modifier.background(RwfPalette.CardBackgroundDark)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isEditable) "View subreddits / Edit" else "View subreddits",
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            popupExpanded = false
                            onViewManage()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                        onClick = {
                            popupExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Unstar" else "Star", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                        onClick = {
                            popupExpanded = false
                            onToggleFavorite()
                        }
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = RwfPalette.VoteArrowUpvoteRed, fontSize = 14.sp) },
                            onClick = {
                                popupExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = multi.resolvedDisplayName,
                color = if (isSelected) RwfPalette.AccentDarkTheme else RwfPalette.TitleUnreadDark,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "${multi.subredditNames.size} subs",
                color = RwfPalette.DomainTextDark,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = RwfPalette.DividerLineDark.copy(alpha = 0.6f)
        )
    }
}

/**
 * Subreddit drawer list row:
 * `[ + / ✓ ]  [ ★ ]  [ ⋮ ]   subreddit`
 */
@Composable
private fun DrawerSubredditRow(
    subreddit: String,
    isSelected: Boolean,
    isFavorite: Boolean,
    isSubscribed: Boolean,
    isLoggedIn: Boolean = false,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onOpenSidebar: () -> Unit,
    onAddToMultireddit: (() -> Unit)? = null,
    onShare: () -> Unit
) {
    val bgColor = if (isSelected) DrawerSelectedRowDark else Color.Transparent
    var popupExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleSubscribe,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (isSubscribed) "Subscribed" else "Subscribe",
                    tint = if (isSubscribed) RwfPalette.AccentDarkTheme else RwfPalette.DomainTextDark,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = if (isFavorite) "Unstar subreddit" else "Star subreddit",
                    tint = if (isFavorite) RwfPalette.SpoilerYellow else RwfPalette.DomainTextDark.copy(alpha = 0.25f),
                    modifier = Modifier.size(17.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { popupExpanded = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Subreddit actions",
                        tint = RwfPalette.DomainTextDark,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = popupExpanded,
                    onDismissRequest = { popupExpanded = false },
                    modifier = Modifier.background(RwfPalette.CardBackgroundDark)
                ) {
                    DropdownMenuItem(
                        text = { Text("Sidebar", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                        onClick = {
                            popupExpanded = false
                            onOpenSidebar()
                        }
                    )
                    if (isLoggedIn && onAddToMultireddit != null) {
                        DropdownMenuItem(
                            text = { Text("Add to multireddit", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                            onClick = {
                                popupExpanded = false
                                onAddToMultireddit()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                        onClick = {
                            popupExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Unstar" else "Star", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                        onClick = {
                            popupExpanded = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isSubscribed) "Unsubscribe" else "Subscribe", color = RwfPalette.TitleUnreadDark, fontSize = 14.sp) },
                        onClick = {
                            popupExpanded = false
                            onToggleSubscribe()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = subreddit,
                color = if (isSelected) RwfPalette.AccentDarkTheme else RwfPalette.TitleUnreadDark,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = RwfPalette.DividerLineDark.copy(alpha = 0.6f)
        )
    }
}

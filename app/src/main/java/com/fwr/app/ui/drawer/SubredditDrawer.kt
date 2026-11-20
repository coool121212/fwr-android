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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.components.RifSidebarDialog
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
    subreddits: List<String> = DEFAULT_SUBSCRIBED_SUBREDDITS
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
    val blockedSubreddits by prefsRepo.blockedSubreddits.collectAsStateWithLifecycle()
    val isLoggedIn = !authPrefs.userAccessToken.isNullOrBlank()

    var query by remember { mutableStateOf("") }
    var sidebarTargetSub by remember { mutableStateOf<String?>(null) }

    // One-time forced cache wipe on update + sync subscriptions only when authenticated
    LaunchedEffect(authPrefs.userAccessToken, authPrefs.username) {
        prefsRepo.ensureSubredditCacheSanitized()
        if (!isLoggedIn) {
            prefsRepo.clearSubscribedSubreddits()
            return@LaunchedEffect
        }
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

    val submitTypedSubreddit = {
        val target = query.trim().removePrefix("/r/").removePrefix("r/")
        if (target.isNotEmpty()) {
            query = ""
            hideKeyboard()
            onSelectSubreddit(target)
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
                            text = "Go to subreddit",
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

            // 3. Subscribed / Favorite Subreddits List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DrawerBackgroundDark)
            ) {
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
 * Subreddit drawer list row:
 * `[ + / ✓ ]  [ ★ ]  [ ⋮ ]   subreddit`
 */
@Composable
private fun DrawerSubredditRow(
    subreddit: String,
    isSelected: Boolean,
    isFavorite: Boolean,
    isSubscribed: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onOpenSidebar: () -> Unit,
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

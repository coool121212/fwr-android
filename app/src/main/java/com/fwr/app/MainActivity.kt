package com.fwr.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.notifications.UnreadMailScheduler
import com.fwr.app.data.notifications.UnreadMailWorker
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.data.update.AppUpdateManager
import com.fwr.app.ui.update.UpdateDialogHost
import com.fwr.app.ui.comments.CommentsScreen
import com.fwr.app.ui.components.FeedScreen
import com.fwr.app.ui.feed.FeedUiState
import com.fwr.app.ui.feed.FeedViewModel
import com.fwr.app.ui.inbox.InboxScreen
import com.fwr.app.ui.media.MediaViewerScreen
import com.fwr.app.ui.profile.ProfileScreen
import com.fwr.app.ui.settings.SettingsScreen
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.ui.theme.RwfTheme
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.RedditLinkTarget
import com.fwr.app.util.ResolvedMedia
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var preferencesRepository: UserPreferencesRepository
    private lateinit var oauthManager: RedditOAuthManager

    private data class PostNavEntry(
        val post: RedditPost,
        val focusCommentId: String? = null,
        val contextDepth: Int? = null
    )

    private var showSettingsScreen by mutableStateOf(false)
    private var selectedProfileUsername by mutableStateOf<String?>(null)
    private var selectedInboxWhere by mutableStateOf<String?>(null)
    private var selectedPostEntry by mutableStateOf<PostNavEntry?>(null)
    private var postBackStack by mutableStateOf<List<PostNavEntry>>(emptyList())
    private var activeMediaPost by mutableStateOf<RedditPost?>(null)
    private var oauthStatusMessage by mutableStateOf<String?>(null)

    private fun openPost(
        post: RedditPost,
        focusCommentId: String? = null,
        contextDepth: Int? = null
    ) {
        var resolvedFocusCommentId = focusCommentId?.removePrefix("t1_")?.trim()?.takeIf { it.isNotEmpty() }
        var resolvedContextDepth = contextDepth?.coerceAtLeast(0)
        if (resolvedFocusCommentId == null && post.permalink.contains("/comments/")) {
            val permalinkUrl = if (post.permalink.startsWith("http", ignoreCase = true)) {
                post.permalink
            } else {
                "https://www.reddit.com/${post.permalink.trimStart('/')}"
            }
            val parsedTarget = MediaResolver.parseRedditLink(permalinkUrl) as? RedditLinkTarget.Post
            if (parsedTarget != null && !parsedTarget.commentId.isNullOrBlank()) {
                resolvedFocusCommentId = parsedTarget.commentId
                resolvedContextDepth = resolvedContextDepth ?: parsedTarget.context
            }
        }
        val nextEntry = PostNavEntry(
            post = post,
            focusCommentId = resolvedFocusCommentId,
            contextDepth = resolvedContextDepth
        )
        val current = selectedPostEntry
        if (current != null &&
            (current.post.id != nextEntry.post.id ||
                current.focusCommentId != nextEntry.focusCommentId ||
                current.contextDepth != nextEntry.contextDepth)
        ) {
            postBackStack = (postBackStack + current).takeLast(MAX_POST_BACK_STACK)
        }
        selectedPostEntry = nextEntry
    }

    private fun popPostOrClear() {
        if (postBackStack.isNotEmpty()) {
            selectedPostEntry = postBackStack.last()
            postBackStack = postBackStack.dropLast(1)
        } else {
            selectedPostEntry = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureCoilImageLoader(applicationContext)

        preferencesRepository = UserPreferencesRepository.getInstance(applicationContext)
        RedditNetworkClient.initialize(
            context = applicationContext,
            prefsRepo = preferencesRepository
        )
        oauthManager = RedditNetworkClient.getOAuthManager(applicationContext)

        // Sync account identity on startup if a user OAuth token already exists
        lifecycleScope.launch {
            val snap = preferencesRepository.awaitAuthSnapshot()
            if (!snap.userAccessToken.isNullOrBlank()) {
                RedditNetworkClient.setOAuthToken(snap.userAccessToken)
                oauthManager.fetchAndStoreMeIdentity(snap.userAccessToken)
            }
        }

        handleIncomingOAuthIntent(intent)

        // Check GitHub Releases for a newer build (throttled, respects the user setting)
        AppUpdateManager.checkOnLaunch(applicationContext)

        enableEdgeToEdge()
        setContent {
            val appSettings by preferencesRepository.appSettings.collectAsStateWithLifecycle()

            LaunchedEffect(appSettings.obscureRecentApps) {
                if (appSettings.obscureRecentApps) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(appSettings.inboxBadgeEnabled, appSettings.notificationInterval) {
                UnreadMailScheduler.syncSchedule(this@MainActivity, appSettings)
            }

            LaunchedEffect(appSettings.darkTheme) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !appSettings.darkTheme
                controller.isAppearanceLightNavigationBars = !appSettings.darkTheme
            }

            LaunchedEffect(Unit) {
                RedditOAuthManager.reauthEvents.collect { message ->
                    oauthStatusMessage = message
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            RwfTheme(
                darkTheme = appSettings.darkTheme,
                amoledBlack = appSettings.amoledBlackBackground,
                uiScalePercent = appSettings.uiScalePercent
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val feedViewModel: FeedViewModel = viewModel()
                    val feedUiState by feedViewModel.uiState.collectAsStateWithLifecycle()
                    val activeUrlMedia by MediaResolver.activeInAppMedia.collectAsStateWithLifecycle()
                    val activeRedditTarget by MediaResolver.activeRedditLinkTarget.collectAsStateWithLifecycle()
                    val activeOAuthUrl by RedditOAuthManager.activeOAuthUrl.collectAsStateWithLifecycle()
                    val authPrefs by preferencesRepository.authPreferences.collectAsStateWithLifecycle()

                    LaunchedEffect(activeRedditTarget) {
                        val target = activeRedditTarget ?: return@LaunchedEffect
                        MediaResolver.consumeRedditLinkTarget()
                        activeMediaPost = null
                        MediaResolver.dismissInAppMedia()
                        when (target) {
                            is RedditLinkTarget.Post -> {
                                showSettingsScreen = false
                                val permalink = if (target.subreddit.isNotBlank()) {
                                    "/r/${target.subreddit}/comments/${target.articleId}"
                                } else {
                                    "/comments/${target.articleId}"
                                }
                                openPost(
                                    post = RedditPost(
                                        id = target.articleId,
                                        subreddit = target.subreddit,
                                        title = target.title,
                                        permalink = permalink
                                    ),
                                    focusCommentId = target.commentId,
                                    contextDepth = target.context
                                )
                            }
                            is RedditLinkTarget.Subreddit -> {
                                showSettingsScreen = false
                                selectedPostEntry = null
                                postBackStack = emptyList()
                                selectedProfileUsername = null
                                selectedInboxWhere = null
                                feedViewModel.selectSubreddit(target.subreddit)
                                if (!target.sort.isNullOrBlank()) {
                                    feedViewModel.selectSort(target.sort)
                                }
                            }
                            is RedditLinkTarget.User -> {
                                showSettingsScreen = false
                                selectedPostEntry = null
                                postBackStack = emptyList()
                                selectedInboxWhere = null
                                selectedProfileUsername = target.username
                            }
                            is RedditLinkTarget.ShareLink -> {
                                resolveAndOpenRedditShareLink(target.url)
                            }
                        }
                    }

                    val hasMediaOverlay = activeMediaPost != null || activeUrlMedia != null
                    val hasAnyOverlay = showSettingsScreen ||
                        selectedPostEntry != null ||
                        selectedInboxWhere != null ||
                        selectedProfileUsername != null ||
                        hasMediaOverlay ||
                        activeOAuthUrl != null

                    Box(modifier = Modifier.fillMaxSize()) {
                        // Base layer: keep FeedScreen continuously composed to preserve scroll state
                        FeedScreen(
                            viewModel = feedViewModel,
                            isOverlayActive = hasAnyOverlay,
                            onOpenSettingsClick = {
                                showSettingsScreen = true
                            },
                            onOpenProfileClick = { username ->
                                selectedProfileUsername = username
                            },
                            onOpenInboxClick = { where ->
                                selectedInboxWhere = where
                            },
                            onPostClick = { post ->
                                openPost(post)
                            },
                            onThumbnailClick = { post ->
                                handleMediaRouting(post)
                            }
                        )

                        if (selectedProfileUsername != null) {
                            BackHandler(
                                enabled = !hasMediaOverlay &&
                                    selectedInboxWhere == null &&
                                    selectedPostEntry == null &&
                                    !showSettingsScreen
                            ) {
                                selectedProfileUsername = null
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                ProfileScreen(
                                    username = selectedProfileUsername!!,
                                    onBackClick = {
                                        selectedProfileUsername = null
                                    },
                                    onPostClick = { post ->
                                        openPost(post)
                                    },
                                    onThumbnailClick = { post ->
                                        handleMediaRouting(post)
                                    },
                                    onSelectSubreddit = { chosenSub ->
                                        selectedProfileUsername = null
                                        feedViewModel.selectSubreddit(chosenSub)
                                    },
                                    onOpenAuthorProfile = { author ->
                                        selectedProfileUsername = author
                                    }
                                )
                            }
                        }

                        if (selectedInboxWhere != null) {
                            BackHandler(
                                enabled = !hasMediaOverlay &&
                                    selectedPostEntry == null &&
                                    !showSettingsScreen
                            ) {
                                selectedInboxWhere = null
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                InboxScreen(
                                    initialWhere = selectedInboxWhere!!,
                                    onBackClick = {
                                        selectedInboxWhere = null
                                    },
                                    onOpenContextPost = { contextPost ->
                                        openPost(contextPost)
                                    },
                                    onOpenAuthorProfile = { author ->
                                        selectedInboxWhere = null
                                        selectedProfileUsername = author
                                    },
                                    onSelectSubreddit = { chosenSub ->
                                        selectedInboxWhere = null
                                        feedViewModel.selectSubreddit(chosenSub)
                                    }
                                )
                            }
                        }

                        if (selectedPostEntry != null) {
                            val feedPosts = (feedUiState as? FeedUiState.Success)?.posts.orEmpty()
                            val activeEntry = selectedPostEntry!!
                            val activePost = activeEntry.post
                            val currentPostIndex = remember(activePost.id, feedPosts) {
                                feedPosts.indexOfFirst { it.id == activePost.id }
                            }
                            BackHandler(enabled = !hasMediaOverlay && !showSettingsScreen) {
                                popPostOrClear()
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                key(activePost.id, activeEntry.focusCommentId, activeEntry.contextDepth) {
                                    CommentsScreen(
                                        post = activePost,
                                        initialFocusCommentId = activeEntry.focusCommentId,
                                        initialContextDepth = activeEntry.contextDepth,
                                        onOpenCommentPermalink = { targetPost, commentId, ctxDepth ->
                                            openPost(
                                                post = targetPost,
                                                focusCommentId = commentId,
                                                contextDepth = ctxDepth
                                            )
                                        },
                                        onBackClick = {
                                            popPostOrClear()
                                        },
                                        onOpenMediaClick = { post ->
                                            handleMediaRouting(post)
                                        },
                                        onSelectSubreddit = { chosenSub ->
                                            selectedPostEntry = null
                                            postBackStack = emptyList()
                                            selectedProfileUsername = null
                                            selectedInboxWhere = null
                                            feedViewModel.selectSubreddit(chosenSub)
                                        },
                                        onOpenAuthorProfile = { author ->
                                            selectedPostEntry = null
                                            postBackStack = emptyList()
                                            selectedProfileUsername = author
                                        },
                                        onOpenSettingsClick = {
                                            selectedPostEntry = null
                                            postBackStack = emptyList()
                                            showSettingsScreen = true
                                        },
                                        onPrevPostClick = if (currentPostIndex > 0) {
                                            {
                                                val prevPost = feedPosts[currentPostIndex - 1]
                                                selectedPostEntry = PostNavEntry(prevPost)
                                            }
                                        } else {
                                            null
                                        },
                                        onNextPostClick = if (currentPostIndex in 0 until feedPosts.lastIndex) {
                                            {
                                                val nextPost = feedPosts[currentPostIndex + 1]
                                                selectedPostEntry = PostNavEntry(nextPost)
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                }
                            }
                        }

                        if (showSettingsScreen) {
                            BackHandler(enabled = !hasMediaOverlay && activeOAuthUrl == null) {
                                showSettingsScreen = false
                                oauthStatusMessage = null
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                SettingsScreen(
                                    preferencesRepository = preferencesRepository,
                                    oauthManager = oauthManager,
                                    statusBannerMessage = oauthStatusMessage,
                                    onBackClick = {
                                        showSettingsScreen = false
                                        oauthStatusMessage = null
                                    }
                                )
                            }
                        }

                        // Full-screen in-app Media Viewer & Built-in Browser overlays
                        if (activeMediaPost != null) {
                            BackHandler {
                                activeMediaPost = null
                            }
                            MediaViewerScreen(
                                post = activeMediaPost!!,
                                onDismiss = {
                                    activeMediaPost = null
                                }
                            )
                        } else if (activeUrlMedia != null) {
                            BackHandler {
                                MediaResolver.dismissInAppMedia()
                            }
                            MediaViewerScreen(
                                resolvedMedia = activeUrlMedia!!,
                                onDismiss = {
                                    MediaResolver.dismissInAppMedia()
                                }
                            )
                        }

                        if (activeOAuthUrl != null) {
                            val authorizeUrl = activeOAuthUrl!!
                            key(authorizeUrl) {
                                RifOAuthWebViewOverlay(
                                    authorizeUrl = authorizeUrl,
                                    configuredRedirectUri = authPrefs.redirectUri,
                                    onOAuthCallback = { callbackUri ->
                                        completeOAuthRedirect(callbackUri)
                                    },
                                    onDismiss = {
                                        RedditOAuthManager.dismissInAppAuthorization()
                                    }
                                )
                            }
                        }

                        // In-app updater prompt (GitHub Releases)
                        UpdateDialogHost()
                    }
                }
            }
        }
    }

    /**
     * Routes a post's link/media using [MediaResolver]:
     * - Reddit thread/subreddit/user link -> opens natively inside the app.
     * - Direct image, gallery, video, or external web link -> opens in-app [MediaViewerScreen] / built-in browser overlay.
     * - Self post -> opens [CommentsScreen].
     */
    private fun handleMediaRouting(post: RedditPost) {
        val rawPostUrl = MediaResolver.unwrapRedditMediaUrl(
            (post.url_overridden_by_dest ?: post.url).trim().replace("&amp;", "&")
        )
        val rules = preferencesRepository.externalBrowserRules.value
        val preferExternal = preferencesRepository.appSettings.value.preferExternalBrowser
        val matchingRule = MediaResolver.findMatchingExternalBrowserRule(rawPostUrl, rules)
        if (matchingRule != null && matchingRule.alwaysExternal) {
            MediaResolver.openInExternalApp(this, rawPostUrl)
            return
        }
        when (val resolved = MediaResolver.resolve(post)) {
            is ResolvedMedia.DirectImage,
            is ResolvedMedia.Gallery,
            is ResolvedMedia.RedditVideo,
            is ResolvedMedia.RedgifsVideo,
            is ResolvedMedia.ImgurAlbum -> {
                activeMediaPost = post
            }
            is ResolvedMedia.ExternalWeb -> {
                val redditTarget = MediaResolver.parseRedditLink(resolved.url)
                if (redditTarget != null) {
                    MediaResolver.emitRedditLinkTarget(redditTarget)
                } else if (matchingRule != null || preferExternal) {
                    MediaResolver.openInExternalApp(this, resolved.url)
                } else {
                    activeMediaPost = post
                }
            }
            ResolvedMedia.SelfPost -> {
                openPost(post)
            }
        }
    }

    private fun resolveAndOpenRedditShareLink(shareUrl: String) {
        lifecycleScope.launch {
            val resolvedTarget = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    val noRedirectClient = RedditNetworkClient.mediaHttpClient.newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(6, TimeUnit.SECONDS)
                        .build()
                    var currentUrl = shareUrl
                    var found: RedditLinkTarget? = null
                    repeat(4) {
                        val request = Request.Builder()
                            .url(currentUrl)
                            .head()
                            .build()
                        val redirectLocation = noRedirectClient.newCall(request).execute().use { response ->
                            if (response.code in 300..399) {
                                val location = response.header("Location")
                                if (!location.isNullOrBlank()) {
                                    response.request.url.resolve(location)?.toString()
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                        if (!redirectLocation.isNullOrBlank()) {
                            currentUrl = redirectLocation
                            val parsed = MediaResolver.parseRedditLink(currentUrl)
                            if (parsed != null && parsed !is RedditLinkTarget.ShareLink) {
                                found = parsed
                                return@runSuspendCatching found
                            }
                        } else {
                            return@runSuspendCatching found
                        }
                    }
                    found
                }.getOrNull()
            }
            if (resolvedTarget != null) {
                MediaResolver.emitRedditLinkTarget(resolvedTarget)
            } else {
                MediaResolver.openSystemBrowser(this@MainActivity, shareUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingOAuthIntent(intent)
    }

    private fun handleIncomingOAuthIntent(intent: Intent?) {
        val openInboxWhere = intent?.getStringExtra(UnreadMailWorker.EXTRA_OPEN_INBOX)
        if (!openInboxWhere.isNullOrBlank()) {
            selectedInboxWhere = openInboxWhere
        }
        val data: Uri = intent?.data ?: return
        if (RedditOAuthManager.isSupportedRedirectUri(data, preferencesRepository.getSnapshot().redirectUri)) {
            completeOAuthRedirect(data)
        } else if (data.scheme == "http" || data.scheme == "https") {
            MediaResolver.parseRedditLink(data.toString())?.let { target ->
                MediaResolver.emitRedditLinkTarget(target)
            }
        }
    }

    private fun completeOAuthRedirect(data: Uri) {
        val stayedInActiveFlow =
            selectedPostEntry != null || selectedInboxWhere != null || selectedProfileUsername != null
        oauthStatusMessage = "Completing Reddit OAuth2 token exchange…"
        Toast.makeText(this, "Exchanging Reddit OAuth token…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = oauthManager.handleRedirectUri(data)
            result.onSuccess { tokenResponse ->
                RedditNetworkClient.setOAuthToken(tokenResponse.accessToken)
                oauthStatusMessage = "Successfully connected Reddit account!"
                Toast.makeText(
                    this@MainActivity,
                    if (stayedInActiveFlow) {
                        "Reddit account connected with full permissions!"
                    } else {
                        "Successfully connected Reddit account!"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                val errMsg = "OAuth failed: ${error.localizedMessage ?: "Unknown error"}"
                oauthStatusMessage = errMsg
                Toast.makeText(this@MainActivity, errMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val MAX_POST_BACK_STACK = 20
        private val coilLock = Any()

        @Volatile
        private var coilConfigured = false

        private fun ensureCoilImageLoader(appContext: android.content.Context) {
            if (coilConfigured) return
            synchronized(coilLock) {
                if (coilConfigured) return
                Coil.setImageLoader(
                    ImageLoader.Builder(appContext)
                        .okHttpClient { RedditNetworkClient.mediaHttpClient }
                        .memoryCache {
                            MemoryCache.Builder(appContext)
                                .maxSizePercent(0.20)
                                .build()
                        }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(appContext.cacheDir.resolve("image_cache"))
                                .maxSizeBytes(128L * 1024L * 1024L)
                                .build()
                        }
                        .components {
                            if (Build.VERSION.SDK_INT >= 28) {
                                add(ImageDecoderDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                        }
                        .build()
                )
                coilConfigured = true
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RifOAuthWebViewOverlay(
    authorizeUrl: String,
    configuredRedirectUri: String,
    onOAuthCallback: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember(authorizeUrl) { mutableStateOf(authorizeUrl) }
    var loadProgress by remember(authorizeUrl) { mutableIntStateOf(0) }
    var isLoading by remember(authorizeUrl) { mutableStateOf(true) }
    var canGoBack by remember(authorizeUrl) { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        if (canGoBack) {
            webViewRef?.goBack()
        } else {
            onDismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                onPause()
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = RwfPalette.PrimaryDarkTheme,
                        titleContentColor = Color.White
                    ),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close sign in",
                                tint = Color.White
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = "Reddit OAuth Sign In",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: "reddit.com",
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { webViewRef?.reload() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White
                            )
                        }
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Sign-in options",
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                                modifier = Modifier.background(Color(0xFF424242))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open in external browser", color = Color.White) },
                                    onClick = {
                                        overflowExpanded = false
                                        onDismiss()
                                        runCatching {
                                            val externalIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(externalIntent)
                                        }.onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                "Unable to open external browser: ${err.localizedMessage ?: "Error"}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { loadProgress.coerceIn(5, 100) / 100f },
                        color = RwfPalette.AccentDarkTheme,
                        trackColor = Color(0xFF1F1F1F),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        AndroidView(
            factory = { ctx ->
                var handledCallback = false
                fun interceptIfOAuthCallback(view: WebView?, rawUrl: String?): Boolean {
                    if (handledCallback || rawUrl.isNullOrBlank()) return false
                    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
                    if (!RedditOAuthManager.isSupportedRedirectUri(uri, configuredRedirectUri)) {
                        return false
                    }
                    handledCallback = true
                    view?.stopLoading()
                    onDismiss()
                    onOAuthCallback(uri)
                    return true
                }

                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    setBackgroundColor(android.graphics.Color.BLACK)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                            isLoading = newProgress < 100
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            return interceptIfOAuthCallback(view, request?.url?.toString())
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return interceptIfOAuthCallback(view, url)
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (interceptIfOAuthCallback(view, url)) return
                            if (!url.isNullOrBlank()) currentUrl = url
                            isLoading = true
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) currentUrl = url
                            isLoading = false
                            canGoBack = view?.canGoBack() == true
                        }
                    }

                    loadUrl(authorizeUrl)
                    webViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

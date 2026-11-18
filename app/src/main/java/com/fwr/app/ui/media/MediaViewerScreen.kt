package com.fwr.app.ui.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.SubcomposeAsyncImage
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.ResolvedMedia
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen in-app Media Viewer matching RIF's image/gallery/video browser:
 * - Pure black (#000000) background.
 * - Single Image & Gallery: Coil image rendering with pinch-to-zoom, double-tap zoom, and pan.
 * - Gallery: [HorizontalPager] with a compact "1 / N" counter pill.
 * - Reddit Video: Native video playback for `v.redd.it` `fallback_url`.
 * - Top-left Close (X) button and Top-right Share button.
 * - Tap outside or vertical swipe-down gesture to dismiss.
 */
@Composable
fun MediaViewerScreen(
    post: RedditPost,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resolvedMedia = remember(post) { MediaResolver.resolve(post) }
    MediaViewerScreen(
        resolvedMedia = resolvedMedia,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

@Composable
fun MediaViewerScreen(
    resolvedMedia: ResolvedMedia,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()
    var effectiveMedia by remember(resolvedMedia) { mutableStateOf(resolvedMedia) }
    var showOverlayControls by remember(resolvedMedia) { mutableStateOf(true) }
    var isCurrentPageZoomed by remember(resolvedMedia) { mutableStateOf(false) }
    var showDownloadChoiceDialog by remember { mutableStateOf(false) }
    var pendingSaveUrl by remember { mutableStateOf<String?>(null) }
    var isVideoFullscreen by remember(resolvedMedia) { mutableStateOf(false) }
    var isVideoBuffering by remember(resolvedMedia) { mutableStateOf(false) }
    var videoReloadNonce by remember(resolvedMedia) { mutableIntStateOf(0) }
    var videoMenuExpanded by remember(resolvedMedia) { mutableStateOf(false) }
    var isResolvingAsync by remember(resolvedMedia) {
        mutableStateOf(
            resolvedMedia is ResolvedMedia.RedgifsVideo ||
                resolvedMedia is ResolvedMedia.ImgurAlbum ||
                (resolvedMedia is ResolvedMedia.Gallery &&
                    resolvedMedia.imageUrls.isEmpty() &&
                    resolvedMedia.postId.isNotBlank())
        )
    }

    val saveAsDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri: Uri? ->
        val urlToSave = pendingSaveUrl
        pendingSaveUrl = null
        if (targetUri != null && !urlToSave.isNullOrBlank()) {
            Toast.makeText(context, "Saving media to chosen folder…", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                MediaResolver.saveMediaToUri(context, urlToSave, targetUri)
                    .onSuccess {
                        Toast.makeText(context, "Saved media!", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { err ->
                        Toast.makeText(
                            context,
                            "Save failed: ${err.localizedMessage ?: "Error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val urlToSave = pendingSaveUrl
        pendingSaveUrl = null
        if (!urlToSave.isNullOrBlank()) {
            if (granted) {
                MediaResolver.downloadMedia(
                    context = context,
                    url = urlToSave,
                    title = when (val active = effectiveMedia) {
                        is ResolvedMedia.DirectImage -> active.title
                        is ResolvedMedia.Gallery -> active.title
                        is ResolvedMedia.RedditVideo -> active.title
                        is ResolvedMedia.ExternalWeb -> active.title
                        else -> ""
                    }
                )
            } else {
                Toast.makeText(
                    context,
                    "Storage permission denied — use 'Choose folder / Save as…' instead.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(resolvedMedia) {
        when (resolvedMedia) {
            is ResolvedMedia.RedgifsVideo -> {
                val (mp4Url, wasRemoved) = resolveRedgifsMp4(resolvedMedia.gifId)
                isResolvingAsync = false
                if (wasRemoved) {
                    Toast.makeText(context, "This Redgifs video was removed", Toast.LENGTH_LONG).show()
                    onDismiss()
                } else if (!mp4Url.isNullOrBlank()) {
                    effectiveMedia = ResolvedMedia.RedditVideo(
                        fallbackUrl = mp4Url,
                        title = resolvedMedia.title,
                        sourceUrl = resolvedMedia.fallbackWebUrl
                    )
                } else {
                    MediaResolver.openExternalLink(context, resolvedMedia.fallbackWebUrl)
                    onDismiss()
                }
            }

            is ResolvedMedia.ImgurAlbum -> {
                val (urls, descriptions) = resolveImgurAlbum(resolvedMedia.albumId)
                isResolvingAsync = false
                if (urls.size == 1 && urls[0].lowercase().endsWith(".mp4")) {
                    effectiveMedia = ResolvedMedia.RedditVideo(
                        fallbackUrl = urls[0],
                        title = resolvedMedia.title,
                        sourceUrl = resolvedMedia.fallbackWebUrl
                    )
                } else if (urls.isNotEmpty()) {
                    effectiveMedia = ResolvedMedia.Gallery(
                        imageUrls = urls,
                        captions = descriptions,
                        title = resolvedMedia.title
                    )
                } else {
                    MediaResolver.openExternalLink(context, resolvedMedia.fallbackWebUrl)
                    onDismiss()
                }
            }

            is ResolvedMedia.Gallery -> {
                if (resolvedMedia.imageUrls.isEmpty() && resolvedMedia.postId.isNotBlank()) {
                    val cleanId = resolvedMedia.postId.removePrefix("t3_")
                    val refreshed = runSuspendCatching {
                        RedditNetworkClient.apiService.getPostComments(
                            articleId = cleanId,
                            limit = 1
                        )
                    }.getOrNull()
                    val postFromComments = refreshed?.firstOrNull()
                        ?.data
                        ?.children
                        ?.firstOrNull()
                        ?.data as? RedditPost
                    val urls = postFromComments?.let { MediaResolver.extractGalleryUrls(it) }.orEmpty()
                    val caps = postFromComments?.gallery_data?.items?.map { it.caption }.orEmpty()
                    isResolvingAsync = false
                    if (urls.isNotEmpty()) {
                        effectiveMedia = resolvedMedia.copy(imageUrls = urls, captions = caps)
                    } else {
                        Toast.makeText(context, "Unable to load Reddit gallery", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                } else {
                    isResolvingAsync = false
                }
            }

            else -> {
                isResolvingAsync = false
            }
        }
    }

    val imageUrls: List<String> = remember(effectiveMedia) {
        when (val active = effectiveMedia) {
            is ResolvedMedia.DirectImage -> listOf(active.url)
            is ResolvedMedia.Gallery -> active.imageUrls
            is ResolvedMedia.RedditVideo -> listOf(active.fallbackUrl)
            is ResolvedMedia.ExternalWeb -> listOf(active.url)
            is ResolvedMedia.RedgifsVideo -> emptyList()
            is ResolvedMedia.ImgurAlbum -> emptyList()
            ResolvedMedia.SelfPost -> emptyList()
        }
    }

    val captions: List<String?> = remember(effectiveMedia) {
        when (val active = effectiveMedia) {
            is ResolvedMedia.Gallery -> active.captions
            else -> emptyList()
        }
    }

    val title: String = remember(effectiveMedia) {
        when (val active = effectiveMedia) {
            is ResolvedMedia.DirectImage -> active.title
            is ResolvedMedia.Gallery -> active.title
            is ResolvedMedia.RedditVideo -> active.title
            is ResolvedMedia.RedgifsVideo -> active.title
            is ResolvedMedia.ImgurAlbum -> active.title
            is ResolvedMedia.ExternalWeb -> active.title
            ResolvedMedia.SelfPost -> ""
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { imageUrls.size.coerceAtLeast(1) }
    )

    val currentShareUrl = imageUrls.getOrNull(pagerState.currentPage).orEmpty()

    fun triggerDirectDownloadsSave(url: String, mediaTitle: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveUrl = url
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            MediaResolver.downloadMedia(context, url, mediaTitle)
        }
    }

    if (showDownloadChoiceDialog && currentShareUrl.isNotEmpty()) {
        var askEveryTime by remember(showDownloadChoiceDialog) {
            mutableStateOf(appSettings.askDownloadLocation)
        }
        AlertDialog(
            onDismissRequest = { showDownloadChoiceDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Save Media") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Choose where to save this file:",
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 14.sp
                    )
                    Surface(
                        color = RwfPalette.DarkGraySurface,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showDownloadChoiceDialog = false
                                coroutineScope.launch {
                                    prefsRepo.updateAppSettings { it.copy(askDownloadLocation = askEveryTime) }
                                }
                                triggerDirectDownloadsSave(currentShareUrl, title)
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Save to Downloads folder",
                                color = RwfPalette.TitleUnreadDark,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Quick download into system Downloads",
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Surface(
                        color = RwfPalette.DarkGraySurface,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showDownloadChoiceDialog = false
                                coroutineScope.launch {
                                    prefsRepo.updateAppSettings { it.copy(askDownloadLocation = askEveryTime) }
                                }
                                val (suggestedName, _) = MediaResolver.suggestMediaFileNameAndMime(
                                    currentShareUrl,
                                    title
                                )
                                pendingSaveUrl = currentShareUrl
                                saveAsDocumentLauncher.launch(suggestedName)
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Choose directory / Save as…",
                                color = RwfPalette.AccentDarkTheme,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Pick any folder on your device or SD card",
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { askEveryTime = !askEveryTime }
                    ) {
                        Checkbox(
                            checked = askEveryTime,
                            onCheckedChange = { askEveryTime = it }
                        )
                        Text(
                            text = "Ask download location every time",
                            color = RwfPalette.MetadataTextDark,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadChoiceDialog = false }) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isResolvingAsync) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
            }
        } else {
            when (val active = effectiveMedia) {
                is ResolvedMedia.RedditVideo -> {
                    BackHandler(enabled = isVideoFullscreen) {
                        isVideoFullscreen = false
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        if (!isVideoFullscreen) {
                            RifVideoTopBar(
                                title = title,
                                displayUrl = active.sourceUrl.ifBlank { active.fallbackUrl },
                                isBuffering = isVideoBuffering,
                                menuExpanded = videoMenuExpanded,
                                onMenuExpandedChange = { videoMenuExpanded = it },
                                onBackClick = onDismiss,
                                onToggleFullscreen = { isVideoFullscreen = true },
                                onDownloadClick = {
                                    if (appSettings.askDownloadLocation) {
                                        showDownloadChoiceDialog = true
                                    } else {
                                        triggerDirectDownloadsSave(active.fallbackUrl, title)
                                    }
                                },
                                onRefreshClick = { videoReloadNonce++ },
                                onOpenInBrowserClick = {
                                    MediaResolver.openExternalLink(
                                        context,
                                        active.sourceUrl.ifBlank { active.fallbackUrl }
                                    )
                                },
                                onShareClick = {
                                    val shareTarget = active.sourceUrl.ifBlank { active.fallbackUrl }
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, title)
                                        putExtra(Intent.EXTRA_TEXT, shareTarget)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "Share video link")
                                    )
                                },
                                onCopyUrlClick = {
                                    val copyTarget = active.sourceUrl.ifBlank { active.fallbackUrl }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("URL", copyTarget))
                                    Toast.makeText(context, "Copied URL", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            RedditVideoPlayerBox(
                                videoUrl = active.fallbackUrl,
                                dashUrl = active.dashUrl,
                                hlsUrl = active.hlsUrl,
                                reloadNonce = videoReloadNonce,
                                controlsVisible = showOverlayControls,
                                onControlsVisibilityChange = { showOverlayControls = it },
                                onBufferingChanged = { isVideoBuffering = it },
                                onDismiss = onDismiss
                            )

                            if (isVideoFullscreen && showOverlayControls) {
                                Surface(
                                    color = Color(0x99000000),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(12.dp)
                                ) {
                                    IconButton(onClick = { isVideoFullscreen = false }) {
                                        FullscreenCornersIcon(
                                            isFullscreen = true,
                                            modifier = Modifier.size(20.dp),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is ResolvedMedia.DirectImage,
                is ResolvedMedia.Gallery -> {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isCurrentPageZoomed,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val imageUrl = imageUrls.getOrNull(pageIndex).orEmpty()
                        ZoomableDismissibleImage(
                            imageUrl = imageUrl,
                            contentDescription = title.ifEmpty { "Media item ${pageIndex + 1}" },
                            onToggleOverlay = { showOverlayControls = !showOverlayControls },
                            onZoomChanged = { zoomed ->
                                if (pageIndex == pagerState.currentPage) {
                                    isCurrentPageZoomed = zoomed
                                }
                            },
                            onDismiss = onDismiss
                        )
                    }

                    // Left / Right side album navigation buttons for multi-image galleries
                    if (imageUrls.size > 1 && showOverlayControls) {
                        if (pagerState.currentPage > 0) {
                            Surface(
                                color = Color(0x99000000),
                                shape = CircleShape,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 10.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Previous image",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                        if (pagerState.currentPage < imageUrls.lastIndex) {
                            Surface(
                                color = Color(0x99000000),
                                shape = CircleShape,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 10.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(imageUrls.lastIndex))
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Next image",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                is ResolvedMedia.ExternalWeb -> {
                    RifInAppWebViewBrowser(
                        initialUrl = active.url,
                        initialTitle = active.title,
                        onDismiss = onDismiss
                    )
                }

                is ResolvedMedia.RedgifsVideo,
                is ResolvedMedia.ImgurAlbum,
                ResolvedMedia.SelfPost -> {
                    // Handled by LaunchedEffect above
                }
            }
        }

        // Top Bar Overlay for Image/Gallery modes (ExternalWeb & RedditVideo render dedicated RIF toolbars)
        if (effectiveMedia !is ResolvedMedia.ExternalWeb && effectiveMedia !is ResolvedMedia.RedditVideo) {
            AnimatedVisibility(
                visible = showOverlayControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Top-Left Close Button (X)
                    Surface(
                        color = Color(0x99000000),
                        shape = CircleShape
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close media viewer",
                                tint = Color.White
                            )
                        }
                    }

                    // Compact "< 1 / N >" Counter Pill for Galleries with clickable Prev/Next arrows
                    if (imageUrls.size > 1) {
                        Surface(
                            color = Color(0xDD000000),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                IconButton(
                                    enabled = pagerState.currentPage > 0,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                        }
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Previous album image",
                                        tint = if (pagerState.currentPage > 0) Color.White else Color(0x55FFFFFF)
                                    )
                                }
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${imageUrls.size}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    enabled = pagerState.currentPage < imageUrls.lastIndex,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(imageUrls.lastIndex))
                                        }
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Next album image",
                                        tint = if (pagerState.currentPage < imageUrls.lastIndex) Color.White else Color(0x55FFFFFF)
                                    )
                                }
                            }
                        }
                    }

                    // Top-Right Download & Share Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0x99000000),
                            shape = CircleShape
                        ) {
                            IconButton(
                                onClick = {
                                    if (currentShareUrl.isNotEmpty()) {
                                        if (appSettings.askDownloadLocation) {
                                            showDownloadChoiceDialog = true
                                        } else {
                                            triggerDirectDownloadsSave(currentShareUrl, title)
                                        }
                                    }
                                }
                            ) {
                                DownloadMediaIcon(
                                    modifier = Modifier.size(22.dp),
                                    tint = Color.White
                                )
                            }
                        }

                        Surface(
                            color = Color(0x99000000),
                            shape = CircleShape
                        ) {
                            IconButton(
                                onClick = {
                                    if (currentShareUrl.isNotEmpty()) {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, title)
                                            putExtra(Intent.EXTRA_TEXT, currentShareUrl)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(sendIntent, "Share media link")
                                        )
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share media",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Caption / Title Bar for non-video modes (Video renders caption integrated above controls)
        if (effectiveMedia !is ResolvedMedia.ExternalWeb && effectiveMedia !is ResolvedMedia.RedditVideo && showOverlayControls) {
            val activeCaption = captions.getOrNull(pagerState.currentPage)?.takeIf { it.isNotBlank() }
            val bottomText = activeCaption ?: title.takeIf { it.isNotBlank() }
            if (!bottomText.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0xDD000000))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = bottomText,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * RIF's classic built-in WebView browser with dark toolbar, loading progress bar,
 * back/forward/refresh navigation, and "Open in external browser" overflow action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RifInAppWebViewBrowser(
    initialUrl: String,
    initialTitle: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var pageTitle by remember(initialUrl) { mutableStateOf(initialTitle.ifBlank { "Loading…" }) }
    var loadProgress by remember(initialUrl) { mutableIntStateOf(0) }
    var isLoading by remember(initialUrl) { mutableStateOf(true) }
    var canGoBack by remember(initialUrl) { mutableStateOf(false) }
    var canGoForward by remember(initialUrl) { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
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
                                contentDescription = "Close browser",
                                tint = Color.White
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = pageTitle,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: currentUrl,
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = canGoBack,
                            onClick = { webViewRef?.goBack() }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (canGoBack) Color.White else RwfPalette.DomainTextDark
                            )
                        }
                        IconButton(
                            enabled = canGoForward,
                            onClick = { webViewRef?.goForward() }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (canGoForward) Color.White else RwfPalette.DomainTextDark
                            )
                        }
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
                                    contentDescription = "Browser menu",
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
                                        MediaResolver.openSystemBrowser(context, currentUrl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share link", color = Color.White) },
                                    onClick = {
                                        overflowExpanded = false
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, pageTitle)
                                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy URL", color = Color.White) },
                                    onClick = {
                                        overflowExpanded = false
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                                        Toast.makeText(context, "Copied URL", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                val lowerCurrent = currentUrl.lowercase().substringBefore('?')
                                val isDirectMediaUrl = lowerCurrent.endsWith(".jpg") ||
                                    lowerCurrent.endsWith(".jpeg") ||
                                    lowerCurrent.endsWith(".png") ||
                                    lowerCurrent.endsWith(".gif") ||
                                    lowerCurrent.endsWith(".webp") ||
                                    lowerCurrent.endsWith(".mp4") ||
                                    lowerCurrent.contains("i.redd.it/") ||
                                    lowerCurrent.contains("v.redd.it/") ||
                                    lowerCurrent.contains("i.imgur.com/")
                                if (isDirectMediaUrl) {
                                    DropdownMenuItem(
                                        text = { Text("Download media", color = Color.White) },
                                        onClick = {
                                            overflowExpanded = false
                                            MediaResolver.downloadMedia(context, currentUrl, pageTitle)
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { (loadProgress.coerceIn(5, 100)) / 100f },
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
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                            isLoading = newProgress < 100
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) {
                                pageTitle = title
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (!url.isNullOrBlank()) currentUrl = url
                            isLoading = true
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) currentUrl = url
                            isLoading = false
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = request?.url?.toString().orEmpty()
                            val redditTarget = MediaResolver.parseRedditLink(targetUrl)
                            if (redditTarget != null) {
                                onDismiss()
                                MediaResolver.emitRedditLinkTarget(redditTarget)
                                return true
                            }
                            return false
                        }
                    }
                    loadUrl(initialUrl)
                    webViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
private fun ZoomableDismissibleImage(
    imageUrl: String,
    contentDescription: String,
    onToggleOverlay: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var scale by remember(imageUrl) { mutableFloatStateOf(1f) }
    var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
    var verticalDragDismissOffset by remember(imageUrl) { mutableFloatStateOf(0f) }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.02f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageUrl) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                    onTap = {
                        onToggleOverlay()
                    }
                )
            }
            .pointerInput(imageUrl) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    // 0 = undecided, 1 = vertical dismiss drag, 2 = horizontal pager swipe
                    var axisLock = 0
                    val touchSlop = viewConfiguration.touchSlop

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2 || scale > 1.02f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val hasTransformDelta = pressedCount >= 2 ||
                                abs(zoomChange - 1f) > 0.005f ||
                                abs(panChange.x) > 1f ||
                                abs(panChange.y) > 1f

                            if (hasTransformDelta) {
                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                if (newScale > 1.02f) {
                                    scale = newScale
                                    verticalDragDismissOffset = 0f
                                    val maxPanX = (size.width * (newScale - 1f)) / 2f
                                    val maxPanY = (size.height * (newScale - 1f)) / 2f
                                    offset = Offset(
                                        x = (offset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                                        y = (offset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                                    )
                                } else {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressedCount == 1) {
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null) {
                                val delta = change.positionChange()
                                if (axisLock == 0) {
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    if (hypot(totalDx, totalDy) > touchSlop) {
                                        axisLock = if (abs(totalDy) > abs(totalDx) * 1.25f) 1 else 2
                                        if (axisLock == 1) {
                                            verticalDragDismissOffset += totalDy
                                            change.consume()
                                        }
                                    }
                                } else if (axisLock == 1) {
                                    verticalDragDismissOffset += delta.y
                                    change.consume()
                                }
                                // If axisLock == 2 (horizontal swipe at 1x zoom), do NOT consume!
                                // HorizontalPager will handle the swipe cleanly across album images.
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (axisLock == 1) {
                        if (abs(verticalDragDismissOffset) > 220f) {
                            verticalDragDismissOffset = 0f
                            onDismiss()
                        } else {
                            verticalDragDismissOffset = 0f
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = RwfPalette.AccentDarkTheme,
                        modifier = Modifier.size(36.dp)
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, verticalDragDismissOffset.roundToInt()) }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

@Composable
private fun DownloadMediaIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val strokePx = 2.dp.toPx()
        val cx = size.width / 2f
        // Vertical stem
        drawLine(
            color = tint,
            start = Offset(cx, size.height * 0.14f),
            end = Offset(cx, size.height * 0.66f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
        // Arrow head
        val headPath = Path().apply {
            moveTo(cx - size.width * 0.22f, size.height * 0.46f)
            lineTo(cx, size.height * 0.68f)
            lineTo(cx + size.width * 0.22f, size.height * 0.46f)
        }
        drawPath(
            path = headPath,
            color = tint,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
        // Tray
        val trayPath = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.72f)
            lineTo(size.width * 0.16f, size.height * 0.86f)
            lineTo(size.width * 0.84f, size.height * 0.86f)
            lineTo(size.width * 0.84f, size.height * 0.72f)
        }
        drawPath(
            path = trayPath,
            color = tint,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun HttpsLockIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFFBDBDBD)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.12f).coerceAtLeast(1.3f)
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.12f),
            size = Size(w * 0.44f, h * 0.44f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.28f, h * 0.34f),
            end = Offset(w * 0.28f, h * 0.46f),
            strokeWidth = stroke
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.72f, h * 0.34f),
            end = Offset(w * 0.72f, h * 0.46f),
            strokeWidth = stroke
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.44f),
            size = Size(w * 0.64f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f)
        )
    }
}

@Composable
private fun FullscreenCornersIcon(
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.11f).coerceAtLeast(2f)
        val pad = w * 0.14f
        val arm = w * 0.24f

        fun drawCorner(cornerX: Float, cornerY: Float, dx: Float, dy: Float) {
            val path = Path().apply {
                moveTo(cornerX + dx * arm, cornerY)
                lineTo(cornerX, cornerY)
                lineTo(cornerX, cornerY + dy * arm)
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter)
            )
        }

        if (!isFullscreen) {
            drawCorner(pad, pad, 1f, 1f)
            drawCorner(w - pad, pad, -1f, 1f)
            drawCorner(pad, h - pad, 1f, -1f)
            drawCorner(w - pad, h - pad, -1f, -1f)
        } else {
            val inner = pad + arm
            drawCorner(inner, inner, -1f, -1f)
            drawCorner(w - inner, inner, 1f, -1f)
            drawCorner(inner, h - inner, -1f, 1f)
            drawCorner(w - inner, h - inner, 1f, 1f)
        }
    }
}

@Composable
private fun RifBackArrowIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.10f).coerceAtLeast(2f)
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.22f)
            lineTo(w * 0.20f, h * 0.50f)
            lineTo(w * 0.50f, h * 0.78f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.22f, h * 0.50f),
            end = Offset(w * 0.82f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Square
        )
    }
}

@Composable
private fun RifDownloadIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.10f).coerceAtLeast(2f)
        val arrowPath = Path().apply {
            moveTo(w * 0.50f, h * 0.16f)
            lineTo(w * 0.50f, h * 0.64f)
        }
        drawPath(arrowPath, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Square))
        val headPath = Path().apply {
            moveTo(w * 0.28f, h * 0.44f)
            lineTo(w * 0.50f, h * 0.66f)
            lineTo(w * 0.72f, h * 0.44f)
        }
        drawPath(
            headPath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.20f, h * 0.82f),
            end = Offset(w * 0.80f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Square
        )
    }
}

@Composable
private fun RifRefreshIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.10f).coerceAtLeast(2f)
        val pad = w * 0.18f
        drawArc(
            color = tint,
            startAngle = -35f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(w - pad * 2f, h - pad * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val arrowTip = Offset(w * 0.82f, h * 0.38f)
        val head = Path().apply {
            moveTo(w * 0.62f, h * 0.38f)
            lineTo(arrowTip.x, arrowTip.y)
            lineTo(w * 0.82f, h * 0.18f)
            close()
        }
        drawPath(head, color = tint)
    }
}

@Composable
private fun RifMoreVertIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val cx = size.width * 0.50f
        val r = (size.width * 0.085f).coerceAtLeast(2f)
        drawCircle(color = tint, radius = r, center = Offset(cx, size.height * 0.24f))
        drawCircle(color = tint, radius = r, center = Offset(cx, size.height * 0.50f))
        drawCircle(color = tint, radius = r, center = Offset(cx, size.height * 0.76f))
    }
}

@Composable
private fun PreviousTrackIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.20f),
            size = Size(w * 0.11f, h * 0.60f)
        )
        val triangle = Path().apply {
            moveTo(w * 0.33f, h * 0.50f)
            lineTo(w * 0.80f, h * 0.20f)
            lineTo(w * 0.80f, h * 0.80f)
            close()
        }
        drawPath(triangle, color = tint)
    }
}

@Composable
private fun PlayTriangleIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.14f)
            lineTo(w * 0.84f, h * 0.50f)
            lineTo(w * 0.24f, h * 0.86f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
private fun PauseBarsIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.24f, h * 0.18f),
            size = Size(w * 0.18f, h * 0.64f)
        )
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.58f, h * 0.18f),
            size = Size(w * 0.18f, h * 0.64f)
        )
    }
}

@Composable
private fun RepeatLoopIcon(
    isLooping: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.095f).coerceAtLeast(2f)

        val topLine = Path().apply {
            moveTo(w * 0.20f, h * 0.44f)
            lineTo(w * 0.20f, h * 0.28f)
            lineTo(w * 0.72f, h * 0.28f)
        }
        drawPath(
            topLine,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
        val topHead = Path().apply {
            moveTo(w * 0.68f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.28f)
            lineTo(w * 0.68f, h * 0.42f)
            close()
        }
        drawPath(topHead, color = tint)

        val bottomLine = Path().apply {
            moveTo(w * 0.80f, h * 0.56f)
            lineTo(w * 0.80f, h * 0.72f)
            lineTo(w * 0.28f, h * 0.72f)
        }
        drawPath(
            bottomLine,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
        val bottomHead = Path().apply {
            moveTo(w * 0.32f, h * 0.58f)
            lineTo(w * 0.14f, h * 0.72f)
            lineTo(w * 0.32f, h * 0.86f)
            close()
        }
        drawPath(bottomHead, color = tint)

        if (!isLooping) {
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, h * 0.14f),
                end = Offset(w * 0.86f, h * 0.86f),
                strokeWidth = stroke * 1.15f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SettingsGearIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val ringRadius = w * 0.26f
        val ringStroke = (w * 0.16f).coerceAtLeast(2.2f)
        drawCircle(
            color = tint,
            radius = ringRadius,
            center = Offset(cx, cy),
            style = Stroke(width = ringStroke)
        )
        val toothInner = w * 0.31f
        val toothOuter = w * 0.45f
        val toothStroke = (w * 0.14f).coerceAtLeast(2.2f)
        for (i in 0 until 8) {
            val angle = i * (kotlin.math.PI / 4.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(cx + cosA * toothInner, cy + sinA * toothInner),
                end = Offset(cx + cosA * toothOuter, cy + sinA * toothOuter),
                strokeWidth = toothStroke,
                cap = StrokeCap.Butt
            )
        }
    }
}

@Composable
private fun SpeakerVolumeIcon(
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.085f).coerceAtLeast(1.8f)

        val conePath = Path().apply {
            moveTo(w * 0.14f, h * 0.36f)
            lineTo(w * 0.34f, h * 0.36f)
            lineTo(w * 0.56f, h * 0.18f)
            lineTo(w * 0.56f, h * 0.82f)
            lineTo(w * 0.34f, h * 0.64f)
            lineTo(w * 0.14f, h * 0.64f)
            close()
        }
        drawPath(path = conePath, color = tint)

        if (isMuted) {
            drawLine(
                color = tint,
                start = Offset(w * 0.68f, h * 0.36f),
                end = Offset(w * 0.90f, h * 0.64f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = Offset(w * 0.90f, h * 0.36f),
                end = Offset(w * 0.68f, h * 0.64f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        } else {
            drawArc(
                color = tint,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.46f, h * 0.32f),
                size = Size(w * 0.24f, h * 0.36f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(w * 0.46f, h * 0.18f),
                size = Size(w * 0.40f, h * 0.64f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

private fun formatRifDisplayUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
}

@Composable
private fun RifVideoTopBar(
    title: String,
    displayUrl: String,
    isBuffering: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onDownloadClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onOpenInBrowserClick: () -> Unit,
    onShareClick: () -> Unit,
    onCopyUrlClick: () -> Unit
) {
    val compactUrl = remember(displayUrl) { formatRifDisplayUrl(displayUrl) }
    val isHttps = displayUrl.startsWith("https://", ignoreCase = true)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(RwfPalette.PrimaryDarkTheme)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(44.dp)
            ) {
                RifBackArrowIcon(modifier = Modifier.size(22.dp), tint = Color.White)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = title.ifBlank { "Video" },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (compactUrl.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        if (isHttps) {
                            HttpsLockIcon(
                                modifier = Modifier
                                    .size(11.dp)
                                    .padding(end = 1.dp),
                                tint = Color(0xFFBDBDBD)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = compactUrl,
                            color = Color(0xFFBDBDBD),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.size(40.dp)
            ) {
                FullscreenCornersIcon(
                    isFullscreen = false,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
            }

            IconButton(
                onClick = onDownloadClick,
                modifier = Modifier.size(40.dp)
            ) {
                RifDownloadIcon(modifier = Modifier.size(20.dp), tint = Color.White)
            }

            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier.size(40.dp)
            ) {
                RifRefreshIcon(modifier = Modifier.size(20.dp), tint = Color.White)
            }

            Box {
                IconButton(
                    onClick = { onMenuExpandedChange(true) },
                    modifier = Modifier.size(40.dp)
                ) {
                    RifMoreVertIcon(modifier = Modifier.size(20.dp), tint = Color.White)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Open in browser") },
                        onClick = {
                            onMenuExpandedChange(false)
                            onOpenInBrowserClick()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Share link") },
                        onClick = {
                            onMenuExpandedChange(false)
                            onShareClick()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Copy URL") },
                        onClick = {
                            onMenuExpandedChange(false)
                            onCopyUrlClick()
                        }
                    )
                }
            }
        }

        if (isBuffering) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
                color = RwfPalette.AccentDarkTheme,
                trackColor = Color.Transparent
            )
        }
    }
}

private data class VideoStreamCandidate(
    val uri: String,
    val mimeType: String?
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun RedditVideoPlayerBox(
    videoUrl: String,
    dashUrl: String? = null,
    hlsUrl: String? = null,
    reloadNonce: Int = 0,
    controlsVisible: Boolean,
    onControlsVisibilityChange: (Boolean) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val streamCandidates = remember(videoUrl, dashUrl, hlsUrl) {
        buildList {
            if (!dashUrl.isNullOrBlank()) {
                add(VideoStreamCandidate(uri = dashUrl, mimeType = MimeTypes.APPLICATION_MPD))
            }
            if (!hlsUrl.isNullOrBlank()) {
                add(VideoStreamCandidate(uri = hlsUrl, mimeType = MimeTypes.APPLICATION_M3U8))
            }
            if (videoUrl.isNotBlank()) {
                add(VideoStreamCandidate(uri = videoUrl, mimeType = null))
            }
        }.distinctBy { it.uri }
    }

    var candidateIndex by remember(streamCandidates, reloadNonce) { mutableIntStateOf(0) }
    var retryNonce by remember(streamCandidates, reloadNonce) { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember(streamCandidates, reloadNonce) { mutableStateOf(true) }
    var hasAudioTrack by remember(streamCandidates, reloadNonce) { mutableStateOf(false) }
    var tracksResolved by remember(streamCandidates, reloadNonce) { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    var durationMs by remember(streamCandidates, reloadNonce) { mutableLongStateOf(0L) }
    var currentPositionMs by remember(streamCandidates, reloadNonce) { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPositionMs by remember { mutableFloatStateOf(0f) }

    var videoAspectRatio by remember(streamCandidates, reloadNonce) { mutableFloatStateOf(16f / 9f) }
    var hasVideoSize by remember(streamCandidates, reloadNonce) { mutableStateOf(false) }
    var hasError by remember(streamCandidates, reloadNonce) { mutableStateOf(false) }
    var errorMessage by remember(streamCandidates, reloadNonce) { mutableStateOf<String?>(null) }

    var hasUserInteractedWithControls by remember(videoUrl) { mutableStateOf(false) }
    var controlsInteractionNonce by remember { mutableIntStateOf(0) }

    var scale by remember(videoUrl) { mutableFloatStateOf(1f) }
    var offsetX by remember(videoUrl) { mutableFloatStateOf(0f) }
    var offsetY by remember(videoUrl) { mutableFloatStateOf(0f) }
    var verticalDismissDrag by remember { mutableFloatStateOf(0f) }

    val currentControlsVisible by rememberUpdatedState(controlsVisible)
    val currentOnControlsVisibilityChange by rememberUpdatedState(onControlsVisibilityChange)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentCandidateIndex by rememberUpdatedState(candidateIndex)

    val dataSourceFactory = remember {
        OkHttpDataSource.Factory(RedditNetworkClient.mediaHttpClient as okhttp3.Call.Factory)
            .setDefaultRequestProperties(
                mapOf("User-Agent" to RedditNetworkClient.CUSTOM_USER_AGENT)
            )
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
    }

    DisposableEffect(hostView, isPlaying) {
        hostView.keepScreenOn = isPlaying
        onDispose {
            hostView.keepScreenOn = false
        }
    }

    var wasPlayingBeforePause by remember(exoPlayer) { mutableStateOf(false) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (exoPlayer.isPlaying || exoPlayer.playWhenReady) {
                        wasPlayingBeforePause = true
                        exoPlayer.playWhenReady = false
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    if (wasPlayingBeforePause && !hasError) {
                        wasPlayingBeforePause = false
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            textureViewRef?.let { tv ->
                exoPlayer.clearVideoTextureView(tv)
                tv.tag = null
            }
            textureViewRef = null
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(exoPlayer, isLooping) {
        exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    LaunchedEffect(exoPlayer, playbackSpeed) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    LaunchedEffect(isBuffering) {
        currentOnBufferingChanged(isBuffering)
    }

    DisposableEffect(exoPlayer, streamCandidates) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    val total = exoPlayer.duration
                    if (total != C.TIME_UNSET && total > 0L) {
                        durationMs = total
                    }
                }
                if (playbackState == Player.STATE_ENDED && exoPlayer.repeatMode == Player.REPEAT_MODE_OFF) {
                    if (durationMs > 0L) currentPositionMs = durationMs
                    isPlaying = false
                    currentOnControlsVisibilityChange(true)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isNotEmpty()) {
                    tracksResolved = true
                    hasAudioTrack = tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_AUDIO && group.length > 0
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val dar = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                    if (dar.isFinite() && dar > 0.05f) {
                        videoAspectRatio = dar
                        hasVideoSize = true
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val nextIndex = currentCandidateIndex + 1
                if (nextIndex < streamCandidates.size) {
                    candidateIndex = nextIndex
                } else {
                    hasError = true
                    isBuffering = false
                    errorMessage = error.localizedMessage ?: "Unable to play video"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(exoPlayer, streamCandidates, candidateIndex, reloadNonce, retryNonce) {
        val candidate = streamCandidates.getOrNull(candidateIndex)
        if (candidate == null) {
            hasError = true
            isBuffering = false
            errorMessage = "No playable video stream URL"
            return@LaunchedEffect
        }
        hasError = false
        errorMessage = null
        isBuffering = true
        tracksResolved = false
        hasAudioTrack = false
        val itemBuilder = MediaItem.Builder().setUri(candidate.uri)
        if (!candidate.mimeType.isNullOrBlank()) {
            itemBuilder.setMimeType(candidate.mimeType)
        }
        exoPlayer.setMediaItem(itemBuilder.build())
        exoPlayer.volume = if (isMuted) 0f else 1f
        exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        exoPlayer.setPlaybackSpeed(playbackSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(exoPlayer, isPlaying, hasError) {
        while (!hasError) {
            if (!isSeeking) {
                val pos = exoPlayer.currentPosition
                if (pos >= 0L) currentPositionMs = pos
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET && dur > 0L) durationMs = dur
            }
            kotlinx.coroutines.delay(200L)
        }
    }

    LaunchedEffect(
        controlsVisible,
        isPlaying,
        isSeeking,
        showSpeedMenu,
        hasUserInteractedWithControls,
        controlsInteractionNonce
    ) {
        if (controlsVisible && isPlaying && !isSeeking && !showSpeedMenu && hasUserInteractedWithControls) {
            kotlinx.coroutines.delay(4000L)
            currentOnControlsVisibilityChange(false)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(videoUrl) {
                detectTapGestures(
                    onTap = {
                        hasUserInteractedWithControls = true
                        controlsInteractionNonce++
                        currentOnControlsVisibilityChange(!currentControlsVisible)
                    },
                    onDoubleTap = {
                        hasUserInteractedWithControls = true
                        controlsInteractionNonce++
                        if (scale > 1.1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(videoUrl) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gestureStarted = false
                    var totalVerticalDrag = 0f
                    var isMultiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            isMultiTouch = true
                            gestureStarted = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1.05f) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            verticalDismissDrag = 0f
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed.size == 1 && scale > 1.05f) {
                            val panChange = event.calculatePan()
                            if (gestureStarted || panChange.getDistance() > touchSlop) {
                                gestureStarted = true
                                val maxOffsetX = (size.width * (scale - 1f)) / 2f
                                val maxOffsetY = (size.height * (scale - 1f)) / 2f
                                offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressed.size == 1 && !isMultiTouch) {
                            val panChange = event.calculatePan()
                            totalVerticalDrag += panChange.y
                            if (gestureStarted || kotlin.math.abs(totalVerticalDrag) > touchSlop) {
                                gestureStarted = true
                                verticalDismissDrag = totalVerticalDrag
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!isMultiTouch && scale <= 1.05f && kotlin.math.abs(verticalDismissDrag) > 220f) {
                        currentOnDismiss()
                    }
                    verticalDismissDrag = 0f
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val maxW = maxWidth
        val maxH = maxHeight
        val containerAspect = if (maxH > 0.dp) maxW / maxH else 1f
        val safeVideoAspect = videoAspectRatio.coerceIn(0.2f, 5f)
        val videoSizeModifier = if (safeVideoAspect >= containerAspect) {
            Modifier
                .fillMaxWidth()
                .aspectRatio(safeVideoAspect)
        } else {
            Modifier
                .fillMaxHeight()
                .aspectRatio(safeVideoAspect)
        }

        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    keepScreenOn = true
                    tag = exoPlayer
                    textureViewRef = this
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { textureView ->
                textureViewRef = textureView
                if (textureView.tag !== exoPlayer) {
                    textureView.tag = exoPlayer
                    exoPlayer.setVideoTextureView(textureView)
                }
            },
            modifier = videoSizeModifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY + verticalDismissDrag
            }
        )

        if (!hasVideoSize && isBuffering && !hasError) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }

        if (hasError) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .background(Color(0xCC1C1C1C), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = errorMessage ?: "Unable to play video",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        candidateIndex = 0
                        hasError = false
                        errorMessage = null
                        retryNonce++
                    }
                ) {
                    Text("Retry")
                }
            }
        }

        if (controlsVisible) {
            val displayPosMs = if (isSeeking) sliderPositionMs.toLong() else currentPositionMs
            val maxDurFloat = durationMs.coerceAtLeast(1L).toFloat()
            val clampedSliderValue = displayPosMs.toFloat().coerceIn(0f, maxDurFloat)

            val speakerTint = when {
                tracksResolved && !hasAudioTrack -> Color.White.copy(alpha = 0.30f)
                isMuted -> Color(0xFFFF5252)
                else -> Color.White
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xAA000000),
                                Color(0xEC000000)
                            )
                        )
                    )
                    .pointerInput(Unit) {
                        detectTapGestures {
                            hasUserInteractedWithControls = true
                            controlsInteractionNonce++
                        }
                    }
                    .navigationBarsPadding()
                    .padding(top = 18.dp, bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            hasUserInteractedWithControls = true
                            controlsInteractionNonce++
                            exoPlayer.seekTo(0L)
                            currentPositionMs = 0L
                            if (!exoPlayer.isPlaying) {
                                exoPlayer.playWhenReady = true
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        PreviousTrackIcon(modifier = Modifier.size(26.dp), tint = Color.White)
                    }

                    IconButton(
                        onClick = {
                            hasUserInteractedWithControls = true
                            controlsInteractionNonce++
                            if (isPlaying) {
                                exoPlayer.playWhenReady = false
                                exoPlayer.pause()
                            } else {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0L)
                                }
                                exoPlayer.playWhenReady = true
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        if (isPlaying) {
                            PauseBarsIcon(modifier = Modifier.size(34.dp), tint = Color.White)
                        } else {
                            PlayTriangleIcon(modifier = Modifier.size(34.dp), tint = Color.White)
                        }
                    }

                    IconButton(
                        onClick = {
                            hasUserInteractedWithControls = true
                            controlsInteractionNonce++
                            isLooping = !isLooping
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        RepeatLoopIcon(
                            isLooping = isLooping,
                            modifier = Modifier.size(26.dp),
                            tint = if (isLooping) Color.White else Color(0xFFE0E0E0)
                        )
                    }

                    IconButton(
                        onClick = {
                            hasUserInteractedWithControls = true
                            controlsInteractionNonce++
                            if (tracksResolved && !hasAudioTrack) {
                                Toast.makeText(
                                    context,
                                    "This video has no audio track",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                isMuted = !isMuted
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        SpeakerVolumeIcon(
                            isMuted = isMuted || (tracksResolved && !hasAudioTrack),
                            modifier = Modifier.size(26.dp),
                            tint = speakerTint
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatRifVideoTimestamp(displayPosMs),
                        color = Color(0xFFBEBEBE),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 6.dp)
                    )

                    androidx.compose.material3.Slider(
                        value = clampedSliderValue,
                        onValueChange = { newValue ->
                            hasUserInteractedWithControls = true
                            controlsInteractionNonce++
                            isSeeking = true
                            sliderPositionMs = newValue
                        },
                        onValueChangeFinished = {
                            val targetMs = sliderPositionMs.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
                            exoPlayer.seekTo(targetMs)
                            currentPositionMs = targetMs
                            isSeeking = false
                            controlsInteractionNonce++
                        },
                        valueRange = 0f..maxDurFloat,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x55FFFFFF)
                        )
                    )

                    Text(
                        text = formatRifVideoTimestamp(durationMs),
                        color = Color(0xFFBEBEBE),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp, end = 4.dp)
                    )

                    Box {
                        IconButton(
                            onClick = {
                                hasUserInteractedWithControls = true
                                controlsInteractionNonce++
                                showSpeedMenu = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            SettingsGearIcon(modifier = Modifier.size(20.dp), tint = Color.White)
                        }

                        androidx.compose.material3.DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = {
                                showSpeedMenu = false
                                controlsInteractionNonce++
                            }
                        ) {
                            val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                            speeds.forEach { speedOption ->
                                val label = if (speedOption == 1.0f) "1.0x (Normal)" else "${speedOption}x"
                                val selectedMark = if (playbackSpeed == speedOption) "✓ " else ""
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("$selectedMark$label") },
                                    onClick = {
                                        playbackSpeed = speedOption
                                        showSpeedMenu = false
                                        controlsInteractionNonce++
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRifVideoTimestamp(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Resolves a RedGIFs watch/iframe ID using `api.redgifs.com/v2/auth/temporary` anonymous tokens.
 * Returns `(mp4Url, wasRemoved404)`.
 */
private suspend fun resolveRedgifsMp4(gifId: String): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
    runSuspendCatching {
        val client = RedditNetworkClient.mediaHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        val authRequest = Request.Builder()
            .url("https://api.redgifs.com/v2/auth/temporary")
            .header("Accept", "application/json")
            .get()
            .build()
        val tokenJson = client.newCall(authRequest).execute().use { response ->
            if (!response.isSuccessful) return@runSuspendCatching null to false
            response.body?.string().orEmpty()
        }
        val token = MediaResolver.parseRedgifsTokenJson(tokenJson)
            ?: return@runSuspendCatching null to false

        val gifRequest = Request.Builder()
            .url("https://api.redgifs.com/v2/gifs/$gifId")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(gifRequest).execute().use { response ->
            if (response.code == 404 || response.code == 410) {
                return@runSuspendCatching null to true
            }
            if (!response.isSuccessful) {
                return@runSuspendCatching null to false
            }
            val gifJson = response.body?.string().orEmpty()
            MediaResolver.parseRedgifsMp4UrlJson(gifJson) to false
        }
    }.getOrDefault(null to false)
}

/**
 * Resolves an Imgur album directly via `https://api.imgur.com/3/album/{id}` with RIF's public Client-ID
 * (matching notBrian2/Imgur.java and MojiRS/FixImgurAlbumsPatch).
 */
private suspend fun resolveImgurAlbum(albumId: String): Pair<List<String>, List<String?>> = withContext(Dispatchers.IO) {
    runSuspendCatching {
        val client = RedditNetworkClient.mediaHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://api.imgur.com/3/album/$albumId")
            .header("Authorization", "Client-ID 546c25a59c58ad7")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@runSuspendCatching emptyList<String>() to emptyList()
            }
            val body = response.body?.string().orEmpty()
            MediaResolver.parseImgurAlbumJson(body)
        }
    }.getOrDefault(emptyList<String>() to emptyList())
}

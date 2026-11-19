package com.fwr.app.ui.comments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.RedditComment
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.ui.components.PostCard
import com.fwr.app.ui.components.RifAwardBadges
import com.fwr.app.ui.components.RifCommentReplyDialog
import com.fwr.app.ui.components.RifMarkdownText
import com.fwr.app.ui.settings.RifScaleTweakerDialog
import com.fwr.app.ui.theme.RwfPalette
import com.fwr.app.util.MediaResolver
import com.fwr.app.util.ResolvedMedia
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_VISUAL_INDENT_DEPTH = 10

private data class PendingCommentScroll(
    val nonce: Int,
    val listIndex: Int,
    val targetTopOffsetPx: Int
)

private val COMMENT_SORT_OPTIONS = listOf(
    "Best" to "confidence",
    "Top" to "top",
    "New" to "new",
    "Controversial" to "controversial",
    "Old" to "old",
    "Q&A" to "qa"
)

/**
 * RWF Dark Theme Comment Thread Screen:
 * - Single tap selects a comment (`#232A35`) revealing the top nav bar (`done | hide | ▲ | ▼ | root | parent`)
 *   and bottom 5-button action bar (`Upvote | Downvote | ... | Share | Reply`).
 * - Long press or tapping `hide` collapses the comment into an italic hidden single-line bar.
 * - Supports comment sort toolbar, child-only collapse toggle, Find in Comments, `more` loading, and live API reply/vote/save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    post: RedditPost,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenMediaClick: ((RedditPost) -> Unit)? = null,
    onSelectSubreddit: ((String) -> Unit)? = null,
    onOpenAuthorProfile: ((String) -> Unit)? = null,
    onOpenSettingsClick: (() -> Unit)? = null,
    onPrevPostClick: (() -> Unit)? = null,
    onNextPostClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val authPrefs by prefsRepo.authPreferences.collectAsStateWithLifecycle()
    val appSettings by prefsRepo.appSettings.collectAsStateWithLifecycle()
    val activeUsername = authPrefs.username?.takeIf { it.isNotBlank() } ?: "guest"
    val baseFontSp = when (appSettings.fontSize) {
        "Tiny" -> 10
        "Small" -> 12
        "Medium" -> 13
        "Huge" -> 16
        "Extra Huge" -> 18
        else -> 14
    }
    val scaleOffsetSp = when (appSettings.displayScale) {
        "Compact" -> -1
        "Large" -> 2
        else -> 0
    }
    val rawCommentSp = (baseFontSp + scaleOffsetSp).coerceAtLeast(10)
    val commentTextSizeSp = (rawCommentSp * (appSettings.commentTextScalePercent / 100f))
        .roundToInt()
        .coerceIn(10, 30)
    val rawTitleSp = (rawCommentSp + 2).coerceAtLeast(12)
    val opTitleFontSizeSp = (rawTitleSp * (appSettings.postTitleScalePercent / 100f))
        .roundToInt()
        .coerceIn(12, 34)

    var hasRequestedCommentsLoad by remember(post.id) { mutableStateOf(appSettings.autoLoadComments) }
    var isLoading by remember(post.id) { mutableStateOf(appSettings.autoLoadComments) }
    var isRefreshing by remember(post.id) { mutableStateOf(false) }
    var errorMessage by remember(post.id) { mutableStateOf<String?>(null) }
    var postDetail by remember(post.id) { mutableStateOf(post) }
    var rootComments by remember(post.id) { mutableStateOf<List<RedditComment>>(emptyList()) }
    val collapsedCommentIds = remember(post.id) { mutableStateMapOf<String, Boolean>() }
    var collapseVersion by remember(post.id) { mutableIntStateOf(0) }
    val voteOverrides = remember(post.id) { mutableStateMapOf<String, Int>() }
    val savedOverrides = remember(post.id) { mutableStateMapOf<String, Boolean>() }
    val loadingMoreIds = remember(post.id) { mutableStateMapOf<String, Boolean>() }

    var selectedCommentId by remember(post.id) { mutableStateOf<String?>(null) }
    var currentSortLabel by remember(post.id) {
        mutableStateOf(
            COMMENT_SORT_OPTIONS.firstOrNull {
                appSettings.defaultCommentSort.contains(it.first, ignoreCase = true)
            }?.first ?: "Best"
        )
    }
    var sortDropdownExpanded by remember { mutableStateOf(false) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var childCommentsCollapsed by remember(post.id) { mutableStateOf(false) }

    var selftextCollapsed by remember(post.id) { mutableStateOf(false) }
    var spoilerRevealed by remember(post.id) { mutableStateOf(!post.spoiler) }

    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    // Reply dialog state: Pair(targetFullname, recipientName, parentBodyToQuote)
    var replyTargetFullname by remember { mutableStateOf<String?>(null) }
    var replyRecipientName by remember { mutableStateOf("") }
    var replyParentQuoteBody by remember { mutableStateOf("") }
    var isSendingReply by remember { mutableStateOf(false) }
    var showScopeReauthDialog by remember { mutableStateOf(false) }

    var moreActionsComment by remember { mutableStateOf<RedditComment?>(null) }
    var pendingDeleteComment by remember(post.id) { mutableStateOf<RedditComment?>(null) }
    var pendingDeletePost by remember(post.id) { mutableStateOf(false) }
    var isDeleting by remember(post.id) { mutableStateOf(false) }
    var showOpActionsDialog by remember(post.id) { mutableStateOf(false) }
    var selectCopyTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var isOpSaved by remember(postDetail.id, postDetail.saved) { mutableStateOf(postDetail.saved) }
    var scrollNonce by remember(post.id) { mutableIntStateOf(0) }
    var pendingCommentScroll by remember(post.id) { mutableStateOf<PendingCommentScroll?>(null) }

    fun apiSortForLabel(label: String): String =
        COMMENT_SORT_OPTIONS.firstOrNull { it.first == label }?.second ?: "confidence"

    fun openReplyDialog(targetFullname: String, recipientName: String, parentQuoteBody: String) {
        if (authPrefs.userAccessToken.isNullOrBlank()) {
            Toast.makeText(
                context,
                "Sign in via Manage accounts / BYOK Settings to post comments",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        replyTargetFullname = targetFullname
        replyRecipientName = recipientName
        replyParentQuoteBody = parentQuoteBody
    }

    fun fetchComments(sortOverride: String? = null, isSwipeRefresh: Boolean = false) {
        hasRequestedCommentsLoad = true
        val cleanArticleId = post.id.removePrefix("t3_").trim()
        if (cleanArticleId.isEmpty()) {
            isLoading = false
            isRefreshing = false
            return
        }
        if (isSwipeRefresh && rootComments.isNotEmpty()) {
            isRefreshing = true
        } else {
            isLoading = true
        }
        errorMessage = null
        val apiSort = sortOverride ?: apiSortForLabel(currentSortLabel)
        coroutineScope.launch {
            runSuspendCatching {
                RedditNetworkClient.apiService.getPostComments(
                    articleId = cleanArticleId,
                    sort = apiSort
                )
            }.onSuccess { listings ->
                val fetchedPost = listings.getOrNull(0)
                    ?.data
                    ?.children
                    ?.firstOrNull()
                    ?.data as? RedditPost
                if (fetchedPost != null) {
                    if (!postDetail.spoiler && fetchedPost.spoiler && rootComments.isEmpty()) {
                        spoilerRevealed = false
                    }
                    postDetail = fetchedPost
                }

                val commentsListing = listings.getOrNull(1)
                val parsedComments = commentsListing
                    ?.data
                    ?.children
                    ?.mapNotNull { thing ->
                        val c = thing.data as? RedditComment ?: return@mapNotNull null
                        when {
                            thing.kind == "t1" && c.body.isNotBlank() -> c
                            thing.kind == "more" && c.moreChildrenIds.isNotEmpty() -> c
                            else -> null
                        }
                    }
                    .orEmpty()

                rootComments = parsedComments
                if (childCommentsCollapsed) {
                    var collapsedAny = false
                    parsedComments.forEach { top ->
                        top.replies?.data?.children?.forEach { child ->
                            if (child.kind == "t1") {
                                collapsedCommentIds[child.data.id] = true
                                collapsedAny = true
                            }
                        }
                    }
                    if (collapsedAny) {
                        collapseVersion++
                    }
                }
                isLoading = false
                isRefreshing = false
            }.onFailure { throwable ->
                val errMsg = throwable.localizedMessage ?: "Failed to load comments"
                if (isSwipeRefresh && rootComments.isNotEmpty()) {
                    Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                } else {
                    errorMessage = errMsg
                }
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(post.id) {
        prefsRepo.markPostClicked(post.id.ifEmpty { post.permalink })
        if (appSettings.autoLoadComments) {
            hasRequestedCommentsLoad = true
            fetchComments()
        } else {
            isLoading = false
        }
    }

    LaunchedEffect(authPrefs.userAccessToken, authPrefs.username) {
        val token = authPrefs.userAccessToken
        if (!token.isNullOrBlank() && authPrefs.username.isNullOrBlank()) {
            RedditNetworkClient.getOAuthManager(context).fetchAndStoreMeIdentity(token)
        }
    }

    LaunchedEffect(pendingCommentScroll) {
        val req = pendingCommentScroll ?: return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0) {
            val safeIndex = req.listIndex.coerceIn(0, totalItems - 1)
            listState.scrollToItem(
                index = safeIndex,
                scrollOffset = -req.targetTopOffsetPx
            )
        }
    }

    val visibleNodes = remember(rootComments, collapseVersion) {
        flattenVisibleComments(
            comments = rootComments,
            collapsedIds = collapsedCommentIds
        )
    }

    val resolvedPostMedia = remember(
        postDetail.id,
        postDetail.url,
        postDetail.url_overridden_by_dest,
        postDetail.is_gallery,
        postDetail.media_metadata,
        postDetail.gallery_data,
        postDetail.preview,
        postDetail.media,
        postDetail.crosspost_parent_list
    ) {
        MediaResolver.resolve(postDetail)
    }
    val showInlinePostMedia = resolvedPostMedia is ResolvedMedia.DirectImage ||
        resolvedPostMedia is ResolvedMedia.Gallery ||
        resolvedPostMedia is ResolvedMedia.ImgurAlbum ||
        resolvedPostMedia is ResolvedMedia.RedditVideo ||
        resolvedPostMedia is ResolvedMedia.RedgifsVideo

    // Offset in LazyColumn before the first comment node
    val commentStartIndex = remember(showInlinePostMedia, postDetail.selftext) {
        // Item 0: OP PostCard; optional media preview; optional selftext; Comment Actions Toolbar
        2 + (if (showInlinePostMedia) 1 else 0) + (if (postDetail.selftext.isNotBlank()) 1 else 0)
    }

    val searchMatchingIndices = remember(visibleNodes, findQuery) {
        if (findQuery.isBlank()) {
            emptyList()
        } else {
            visibleNodes.mapIndexedNotNull { idx, node ->
                if (!node.isMorePlaceholder &&
                    (node.comment.body.contains(findQuery, ignoreCase = true) ||
                        node.comment.author.contains(findQuery, ignoreCase = true))
                ) {
                    idx
                } else {
                    null
                }
            }
        }
    }

    fun jumpToNodeIndex(nodeIndex: Int, anchorNodeIndex: Int? = null) {
        val node = visibleNodes.getOrNull(nodeIndex) ?: return
        val desiredTopOffsetPx = if (anchorNodeIndex != null) {
            val anchorItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == commentStartIndex + anchorNodeIndex }
            val viewportHeight = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset)
                .coerceAtLeast(0)
            computePreservedScrollOffset(
                anchorTopOffsetPx = anchorItem?.offset,
                viewportHeightPx = viewportHeight
            )
        } else {
            0
        }
        selectedCommentId = node.comment.id
        scrollNonce++
        pendingCommentScroll = PendingCommentScroll(
            nonce = scrollNonce,
            listIndex = commentStartIndex + nodeIndex,
            targetTopOffsetPx = desiredTopOffsetPx
        )
    }

    fun stepPrevComment() {
        if (visibleNodes.isEmpty()) return
        val selectedIdx = selectedCommentId?.let { selId ->
            visibleNodes.indexOfFirst { it.comment.id == selId }
        } ?: -1
        val target = if (selectedIdx >= 0) {
            findPrevCommentIndex(visibleNodes, selectedIdx)
                ?: visibleNodes.take(selectedIdx).indexOfLast { it.depth == 0 && !it.isMorePlaceholder }.takeIf { it >= 0 }
        } else {
            val approx = (listState.firstVisibleItemIndex - commentStartIndex).coerceIn(0, visibleNodes.size)
            visibleNodes.take(approx).indexOfLast { it.depth == 0 && !it.isMorePlaceholder }.takeIf { it >= 0 }
                ?: visibleNodes.indexOfFirst { it.depth == 0 && !it.isMorePlaceholder }.takeIf { it >= 0 }
        }
        target?.let { jumpToNodeIndex(it, anchorNodeIndex = selectedIdx.takeIf { idx -> idx >= 0 }) }
    }

    fun stepNextComment() {
        if (visibleNodes.isEmpty()) return
        val selectedIdx = selectedCommentId?.let { selId ->
            visibleNodes.indexOfFirst { it.comment.id == selId }
        } ?: -1
        val target = if (selectedIdx >= 0) {
            findNextCommentIndex(visibleNodes, selectedIdx)
        } else if (listState.firstVisibleItemIndex < commentStartIndex) {
            visibleNodes.indexOfFirst { !it.isMorePlaceholder }.takeIf { it >= 0 }
        } else {
            val approx = (listState.firstVisibleItemIndex - commentStartIndex).coerceIn(0, visibleNodes.lastIndex)
            ((approx + 1) until visibleNodes.size).firstOrNull {
                visibleNodes[it].depth == 0 && !visibleNodes[it].isMorePlaceholder
            }
        }
        target?.let { jumpToNodeIndex(it, anchorNodeIndex = selectedIdx.takeIf { idx -> idx >= 0 }) }
    }

    fun loadMoreStub(node: FlattenedCommentNode) {
        val idsToLoad = node.comment.moreChildrenIds.take(50)
        val stubKey = node.stableKey
        if (idsToLoad.isEmpty() || loadingMoreIds[stubKey] == true) return
        loadingMoreIds[stubKey] = true
        val articleIdClean = postDetail.id.removePrefix("t3_")
        val articleFullname = "t3_$articleIdClean"
        val currentSortApi = apiSortForLabel(currentSortLabel)
        val existingIdsSnapshot = collectCommentIds(rootComments)
        coroutineScope.launch {
            val wantedSet = node.comment.moreChildrenIds.toSet()
            runSuspendCatching {
                // Load missing subtree via /comments/... when /api/morechildren is blocked
                val parentId = node.parentCommentId
                val listings = if (parentId.isNullOrBlank()) {
                    RedditNetworkClient.apiService.getPostComments(
                        articleId = articleIdClean,
                        sort = currentSortApi,
                        limit = 500,
                        depth = 2
                    )
                } else {
                    RedditNetworkClient.apiService.getPostSubtreeComments(
                        articleId = articleIdClean,
                        commentId = parentId.removePrefix("t1_"),
                        sort = currentSortApi,
                        limit = 500,
                        depth = 10
                    )
                }
                val childrenThings = listings.getOrNull(1)?.data?.children.orEmpty()
                val candidateComments = if (parentId.isNullOrBlank()) {
                    childrenThings.mapNotNull { it.data as? RedditComment }
                } else {
                    val focalParent = childrenThings.firstOrNull()?.data as? RedditComment
                    focalParent?.replies?.data?.children?.map { it.data }.orEmpty()
                }
                val matching = candidateComments.filter {
                    it.id in wantedSet && (it.body.isNotBlank() || it.moreChildrenIds.isNotEmpty())
                }
                val subtreeComments = if (matching.isNotEmpty()) {
                    matching
                } else {
                    candidateComments.filter {
                        it.body.isNotBlank() &&
                            it.id != node.comment.id &&
                            it.id !in existingIdsSnapshot
                    }.take(25)
                }
                if (subtreeComments.isNotEmpty()) {
                    subtreeComments
                } else {
                    val resp = RedditNetworkClient.apiService.getMoreChildren(
                        linkId = articleFullname,
                        childrenCsv = idsToLoad.joinToString(",")
                    )
                    resp.json.data.things.mapNotNull { thing ->
                        thing.data.takeIf { it.body.isNotBlank() || it.moreChildrenIds.isNotEmpty() }
                    }
                }
            }.onSuccess { newComments ->
                if (newComments.isEmpty()) {
                    Toast.makeText(
                        context,
                        "No additional comments available in this thread window",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    rootComments = replaceMoreStubInTree(
                        tree = rootComments,
                        stubId = node.comment.id,
                        loadedComments = newComments,
                        parentCommentId = node.parentCommentId,
                        stubFirstChildId = node.comment.moreChildrenIds.firstOrNull(),
                        requireRootParent = (node.parentCommentId == null)
                    )
                }
                loadingMoreIds[stubKey] = false
            }.onFailure { err ->
                loadingMoreIds[stubKey] = false
                Toast.makeText(
                    context,
                    "Failed to load more comments: ${err.localizedMessage ?: "Error"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // RIF Comment Reply Dialog
    if (replyTargetFullname != null) {
        RifCommentReplyDialog(
            recipientName = replyRecipientName,
            activeUsername = activeUsername,
            parentTextToQuote = replyParentQuoteBody,
            isSending = isSendingReply,
            onDismiss = {
                replyTargetFullname = null
            },
            onSend = { bodyText ->
                if (authPrefs.userAccessToken.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        "Sign in via Manage accounts / BYOK Settings to post comments",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@RifCommentReplyDialog
                }
                val target = replyTargetFullname ?: return@RifCommentReplyDialog
                isSendingReply = true
                coroutineScope.launch {
                    runSuspendCatching {
                        val responseBody = RedditNetworkClient.apiService.postComment(
                            parentFullname = target,
                            markdownText = bodyText
                        ).use { it.string() }
                        RedditOAuthManager.extractRedditApiError(responseBody)?.let { apiErr ->
                            throw IllegalStateException(apiErr)
                        }
                        responseBody
                    }.onSuccess { responseBody ->
                        isSendingReply = false
                        replyTargetFullname = null
                        Toast.makeText(context, "Comment submitted!", Toast.LENGTH_SHORT).show()
                        val posted = parsePostedCommentFromResponse(
                            responseBody = responseBody,
                            fallbackAuthor = activeUsername,
                            fallbackBody = bodyText
                        )
                        if (posted != null) {
                            rootComments = insertPostedCommentInTree(rootComments, target, posted)
                            if (target.startsWith("t1_")) {
                                val parentId = target.removePrefix("t1_")
                                if (collapsedCommentIds.remove(parentId) != null) {
                                    collapseVersion++
                                }
                            }
                            postDetail = postDetail.copy(num_comments = postDetail.num_comments + 1)
                            selectedCommentId = posted.id
                        } else {
                            fetchComments()
                        }
                    }.onFailure { err ->
                        isSendingReply = false
                        if (err.message?.contains("403") == true) {
                            showScopeReauthDialog = true
                        }
                        Toast.makeText(
                            context,
                            "Failed to post comment: ${err.localizedMessage ?: "Error"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    if (showScopeReauthDialog) {
        AlertDialog(
            onDismissRequest = { showScopeReauthDialog = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Reddit Permission Update Needed") },
            text = {
                Text(
                    text = "Your current Reddit login was authorized before comment & post permissions (submit, edit, privatemessages, subscribe) were enabled. Tap Re-authorize to grant permission in your browser — your typed reply will stay open.",
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

    // RIF Comment "..." More Actions Dialog
    if (moreActionsComment != null) {
        val targetComment = moreActionsComment!!
        val isSaved = savedOverrides[targetComment.id] ?: targetComment.saved
        val isOwnComment = !authPrefs.userAccessToken.isNullOrBlank() &&
            !authPrefs.username.isNullOrBlank() &&
            targetComment.author.equals(authPrefs.username, ignoreCase = true)
        Dialog(onDismissRequest = { moreActionsComment = null }) {
            Surface(
                color = RwfPalette.CardBackgroundDark,
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    CommentDialogRow(
                        text = "${targetComment.author}'s profile",
                        onClick = {
                            moreActionsComment = null
                            if (onOpenAuthorProfile != null) {
                                onOpenAuthorProfile(targetComment.author)
                            } else {
                                MediaResolver.openExternalLink(
                                    context,
                                    "https://www.reddit.com/user/${targetComment.author}"
                                )
                            }
                        }
                    )
                    CommentDialogRow(
                        text = if (isSaved) "Unsave comment" else "Save comment",
                        onClick = {
                            moreActionsComment = null
                            val nextSaved = !isSaved
                            savedOverrides[targetComment.id] = nextSaved
                            coroutineScope.launch {
                                runSuspendCatching {
                                    if (nextSaved) {
                                        RedditNetworkClient.apiService.save(targetComment.resolvedFullname)
                                    } else {
                                        RedditNetworkClient.apiService.unsave(targetComment.resolvedFullname)
                                    }
                                }
                            }
                            Toast.makeText(
                                context,
                                if (nextSaved) "Saved comment" else "Unsaved comment",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    CommentDialogRow(
                        text = "Copy comment text",
                        onClick = {
                            moreActionsComment = null
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Comment", targetComment.body))
                            Toast.makeText(context, "Copied comment text", Toast.LENGTH_SHORT).show()
                        }
                    )
                    CommentDialogRow(
                        text = "Select / copy text",
                        onClick = {
                            moreActionsComment = null
                            selectCopyTarget = "Comment by u/${targetComment.author}" to targetComment.body
                        }
                    )
                    CommentDialogRow(
                        text = "Open permalink in browser",
                        onClick = {
                            moreActionsComment = null
                            val url = buildCommentPermalinkUrl(postDetail, targetComment)
                            MediaResolver.openSystemBrowser(context, url)
                        }
                    )
                    CommentDialogRow(
                        text = "Report comment",
                        onClick = {
                            moreActionsComment = null
                            if (authPrefs.userAccessToken.isNullOrBlank()) {
                                Toast.makeText(
                                    context,
                                    "Sign in via Settings to report comments",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                coroutineScope.launch {
                                    runSuspendCatching {
                                        RedditNetworkClient.apiService.reportThing(
                                            thingId = targetComment.resolvedFullname,
                                            reason = "other"
                                        ).close()
                                    }.onSuccess {
                                        Toast.makeText(
                                            context,
                                            "Reported comment to Reddit",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            "Report failed: ${err.localizedMessage ?: "Error"}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                    if (isOwnComment) {
                        CommentDialogRow(
                            text = "Delete comment",
                            onClick = {
                                moreActionsComment = null
                                pendingDeleteComment = targetComment
                            }
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteComment != null) {
        val commentToDelete = pendingDeleteComment!!
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pendingDeleteComment = null },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Delete comment?") },
            text = {
                Text(
                    text = "Delete this comment permanently? This cannot be undone.",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.deleteThing(commentToDelete.resolvedFullname)
                            }.onSuccess {
                                isDeleting = false
                                pendingDeleteComment = null
                                val (updatedTree, removed) = deleteCommentInTree(
                                    tree = rootComments,
                                    commentId = commentToDelete.id
                                )
                                rootComments = updatedTree
                                if (removed) {
                                    postDetail = postDetail.copy(
                                        num_comments = (postDetail.num_comments - 1).coerceAtLeast(0)
                                    )
                                }
                                if (selectedCommentId == commentToDelete.id) {
                                    selectedCommentId = null
                                }
                                Toast.makeText(context, "Comment deleted", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                isDeleting = false
                                if (err.message?.contains("403") == true) {
                                    showScopeReauthDialog = true
                                }
                                Toast.makeText(
                                    context,
                                    "Failed to delete comment: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isDeleting) "Deleting…" else "Delete",
                        color = RwfPalette.VoteArrowUpvoteRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { pendingDeleteComment = null }
                ) {
                    Text("Cancel", color = RwfPalette.DomainTextDark)
                }
            }
        )
    }

    // RIF OP Post Actions Dialog (shown when tapping the OP post header at the top of CommentsScreen)
    if (showOpActionsDialog) {
        val isOwnPost = !authPrefs.userAccessToken.isNullOrBlank() &&
            !authPrefs.username.isNullOrBlank() &&
            postDetail.author.equals(authPrefs.username, ignoreCase = true)
        val permalinkUrl = if (postDetail.permalink.startsWith("http")) {
            postDetail.permalink
        } else {
            "https://www.reddit.com${postDetail.permalink}"
        }
        val browserUrl = if (!postDetail.is_self && postDetail.url.startsWith("http")) {
            postDetail.url
        } else {
            permalinkUrl
        }
        Dialog(onDismissRequest = { showOpActionsDialog = false }) {
            Surface(
                color = RwfPalette.CardBackgroundDark,
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    CommentDialogRow(
                        text = "Reply",
                        onClick = {
                            showOpActionsDialog = false
                            openReplyDialog(
                                targetFullname = postDetail.resolvedFullname,
                                recipientName = "${postDetail.author} (OP)",
                                parentQuoteBody = postDetail.selftext.ifBlank { postDetail.title }
                            )
                        }
                    )
                    CommentDialogRow(
                        text = "Share/Copy",
                        onClick = {
                            showOpActionsDialog = false
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, postDetail.title)
                                putExtra(Intent.EXTRA_TEXT, "$permalinkUrl\n${postDetail.title}")
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share post"))
                        }
                    )
                    CommentDialogRow(
                        text = "Select / copy post text",
                        onClick = {
                            showOpActionsDialog = false
                            val combinedText = listOfNotNull(
                                postDetail.title.takeIf { it.isNotBlank() },
                                postDetail.selftext.takeIf { it.isNotBlank() }
                            ).joinToString("\n\n")
                            selectCopyTarget = "Post by u/${postDetail.author}" to combinedText
                        }
                    )
                    CommentDialogRow(
                        text = if (isOpSaved) "Unsave" else "Save",
                        onClick = {
                            showOpActionsDialog = false
                            val nextSaved = !isOpSaved
                            isOpSaved = nextSaved
                            coroutineScope.launch {
                                runSuspendCatching {
                                    if (nextSaved) {
                                        RedditNetworkClient.apiService.save(postDetail.resolvedFullname)
                                    } else {
                                        RedditNetworkClient.apiService.unsave(postDetail.resolvedFullname)
                                    }
                                }
                            }
                            Toast.makeText(
                                context,
                                if (nextSaved) "Saved post" else "Unsaved post",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    CommentDialogRow(
                        text = "${postDetail.author}'s profile",
                        onClick = {
                            showOpActionsDialog = false
                            if (onOpenAuthorProfile != null) {
                                onOpenAuthorProfile(postDetail.author)
                            } else {
                                MediaResolver.openExternalLink(
                                    context,
                                    "https://www.reddit.com/user/${postDetail.author}"
                                )
                            }
                        }
                    )
                    CommentDialogRow(
                        text = "Open link in browser",
                        onClick = {
                            showOpActionsDialog = false
                            MediaResolver.openSystemBrowser(context, browserUrl)
                        }
                    )
                    CommentDialogRow(
                        text = "Report post",
                        onClick = {
                            showOpActionsDialog = false
                            if (authPrefs.userAccessToken.isNullOrBlank()) {
                                Toast.makeText(
                                    context,
                                    "Sign in via Settings to report posts",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                coroutineScope.launch {
                                    runSuspendCatching {
                                        RedditNetworkClient.apiService.reportThing(
                                            thingId = postDetail.resolvedFullname,
                                            reason = "other"
                                        ).close()
                                    }.onSuccess {
                                        Toast.makeText(
                                            context,
                                            "Reported post to Reddit",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            "Report failed: ${err.localizedMessage ?: "Error"}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                    if (isOwnPost) {
                        CommentDialogRow(
                            text = "Delete post",
                            onClick = {
                                showOpActionsDialog = false
                                pendingDeletePost = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (pendingDeletePost) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pendingDeletePost = false },
            containerColor = RwfPalette.CardBackgroundDark,
            titleContentColor = RwfPalette.TitleUnreadDark,
            title = { Text("Delete post?") },
            text = {
                Text(
                    text = "Delete this post permanently? This cannot be undone.",
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        coroutineScope.launch {
                            runSuspendCatching {
                                RedditNetworkClient.apiService.deleteThing(postDetail.resolvedFullname)
                            }.onSuccess {
                                isDeleting = false
                                pendingDeletePost = false
                                Toast.makeText(context, "Post deleted", Toast.LENGTH_SHORT).show()
                                onBackClick()
                            }.onFailure { err ->
                                isDeleting = false
                                if (err.message?.contains("403") == true) {
                                    showScopeReauthDialog = true
                                }
                                Toast.makeText(
                                    context,
                                    "Failed to delete post: ${err.localizedMessage ?: "Error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isDeleting) "Deleting…" else "Delete",
                        color = RwfPalette.VoteArrowUpvoteRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { pendingDeletePost = false }
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

    selectCopyTarget?.let { (dialogTitle, dialogText) ->
        RifTextSelectionDialog(
            title = dialogTitle,
            text = dialogText,
            onDismiss = { selectCopyTarget = null }
        )
    }

    if (showScaleDialog) {
        RifScaleTweakerDialog(
            preferencesRepository = prefsRepo,
            onDismiss = { showScaleDialog = false }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            Surface(
                color = RwfPalette.PrimaryDarkTheme,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(50.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to feed",
                            tint = topBarContentColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                val fadePx = 28.dp.toPx()
                                if (size.width > fadePx) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startX = size.width - fadePx,
                                            endX = size.width
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = postDetail.title.ifBlank { "/r/${postDetail.subreddit}" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = topBarContentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }

                    // Reply to OP button
                    IconButton(
                        onClick = {
                            openReplyDialog(
                                targetFullname = postDetail.resolvedFullname,
                                recipientName = "${postDetail.author} (OP)",
                                parentQuoteBody = postDetail.selftext.ifBlank { postDetail.title }
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Reply to post",
                            tint = topBarContentColor
                        )
                    }
                    IconButton(onClick = { fetchComments() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh comments",
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
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false },
                            modifier = Modifier
                                .width(220.dp)
                                .background(RwfPalette.CardBackgroundDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("View /r/${postDetail.subreddit}", color = RwfPalette.TitleUnreadDark, fontSize = 15.sp) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    onSelectSubreddit?.invoke(postDetail.subreddit)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Find comment", color = RwfPalette.TitleUnreadDark, fontSize = 15.sp) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    showFindBar = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Open comments in browser", color = RwfPalette.TitleUnreadDark, fontSize = 15.sp) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    val url = if (postDetail.permalink.startsWith("http")) {
                                        postDetail.permalink
                                    } else {
                                        "https://www.reddit.com${postDetail.permalink}"
                                    }
                                    MediaResolver.openSystemBrowser(context, url)
                                }
                            )
                            if (!authPrefs.username.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text("${authPrefs.username}'s profile", color = RwfPalette.TitleUnreadDark, fontSize = 15.sp) },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onOpenAuthorProfile?.invoke(authPrefs.username!!)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Scale settings", color = RwfPalette.TitleUnreadDark, fontSize = 15.sp) },
                                onClick = {
                                    overflowMenuExpanded = false
                                    showScaleDialog = true
                                }
                            )
                            if (onOpenSettingsClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Settings", color = RwfPalette.TitleUnreadDark, fontSize = 15.sp) },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onOpenSettingsClick()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (showFindBar || appSettings.showCommentNavBar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RwfPalette.CardBackgroundDark)
                        .navigationBarsPadding()
                ) {
                    // Find Comment Bar
                    AnimatedVisibility(visible = showFindBar) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(RwfPalette.CardBackgroundDark)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedTextField(
                                value = findQuery,
                                onValueChange = {
                                    findQuery = it
                                    currentMatchIndex = 0
                                },
                                placeholder = { Text("Find in comments…", fontSize = 13.sp, color = RwfPalette.DomainTextDark) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RwfPalette.AccentDarkTheme,
                                    unfocusedBorderColor = RwfPalette.DividerLineDark,
                                    focusedTextColor = RwfPalette.TitleUnreadDark,
                                    unfocusedTextColor = RwfPalette.TitleUnreadDark
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (searchMatchingIndices.isNotEmpty()) {
                                Text(
                                    text = "${(currentMatchIndex % searchMatchingIndices.size) + 1}/${searchMatchingIndices.size}",
                                    color = RwfPalette.AccentDarkTheme,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (searchMatchingIndices.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex - 1 + searchMatchingIndices.size) % searchMatchingIndices.size
                                        jumpToNodeIndex(searchMatchingIndices[currentMatchIndex])
                                    }
                                }
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", tint = RwfPalette.TitleUnreadDark)
                            }
                            IconButton(
                                onClick = {
                                    if (searchMatchingIndices.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex + 1) % searchMatchingIndices.size
                                        jumpToNodeIndex(searchMatchingIndices[currentMatchIndex])
                                    }
                                }
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", tint = RwfPalette.TitleUnreadDark)
                            }
                            IconButton(
                                onClick = {
                                    showFindBar = false
                                    findQuery = ""
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close find bar", tint = RwfPalette.TitleUnreadDark)
                            }
                        }
                    }

                    if (appSettings.showCommentNavBar) {
                        HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)

                        // Docked prev/next post and comment navigation strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CommentNavTextButton(
                                label = "◄ prev post",
                                enabled = onPrevPostClick != null,
                                onClick = { onPrevPostClick?.invoke() }
                            )
                            CommentNavTextButton(
                                label = "▲ prev",
                                enabled = visibleNodes.isNotEmpty(),
                                onClick = { stepPrevComment() }
                            )
                            CommentNavTextButton(
                                label = "▼ next",
                                enabled = visibleNodes.isNotEmpty(),
                                onClick = { stepNextComment() }
                            )
                            CommentNavTextButton(
                                label = "next post ►",
                                enabled = onNextPostClick != null,
                                onClick = { onNextPostClick?.invoke() }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { fetchComments(isSwipeRefresh = true) },
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
            // 1. Original Post Header Card
            item(key = "op_header_${postDetail.id}", contentType = "op_header") {
                PostCard(
                    post = postDetail,
                    isRead = true,
                    isExpanded = false,
                    isOpHeaderInComments = true,
                    onToggleExpand = { showOpActionsDialog = true },
                    onPostClick = { showOpActionsDialog = true },
                    onThumbnailClick = {
                        if (onOpenMediaClick != null) {
                            onOpenMediaClick(postDetail)
                        } else if (postDetail.url.startsWith("http")) {
                            MediaResolver.openExternalLink(context, postDetail.url)
                        }
                    },
                    onSelectSubreddit = onSelectSubreddit,
                    onOpenAuthorProfile = onOpenAuthorProfile,
                    amoledBlackBackground = appSettings.amoledBlackBackground,
                    leftHandedMode = appSettings.leftHandedMode,
                    showLinkFlair = appSettings.showLinkFlair,
                    titleFontSizeSp = opTitleFontSizeSp,
                    labelNsfw = appSettings.labelNsfw,
                    showInlineImages = appSettings.showInlineImages
                )
            }

            // 1b. Inline OP Media Preview Card (images, galleries, videos)
            if (showInlinePostMedia) {
                item(key = "op_media_${postDetail.id}", contentType = "op_media") {
                    RifPostMediaPreviewCard(
                        post = postDetail,
                        resolvedMedia = resolvedPostMedia,
                        spoilerRevealed = spoilerRevealed,
                        onRevealSpoiler = { spoilerRevealed = true },
                        onOpenFullMedia = {
                            if (onOpenMediaClick != null) {
                                onOpenMediaClick(postDetail)
                            } else if (postDetail.url.startsWith("http")) {
                                MediaResolver.openExternalLink(context, postDetail.url)
                            }
                        }
                    )
                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                }
            }

            // 2. OP Collapsible Selftext Box with Spoiler Overlay
            if (postDetail.selftext.isNotBlank()) {
                item(key = "op_selftext_${postDetail.id}", contentType = "op_selftext") {
                    if (!spoilerRevealed) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
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
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .background(RwfPalette.CardBackgroundDark, shape = RoundedCornerShape(2.dp))
                                .border(
                                    width = 1.dp,
                                    color = RwfPalette.DividerLineDark,
                                    shape = RoundedCornerShape(2.dp)
                                )
                                .padding(10.dp)
                        ) {
                            if (selftextCollapsed) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selftextCollapsed = false },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "(selftext collapsed — tap to expand)",
                                        color = RwfPalette.MetadataTextDark,
                                        fontSize = 12.sp,
                                        fontStyle = FontStyle.Italic
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand selftext",
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
                                            contentDescription = "Collapse selftext",
                                            tint = RwfPalette.MetadataTextDark,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable { selftextCollapsed = true }
                                        )
                                    }
                                    RifMarkdownText(
                                        text = postDetail.selftext,
                                        color = RwfPalette.TitleUnreadDark,
                                        fontSize = commentTextSizeSp.sp,
                                        lineHeight = (commentTextSizeSp + 5).sp,
                                        mediaMetadata = postDetail.media_metadata,
                                        selectable = true,
                                        showInlineImages = appSettings.showInlineImages,
                                        onSubredditClick = onSelectSubreddit,
                                        onUserClick = onOpenAuthorProfile,
                                        onNonLinkClick = null
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
                }
            }

            // 3. Thread Comment Actions Bar
            item(key = "comment_actions_toolbar", contentType = "comment_actions_toolbar") {
                val currentSortApi = apiSortForLabel(currentSortLabel)
                val isSuggested = !postDetail.suggested_sort.isNullOrBlank() &&
                    postDetail.suggested_sort.equals(currentSortApi, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RwfPalette.CardBackgroundDark)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box {
                        Column(
                            modifier = Modifier.clickable { sortDropdownExpanded = true },
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = "${postDetail.num_comments} comments",
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = buildString {
                                    append("sorted by ${currentSortLabel.lowercase()}")
                                    if (isSuggested) append(" (suggested)")
                                },
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 12.sp
                            )
                        }

                        DropdownMenu(
                            expanded = sortDropdownExpanded,
                            onDismissRequest = { sortDropdownExpanded = false },
                            modifier = Modifier.background(RwfPalette.CardBackgroundDark)
                        ) {
                            COMMENT_SORT_OPTIONS.forEach { (label, apiValue) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (label == currentSortLabel) RwfPalette.AccentDarkTheme else RwfPalette.TitleUnreadDark,
                                            fontSize = 15.sp,
                                            fontWeight = if (label == currentSortLabel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        sortDropdownExpanded = false
                                        currentSortLabel = label
                                        hasRequestedCommentsLoad = true
                                        fetchComments(sortOverride = apiValue)
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { actionsMenuExpanded = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Comment actions",
                                tint = RwfPalette.MetadataTextDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = actionsMenuExpanded,
                            onDismissRequest = { actionsMenuExpanded = false },
                            modifier = Modifier.background(RwfPalette.CardBackgroundDark)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Sort comments (${currentSortLabel.lowercase()})",
                                        color = RwfPalette.TitleUnreadDark,
                                        fontSize = 15.sp
                                    )
                                },
                                onClick = {
                                    actionsMenuExpanded = false
                                    sortDropdownExpanded = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Find comment",
                                        color = RwfPalette.TitleUnreadDark,
                                        fontSize = 15.sp
                                    )
                                },
                                onClick = {
                                    actionsMenuExpanded = false
                                    showFindBar = !showFindBar
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (childCommentsCollapsed) "Expand all child comments" else "Collapse all child comments",
                                        color = RwfPalette.TitleUnreadDark,
                                        fontSize = 15.sp
                                    )
                                },
                                onClick = {
                                    actionsMenuExpanded = false
                                    val nextState = !childCommentsCollapsed
                                    childCommentsCollapsed = nextState
                                    rootComments.forEach { top ->
                                        top.replies?.data?.children?.forEach { child ->
                                            if (child.kind == "t1") {
                                                collapsedCommentIds[child.data.id] = nextState
                                            }
                                        }
                                    }
                                    collapseVersion++
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
            }

            // 4. Loading / Error / Comments Tree
            if (isLoading) {
                item(key = "comments_loading", contentType = "comments_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = RwfPalette.AccentDarkTheme)
                    }
                }
            } else if (errorMessage != null) {
                item(key = "comments_error", contentType = "comments_error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "Error loading comments",
                            color = RwfPalette.VoteArrowUpvoteRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { fetchComments() }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (!hasRequestedCommentsLoad && visibleNodes.isEmpty()) {
                item(key = "comments_manual_load", contentType = "comments_manual_load") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Auto-load comments is disabled in Data usage settings.",
                            color = RwfPalette.DomainTextDark,
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                hasRequestedCommentsLoad = true
                                fetchComments()
                            }
                        ) {
                            Text("Load comments")
                        }
                    }
                }
            } else if (visibleNodes.isEmpty()) {
                item(key = "comments_empty", contentType = "comments_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
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
                itemsIndexed(
                    items = visibleNodes,
                    key = { _, node -> node.stableKey },
                    contentType = { _, node ->
                        when {
                            node.isMorePlaceholder -> "more"
                            collapsedCommentIds[node.comment.id] == true -> "hidden"
                            else -> "comment"
                        }
                    }
                ) { idx, node ->
                    if (node.isMorePlaceholder) {
                        RifMoreCommentsRow(
                            node = node,
                            isLoading = loadingMoreIds[node.stableKey] == true,
                            showGuideLines = appSettings.showCommentGuideLines,
                            backgroundColor = backgroundColor,
                            onClick = { loadMoreStub(node) }
                        )
                    } else {
                        val comment = node.comment
                        val isCollapsed = collapsedCommentIds[comment.id] == true
                        val isSelected = selectedCommentId == comment.id
                        val initialVote = when (comment.likes) {
                            true -> 1
                            false -> -1
                            null -> 0
                        }
                        val voteDirection = voteOverrides[comment.id] ?: initialVote
                        val isHighlightMatch = findQuery.isNotBlank() &&
                            (comment.body.contains(findQuery, ignoreCase = true) ||
                                comment.author.contains(findQuery, ignoreCase = true))

                        if (isCollapsed) {
                            RifHiddenCommentRow(
                                comment = comment,
                                depth = node.depth,
                                childCount = node.childCount,
                                showGuideLines = appSettings.showCommentGuideLines,
                                backgroundColor = backgroundColor,
                                onExpand = {
                                    collapsedCommentIds.remove(comment.id)
                                    collapseVersion++
                                }
                            )
                        } else {
                            RifCommentRow(
                                comment = comment,
                                depth = node.depth,
                                isSelected = isSelected,
                                isSearchMatch = isHighlightMatch,
                                voteDirection = voteDirection,
                                showGuideLines = appSettings.showCommentGuideLines,
                                showInlineImages = appSettings.showInlineImages,
                                baseBackgroundColor = backgroundColor,
                                commentTextSizeSp = commentTextSizeSp,
                                onSelectToggle = {
                                    selectedCommentId = if (isSelected) null else comment.id
                                },
                                onLongPressCollapse = {
                                    collapsedCommentIds[comment.id] = true
                                    collapseVersion++
                                    if (isSelected) selectedCommentId = null
                                },
                                onDone = { selectedCommentId = null },
                                onHide = {
                                    collapsedCommentIds[comment.id] = true
                                    collapseVersion++
                                    selectedCommentId = null
                                },
                                onPrev = {
                                    findPrevCommentIndex(visibleNodes, idx)?.let {
                                        jumpToNodeIndex(it, anchorNodeIndex = idx)
                                    }
                                },
                                onNext = {
                                    findNextCommentIndex(visibleNodes, idx)?.let {
                                        jumpToNodeIndex(it, anchorNodeIndex = idx)
                                    }
                                },
                                onRoot = {
                                    val rootIdx = visibleNodes.indexOfFirst { it.comment.id == node.rootCommentId }
                                    if (rootIdx >= 0) jumpToNodeIndex(rootIdx, anchorNodeIndex = idx)
                                },
                                onParent = {
                                    val parentId = node.parentCommentId
                                    if (parentId != null) {
                                        val parentIdx = visibleNodes.indexOfFirst { it.comment.id == parentId }
                                        if (parentIdx >= 0) jumpToNodeIndex(parentIdx, anchorNodeIndex = idx)
                                    }
                                },
                                onUpvote = {
                                    val nextDir = if (voteDirection == 1) 0 else 1
                                    voteOverrides[comment.id] = nextDir
                                    coroutineScope.launch {
                                        runSuspendCatching {
                                            RedditNetworkClient.apiService.vote(comment.resolvedFullname, nextDir)
                                        }
                                    }
                                },
                                onDownvote = {
                                    val nextDir = if (voteDirection == -1) 0 else -1
                                    voteOverrides[comment.id] = nextDir
                                    coroutineScope.launch {
                                        runSuspendCatching {
                                            RedditNetworkClient.apiService.vote(comment.resolvedFullname, nextDir)
                                        }
                                    }
                                },
                                onMoreActions = { moreActionsComment = comment },
                                onSharePermalink = {
                                    val url = buildCommentPermalinkUrl(postDetail, comment)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Comment by ${comment.author}")
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share comment"))
                                },
                                onReply = {
                                    openReplyDialog(
                                        targetFullname = comment.resolvedFullname,
                                        recipientName = comment.author,
                                        parentQuoteBody = comment.body
                                    )
                                },
                                onSelectSubreddit = onSelectSubreddit,
                                onOpenAuthorProfile = onOpenAuthorProfile
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private fun commentIndentWidth(depth: Int) =
    if (depth <= 0) 12.dp else (6 + 10 * depth.coerceAtMost(MAX_VISUAL_INDENT_DEPTH)).dp

private fun Modifier.commentIndentGuides(
    depth: Int,
    showGuideLines: Boolean,
    guideColor: Color
): Modifier {
    val visualDepth = depth.coerceAtMost(MAX_VISUAL_INDENT_DEPTH)
    if (!showGuideLines || visualDepth <= 0) return this
    return drawBehind {
        val lineStrokePx = 1.dp.toPx()
        for (i in 0 until visualDepth) {
            val x = (6 + 10 * i + 5.5f).dp.toPx()
            drawLine(
                color = guideColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = lineStrokePx
            )
        }
    }
}

/**
 * Normal expanded comment row:
 * - Tap selects (`#232A35`) & shows `#comment_nav` + `#comment_actions`.
 * - Long-press or `hide` collapses into `RifHiddenCommentRow`.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RifCommentRow(
    comment: RedditComment,
    depth: Int,
    isSelected: Boolean,
    isSearchMatch: Boolean,
    voteDirection: Int,
    showGuideLines: Boolean,
    showInlineImages: Boolean,
    baseBackgroundColor: Color,
    commentTextSizeSp: Int = 14,
    onSelectToggle: () -> Unit,
    onLongPressCollapse: () -> Unit,
    onDone: () -> Unit,
    onHide: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRoot: () -> Unit,
    onParent: () -> Unit,
    onUpvote: () -> Unit,
    onDownvote: () -> Unit,
    onMoreActions: () -> Unit,
    onSharePermalink: () -> Unit,
    onReply: () -> Unit,
    onSelectSubreddit: ((String) -> Unit)?,
    onOpenAuthorProfile: ((String) -> Unit)?
) {
    val targetBg = when {
        isSelected -> RwfPalette.SelectedPostRowDark
        isSearchMatch -> if (RwfPalette.isDark) Color(0xFF332D1B) else Color(0xFFFFF3CD)
        else -> baseBackgroundColor
    }
    val rowBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 120),
        label = "commentSelectionBg"
    )
    val guideColor = RwfPalette.FlairBackgroundDark
    val indentWidth = commentIndentWidth(depth)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
    ) {
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .commentIndentGuides(depth = depth, showGuideLines = showGuideLines, guideColor = guideColor)
                .padding(end = 12.dp),
            content = {
                Box(
                    modifier = Modifier.combinedClickable(
                        onClick = onLongPressCollapse,
                        onLongClick = onLongPressCollapse
                    )
                )
                Column(
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                // Top comment nav bar when selected (#comment_nav)
                if (isSelected) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CommentNavTextButton("done", onDone)
                        CommentNavTextButton("hide", onHide)
                        CommentNavTextButton("▲ prev", onPrev)
                        CommentNavTextButton("▼ next", onNext)
                        if (depth > 0) {
                            CommentNavTextButton("root", onRoot)
                            CommentNavTextButton("parent", onParent)
                        }
                    }
                }

                // Comment header row — tap to select, long-press or tap [-] to collapse
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = onSelectToggle,
                            onLongClick = onLongPressCollapse
                        )
                        .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (voteDirection != 0) {
                            Canvas(
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .size(width = 10.dp, height = 7.dp)
                            ) {
                                val path = Path().apply {
                                    if (voteDirection == 1) {
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
                                drawPath(
                                    path = path,
                                    color = if (voteDirection == 1) {
                                        RwfPalette.VoteArrowUpvoteRed
                                    } else {
                                        RwfPalette.VoteArrowDownvoteBlue
                                    }
                                )
                            }
                        }

                        val authorColor = when {
                            comment.distinguished == "moderator" -> RwfPalette.DistinguishedModGreen
                            comment.distinguished == "admin" -> RwfPalette.DistinguishedAdminRed
                            comment.is_submitter -> RwfPalette.CyanLinkDark
                            else -> Color(0xFF888888)
                        }

                        Text(
                            text = comment.author,
                            color = authorColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (comment.is_submitter) {
                            Text("[S]", color = RwfPalette.CyanLinkDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else if (comment.distinguished == "moderator") {
                            Text("[M]", color = RwfPalette.DistinguishedModGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else if (comment.distinguished == "admin") {
                            Text("[A]", color = RwfPalette.DistinguishedAdminRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        val initialVote = when (comment.likes) {
                            true -> 1
                            false -> -1
                            null -> 0
                        }
                        val displayedScore = comment.score + (voteDirection - initialVote)
                        val scoreText = if (comment.score_hidden) {
                            "[score hidden]"
                        } else {
                            if (displayedScore == 1) "1 point" else "$displayedScore points"
                        }
                        Text(
                            text = scoreText,
                            color = when (voteDirection) {
                                1 -> RwfPalette.VoteArrowUpvoteRed
                                -1 -> RwfPalette.VoteArrowDownvoteBlue
                                else -> RwfPalette.MetadataTextDark
                            },
                            fontSize = 12.sp
                        )

                        val relTime = formatCommentTime(comment.created_utc)
                        if (relTime.isNotEmpty()) {
                            Text(
                                text = relTime,
                                color = RwfPalette.DomainTextDark,
                                fontSize = 12.sp
                            )
                        }

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

                        if (depth > MAX_VISUAL_INDENT_DEPTH) {
                            Text(
                                text = "↳ depth $depth",
                                color = RwfPalette.MetadataTextDark,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        text = "[–]",
                        color = RwfPalette.MetadataTextDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(onClick = onLongPressCollapse)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Comment body with selectable rich Reddit Markdown
                RifMarkdownText(
                    text = comment.body.trim(),
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = commentTextSizeSp.sp,
                    lineHeight = (commentTextSizeSp + 4).sp,
                    mediaMetadata = comment.media_metadata,
                    selectable = true,
                    showInlineImages = showInlineImages,
                    onSubredditClick = onSelectSubreddit,
                    onUserClick = onOpenAuthorProfile,
                    onNonLinkClick = if (!isSelected) onSelectToggle else null,
                    onLongClick = null
                )

                // Bottom 5-button RIF comment action strip when selected (#comment_actions)
                if (isSelected) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(
                            onClick = onUpvote,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Upvote",
                                tint = if (voteDirection == 1) RwfPalette.VoteArrowUpvoteRed else RwfPalette.ActionStripTextDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = onDownvote,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Downvote",
                                tint = if (voteDirection == -1) RwfPalette.VoteArrowDownvoteBlue else RwfPalette.ActionStripTextDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = onMoreActions,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More actions",
                                tint = RwfPalette.ActionStripTextDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onSharePermalink,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Permalink",
                                tint = RwfPalette.ActionStripTextDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onReply,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Reply",
                                tint = RwfPalette.ActionStripTextDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        ) { measurables, constraints ->
            val indentPx = indentWidth.roundToPx().coerceAtMost(constraints.maxWidth)
            val contentMaxWidth = (constraints.maxWidth - indentPx).coerceAtLeast(0)
            val contentPlaceable = measurables[1].measure(
                constraints.copy(minWidth = 0, maxWidth = contentMaxWidth)
            )
            val gutterPlaceable = measurables[0].measure(
                Constraints.fixed(indentPx, contentPlaceable.height)
            )
            val layoutWidth = if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                indentPx + contentPlaceable.width
            }
            layout(layoutWidth, contentPlaceable.height) {
                gutterPlaceable.placeRelative(0, 0)
                contentPlaceable.placeRelative(indentPx, 0)
            }
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = RwfPalette.DividerLineDark
        )
    }
}

/**
 * Collapsed comment single-line italic row.
 */
@Composable
private fun RifHiddenCommentRow(
    comment: RedditComment,
    depth: Int,
    childCount: Int,
    showGuideLines: Boolean,
    backgroundColor: Color,
    onExpand: () -> Unit
) {
    val guideColor = RwfPalette.FlairBackgroundDark
    val indentWidth = commentIndentWidth(depth)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onExpand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .commentIndentGuides(depth = depth, showGuideLines = showGuideLines, guideColor = guideColor)
                .padding(start = indentWidth, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = comment.author,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic
            )
            Text(
                text = if (comment.score_hidden) "[score hidden]" else "${comment.score} points",
                color = RwfPalette.DomainTextDark,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic
            )
            val rel = formatCommentTime(comment.created_utc)
            if (rel.isNotEmpty()) {
                Text(
                    text = rel,
                    color = RwfPalette.DomainTextDark,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic
                )
            }
            if (depth > MAX_VISUAL_INDENT_DEPTH) {
                Text(
                    text = "↳ depth $depth",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 11.sp
                )
            }
            Text(
                text = if (childCount > 0) "($childCount children)" else "(comment hidden)",
                color = RwfPalette.AccentDarkTheme,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
    }
}

/**
 * `"load more comments (N replies)"` row.
 */
@Composable
private fun RifMoreCommentsRow(
    node: FlattenedCommentNode,
    isLoading: Boolean,
    showGuideLines: Boolean,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    val count = node.comment.count ?: node.comment.moreChildrenIds.size
    val guideColor = RwfPalette.FlairBackgroundDark
    val indentWidth = commentIndentWidth(node.depth)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(enabled = !isLoading, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .commentIndentGuides(depth = node.depth, showGuideLines = showGuideLines, guideColor = guideColor)
                .padding(start = indentWidth, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "load more comments ($count ${if (count == 1) "reply" else "replies"})",
                color = RwfPalette.CyanLinkDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (node.depth > MAX_VISUAL_INDENT_DEPTH) {
                Text(
                    text = "↳ depth ${node.depth}",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 11.sp
                )
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = RwfPalette.AccentDarkTheme
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = RwfPalette.DividerLineDark)
    }
}

@Composable
private fun RifPostMediaPreviewCard(
    post: RedditPost,
    resolvedMedia: ResolvedMedia,
    spoilerRevealed: Boolean,
    onRevealSpoiler: () -> Unit,
    onOpenFullMedia: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (!spoilerRevealed) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .background(RwfPalette.DarkGraySurface, RoundedCornerShape(4.dp))
                .clickable(onClick = onRevealSpoiler),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Tap to reveal spoiler media",
                color = RwfPalette.TitleUnreadDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .background(RwfPalette.CardBackgroundDark, RoundedCornerShape(4.dp))
            .border(1.dp, RwfPalette.DividerLineDark, RoundedCornerShape(4.dp))
    ) {
        when (resolvedMedia) {
            is ResolvedMedia.Gallery -> {
                val galleryUrls = resolvedMedia.imageUrls
                if (galleryUrls.isNotEmpty()) {
                    val pagerState = rememberPagerState(pageCount = { galleryUrls.size })
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 380.dp)
                            .background(Color.Black)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val pageUrl = galleryUrls[page]
                            val request = remember(pageUrl) {
                                ImageRequest.Builder(context)
                                    .data(pageUrl)
                                    .addHeader("User-Agent", RedditNetworkClient.CUSTOM_USER_AGENT)
                                    .crossfade(true)
                                    .build()
                            }
                            SubcomposeAsyncImage(
                                model = request,
                                contentDescription = "Gallery image ${page + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(onClick = onOpenFullMedia),
                                loading = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = RwfPalette.AccentDarkTheme, strokeWidth = 2.5.dp)
                                    }
                                },
                                error = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Tap to view image", color = RwfPalette.CyanLinkDark, fontSize = 13.sp)
                                    }
                                }
                            )
                        }

                        if (galleryUrls.size > 1) {
                            Surface(
                                color = Color(0xB3000000),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${galleryUrls.size}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    val caption = resolvedMedia.captions.getOrNull(pagerState.currentPage)
                    if (!caption.isNullOrBlank()) {
                        Text(
                            text = caption,
                            color = RwfPalette.TitleUnreadDark,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (galleryUrls.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CommentNavTextButton(
                                    label = "◄ Prev",
                                    enabled = pagerState.currentPage > 0,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                        }
                                    }
                                )
                                CommentNavTextButton(
                                    label = "Next ►",
                                    enabled = pagerState.currentPage < galleryUrls.lastIndex,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(galleryUrls.lastIndex))
                                        }
                                    }
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }
                        Text(
                            text = "Open fullscreen",
                            color = RwfPalette.CyanLinkDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(onClick = onOpenFullMedia)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenFullMedia)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reddit Gallery — Tap to view all images",
                            color = RwfPalette.CyanLinkDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            is ResolvedMedia.DirectImage -> {
                val request = remember(resolvedMedia.url) {
                    ImageRequest.Builder(context)
                        .data(resolvedMedia.url)
                        .addHeader("User-Agent", RedditNetworkClient.CUSTOM_USER_AGENT)
                        .crossfade(true)
                        .build()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 380.dp)
                        .background(Color.Black)
                        .clickable(onClick = onOpenFullMedia),
                    contentAlignment = Alignment.Center
                ) {
                    SubcomposeAsyncImage(
                        model = request,
                        contentDescription = post.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Box(Modifier.height(180.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = RwfPalette.AccentDarkTheme, strokeWidth = 2.5.dp)
                            }
                        },
                        error = {
                            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("Tap to view image", color = RwfPalette.CyanLinkDark, fontSize = 13.sp)
                            }
                        }
                    )
                }
            }

            is ResolvedMedia.RedditVideo,
            is ResolvedMedia.RedgifsVideo,
            is ResolvedMedia.ImgurAlbum -> {
                val posterUrl = post.preview?.images?.firstOrNull()?.source?.url?.replace("&amp;", "&")
                    ?: post.thumbnail?.takeIf { it.startsWith("http") }
                val badgeLabel = when (resolvedMedia) {
                    is ResolvedMedia.ImgurAlbum -> "ALBUM • Tap to view all images"
                    else -> "VIDEO • Tap to play with sound"
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color(0xFF0E1116))
                        .clickable(onClick = onOpenFullMedia),
                    contentAlignment = Alignment.Center
                ) {
                    if (!posterUrl.isNullOrBlank()) {
                        val posterReq = remember(posterUrl) {
                            ImageRequest.Builder(context)
                                .data(posterUrl)
                                .addHeader("User-Agent", RedditNetworkClient.CUSTOM_USER_AGENT)
                                .crossfade(true)
                                .build()
                        }
                        SubcomposeAsyncImage(
                            model = posterReq,
                            contentDescription = post.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (resolvedMedia !is ResolvedMedia.ImgurAlbum) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color(0xAA000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(26.dp)) {
                                val path = Path().apply {
                                    moveTo(size.width * 0.25f, size.height * 0.15f)
                                    lineTo(size.width * 0.85f, size.height * 0.5f)
                                    lineTo(size.width * 0.25f, size.height * 0.85f)
                                    close()
                                }
                                drawPath(path = path, color = Color.White)
                            }
                        }
                    }
                    Surface(
                        color = Color(0xBF000000),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = badgeLabel,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun RifTextSelectionDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = RwfPalette.CardBackgroundDark,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, RwfPalette.DividerLineDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    color = RwfPalette.TitleUnreadDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Long-press and drag to highlight specific text, or copy all.",
                    color = RwfPalette.MetadataTextDark,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .background(RwfPalette.BackgroundMaterialDark, RoundedCornerShape(4.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = text,
                        color = RwfPalette.TitleUnreadDark,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText(title, text))
                            Toast.makeText(context, "Copied all text", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    ) {
                        Text("Copy All", color = RwfPalette.AccentDarkTheme, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = RwfPalette.DomainTextDark)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentNavTextButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        color = if (enabled) Color(0xFF283240) else Color(0xFF1D222B),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) RwfPalette.DividerLineDark else Color(0xFF282C34)
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (enabled) RwfPalette.ActionStripTextDark else RwfPalette.MetadataTextDark.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CommentDialogRow(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(text = text, color = RwfPalette.TitleUnreadDark, fontSize = 15.sp)
    }
}

private fun buildCommentPermalinkUrl(post: RedditPost, comment: RedditComment): String {
    if (!comment.permalink.isNullOrBlank()) {
        return if (comment.permalink.startsWith("http")) {
            comment.permalink
        } else {
            "https://www.reddit.com${comment.permalink}"
        }
    }
    val postPermalink = if (post.permalink.startsWith("http")) {
        post.permalink
    } else {
        "https://www.reddit.com${post.permalink}"
    }
    return if (comment.id.isNotBlank()) "${postPermalink.trimEnd('/')}/${comment.id}" else postPermalink
}

private fun formatCommentTime(createdUtcSeconds: Double): String {
    if (createdUtcSeconds <= 0.0) return ""
    val nowSeconds = System.currentTimeMillis() / 1000L
    val diffSeconds = max(0L, nowSeconds - createdUtcSeconds.toLong())
    return when {
        diffSeconds < 60L -> "$diffSeconds seconds ago"
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

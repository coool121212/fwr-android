package com.fwr.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fwr.app.data.api.RedditApiService
import com.fwr.app.data.model.RedditListing
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RateLimitException
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.UserPreferencesRepository
import com.fwr.app.util.runSuspendCatching
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import retrofit2.Response

data class FeedCacheKey(
    val subreddit: String,
    val sort: String,
    val timeFilter: String,
    val searchQuery: String?,
    val searchRestrictToSubreddit: Boolean = true,
    val searchIncludeNsfw: Boolean = false,
    val searchSort: String = "relevance"
)

data class CachedFeedEntry(
    val posts: List<RedditPost>,
    val after: String?,
    val timestampMs: Long,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

data class FeedScrollTarget(
    val version: Int = 0,
    val index: Int = 0,
    val offset: Int = 0
)

/**
 * ViewModel managing subreddit feed loading, frontpage support, time filters for Top/Controversial,
 * server-side search, Saved tab loading, in-memory feed/scroll caching, and infinite pagination.
 */
class FeedViewModel @JvmOverloads constructor(
    private val apiService: RedditApiService = RedditNetworkClient.apiService,
    private val usernameProvider: () -> String? = {
        UserPreferencesRepository.peekInstance()?.getSnapshot()?.username
    },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val autoLoad: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _subreddit = MutableStateFlow(DEFAULT_SUBREDDIT)
    val subreddit: StateFlow<String> = _subreddit.asStateFlow()

    private val _sort = MutableStateFlow(DEFAULT_SORT)
    val sort: StateFlow<String> = _sort.asStateFlow()

    private val _timeFilter = MutableStateFlow(DEFAULT_TIME_FILTER)
    val timeFilter: StateFlow<String> = _timeFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    private val _searchRestrictToSubreddit = MutableStateFlow(true)
    val searchRestrictToSubreddit: StateFlow<Boolean> = _searchRestrictToSubreddit.asStateFlow()

    private val _searchIncludeNsfw = MutableStateFlow(false)
    val searchIncludeNsfw: StateFlow<Boolean> = _searchIncludeNsfw.asStateFlow()

    private val _searchSort = MutableStateFlow("relevance")
    val searchSort: StateFlow<String> = _searchSort.asStateFlow()

    private val _scrollTarget = MutableStateFlow(FeedScrollTarget())
    val scrollTarget: StateFlow<FeedScrollTarget> = _scrollTarget.asStateFlow()

    private val feedCache = object : LinkedHashMap<FeedCacheKey, CachedFeedEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FeedCacheKey, CachedFeedEntry>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }
    private val navigationBackStack = ArrayDeque<FeedCacheKey>()
    private var currentScrollIndex = 0
    private var currentScrollOffset = 0

    private var fetchJob: Job? = null
    private var nextPageJob: Job? = null
    private var isFetchingNextPage = false

    init {
        if (autoLoad) {
            loadInitialFeed()
        }
    }

    private fun currentCacheKey(): FeedCacheKey {
        val activeQuery = _searchQuery.value?.trim()?.takeIf { it.isNotEmpty() }
        return FeedCacheKey(
            subreddit = _subreddit.value.trim().lowercase(),
            sort = _sort.value.trim().lowercase(),
            timeFilter = _timeFilter.value.trim().lowercase(),
            searchQuery = activeQuery,
            searchRestrictToSubreddit = if (activeQuery != null) _searchRestrictToSubreddit.value else true,
            searchIncludeNsfw = if (activeQuery != null) _searchIncludeNsfw.value else false,
            searchSort = if (activeQuery != null) _searchSort.value.trim().lowercase() else "relevance"
        )
    }

    private fun isCacheEntryFresh(entry: CachedFeedEntry): Boolean {
        return (clockMs() - entry.timestampMs) <= CACHE_TTL_MS
    }

    private fun emitScrollTarget(index: Int, offset: Int) {
        currentScrollIndex = index.coerceAtLeast(0)
        currentScrollOffset = offset.coerceAtLeast(0)
        _scrollTarget.value = FeedScrollTarget(
            version = _scrollTarget.value.version + 1,
            index = currentScrollIndex,
            offset = currentScrollOffset
        )
    }

    private fun pushBackStack(previousKey: FeedCacheKey) {
        // Persist latest scroll position into cache before leaving this feed
        feedCache[previousKey]?.let { existing ->
            feedCache[previousKey] = existing.copy(
                firstVisibleItemIndex = currentScrollIndex,
                firstVisibleItemScrollOffset = currentScrollOffset
            )
        }
        if (navigationBackStack.lastOrNull() != previousKey) {
            navigationBackStack.addLast(previousKey)
            while (navigationBackStack.size > MAX_BACK_STACK) {
                navigationBackStack.removeFirst()
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = offset.coerceAtLeast(0)
        currentScrollIndex = safeIndex
        currentScrollOffset = safeOffset
        val key = currentCacheKey()
        feedCache[key]?.let { existing ->
            feedCache[key] = existing.copy(
                firstVisibleItemIndex = safeIndex,
                firstVisibleItemScrollOffset = safeOffset
            )
        }
    }

    fun canNavigateBackInFeed(): Boolean = navigationBackStack.isNotEmpty()

    /**
     * Pops the previous feed from the in-memory navigation stack, restoring cached posts and
     * exact scroll offset without forcing a network reload when cache is still fresh.
     */
    fun navigateBackInFeed(): Boolean {
        if (navigationBackStack.isEmpty()) return false
        val previousKey = navigationBackStack.removeLast()
        fetchJob?.cancel()
        nextPageJob?.cancel()
        nextPageJob = null
        isFetchingNextPage = false

        _subreddit.value = previousKey.subreddit
        _sort.value = previousKey.sort
        _timeFilter.value = previousKey.timeFilter
        _searchQuery.value = previousKey.searchQuery
        _searchRestrictToSubreddit.value = previousKey.searchRestrictToSubreddit
        _searchIncludeNsfw.value = previousKey.searchIncludeNsfw
        _searchSort.value = previousKey.searchSort

        val cached = feedCache[previousKey]
        if (cached != null && isCacheEntryFresh(cached)) {
            _uiState.value = FeedUiState.Success(
                posts = cached.posts,
                after = cached.after,
                isLoadingMore = false,
                isRefreshing = false,
                paginationError = null
            )
            val safeIndex = if (cached.posts.isEmpty()) 0 else cached.firstVisibleItemIndex.coerceIn(0, cached.posts.lastIndex)
            val safeOffset = if (safeIndex == cached.firstVisibleItemIndex) cached.firstVisibleItemScrollOffset else 0
            emitScrollTarget(safeIndex, safeOffset)
        } else {
            emitScrollTarget(cached?.firstVisibleItemIndex ?: 0, cached?.firstVisibleItemScrollOffset ?: 0)
            loadInitialFeed(forceRefresh = true)
        }
        return true
    }

    private data class FeedFetchResult(
        val posts: List<RedditPost>,
        val after: String?,
        val requestedUrl: String,
        val responseCode: Int
    )

    private fun <T> Response<T>.requireBodyOrThrow(): T {
        if (!isSuccessful) {
            throw HttpException(this)
        }
        return body() ?: throw HttpException(this)
    }

    private suspend fun executeFeedRequest(after: String?): FeedFetchResult {
        val activeSearch = _searchQuery.value
        val sub = _subreddit.value
        val sortOrder = _sort.value
        val tFilter = _timeFilter.value.takeIf { sortOrder == "top" || sortOrder == "controversial" }

        if (!activeSearch.isNullOrBlank()) {
            val includeNsfw = _searchIncludeNsfw.value
            val nsfwParam = if (includeNsfw) "on" else "false"
            val restrictToSub = _searchRestrictToSubreddit.value &&
                !sub.equals("frontpage", ignoreCase = true) &&
                !sub.equals("all", ignoreCase = true) &&
                !sub.equals("popular", ignoreCase = true)

            suspend fun fetchSearchPage(cursor: String?): Response<RedditListing<RedditPost>> {
                return if (restrictToSub) {
                    apiService.searchSubredditPosts(
                        sub = sub,
                        query = activeSearch,
                        restrictSr = "on",
                        sort = _searchSort.value,
                        timeFilter = _timeFilter.value,
                        includeOver18 = nsfwParam,
                        after = cursor
                    )
                } else {
                    apiService.searchAllPosts(
                        query = activeSearch,
                        sort = _searchSort.value,
                        timeFilter = _timeFilter.value,
                        includeOver18 = nsfwParam,
                        after = cursor
                    )
                }
            }

            var cursor = after
            val collected = mutableListOf<RedditPost>()
            var lastAfter: String? = null
            var lastUrl = ""
            var lastCode = 200
            var pageAttempts = 0
            while (pageAttempts < 3) {
                pageAttempts++
                val response = fetchSearchPage(cursor)
                val listing = response.requireBodyOrThrow()
                lastUrl = response.raw().request.url.toString()
                lastCode = response.code()
                lastAfter = listing.data.after?.takeIf { it.isNotBlank() }
                val rawPosts = listing.data.children.map { it.data }
                val filteredPosts = if (includeNsfw) rawPosts else rawPosts.filterNot { it.over_18 }
                collected.addAll(filteredPosts)
                if (includeNsfw || collected.isNotEmpty() || lastAfter == null) {
                    break
                }
                cursor = lastAfter
            }
            return FeedFetchResult(
                posts = collected.distinctBy { it.id.ifEmpty { it.permalink } },
                after = lastAfter,
                requestedUrl = lastUrl,
                responseCode = lastCode
            )
        }

        if (sortOrder == "saved") {
            val username = usernameProvider()
            if (username.isNullOrBlank()) {
                throw IllegalStateException("Sign in via Manage accounts to view your Saved posts.")
            }
            val response = apiService.getUserProfileSectionResponse(
                username = username,
                section = "saved",
                after = after
            )
            val savedListing = response.requireBodyOrThrow()
            val savedPosts = savedListing.data.children.mapNotNull { it.data as? RedditPost }
            return FeedFetchResult(
                posts = savedPosts,
                after = savedListing.data.after,
                requestedUrl = response.raw().request.url.toString(),
                responseCode = response.code()
            )
        }

        if (sub.equals("frontpage", ignoreCase = true)) {
            val response = apiService.getFrontpagePosts(
                sort = sortOrder,
                timeFilter = tFilter,
                after = after
            )
            val listing = response.requireBodyOrThrow()
            return FeedFetchResult(
                posts = listing.data.children.map { it.data },
                after = listing.data.after,
                requestedUrl = response.raw().request.url.toString(),
                responseCode = response.code()
            )
        }

        val response = apiService.getSubredditPosts(
            sub = sub,
            sort = sortOrder,
            timeFilter = tFilter,
            after = after
        )
        val listing = response.requireBodyOrThrow()
        return FeedFetchResult(
            posts = listing.data.children.map { it.data },
            after = listing.data.after,
            requestedUrl = response.raw().request.url.toString(),
            responseCode = response.code()
        )
    }

    /**
     * Loads the first page of posts for the current [subreddit] and [sort] (or active search),
     * serving fresh in-memory cache when [forceRefresh] is false.
     */
    fun loadInitialFeed(forceRefresh: Boolean = false, preserveWhileRefreshing: Boolean = false) {
        val requestKey = currentCacheKey()
        if (!forceRefresh) {
            val cached = feedCache[requestKey]
            if (cached != null && isCacheEntryFresh(cached)) {
                fetchJob?.cancel()
                nextPageJob?.cancel()
                nextPageJob = null
                isFetchingNextPage = false
                _uiState.value = FeedUiState.Success(
                    posts = cached.posts,
                    after = cached.after,
                    isLoadingMore = false,
                    isRefreshing = false,
                    paginationError = null
                )
                return
            }
        }

        fetchJob?.cancel()
        nextPageJob?.cancel()
        nextPageJob = null
        isFetchingNextPage = false
        val currentState = _uiState.value
        _uiState.value = if (preserveWhileRefreshing && currentState is FeedUiState.Success) {
            currentState.copy(isRefreshing = true, isLoadingMore = false, paginationError = null)
        } else {
            FeedUiState.Loading
        }
        val sub = _subreddit.value
        val sortOrder = _sort.value
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                runSuspendCatching {
                    executeFeedRequest(after = null)
                }.onSuccess { result ->
                    Log.d(
                        TAG,
                        "Loaded feed successfully | URL: ${result.requestedUrl} | HTTP response code: ${result.responseCode}"
                    )

                    feedCache[requestKey] = CachedFeedEntry(
                        posts = result.posts.take(MAX_CACHED_POSTS_PER_FEED),
                        after = result.after,
                        timestampMs = clockMs(),
                        firstVisibleItemIndex = if (requestKey == currentCacheKey()) currentScrollIndex else 0,
                        firstVisibleItemScrollOffset = if (requestKey == currentCacheKey()) currentScrollOffset else 0
                    )
                    if (requestKey == currentCacheKey()) {
                        _uiState.value = FeedUiState.Success(
                            posts = result.posts,
                            after = result.after,
                            isLoadingMore = false,
                            isRefreshing = false,
                            paginationError = null
                        )
                    }
                }.onFailure { throwable ->
                    val errorDetails = logRequestFailure(
                        action = if (preserveWhileRefreshing) "refresh" else "loadInitialFeed",
                        sub = sub,
                        sort = sortOrder,
                        after = null,
                        throwable = throwable
                    )
                    if (requestKey == currentCacheKey()) {
                        _uiState.value = FeedUiState.Error(
                            message = errorDetails
                        )
                    }
                }
            } finally {
                if (fetchJob === coroutineContext[Job]) {
                    fetchJob = null
                    if (requestKey == currentCacheKey()) {
                        val latestState = _uiState.value as? FeedUiState.Success
                        if (latestState != null && latestState.isRefreshing) {
                            _uiState.value = latestState.copy(isRefreshing = false)
                        }
                    }
                }
            }
        }
        fetchJob = job
        job.start()
    }

    /**
     * Pull-to-refresh handler that reloads from the top of the current subreddit feed.
     */
    fun refresh() {
        loadInitialFeed(forceRefresh = true, preserveWhileRefreshing = true)
    }

    /**
     * Explicitly retries pagination after a pagination failure.
     */
    fun retryNextPage() {
        loadNextPage(allowRetryAfterError = true)
    }

    /**
     * Appends the next page of posts using the current `after` token when scrolling near the bottom.
     */
    fun loadNextPage(allowRetryAfterError: Boolean = false) {
        val currentState = _uiState.value as? FeedUiState.Success ?: return
        val nextAfter = currentState.after ?: return
        if (isFetchingNextPage || currentState.isLoadingMore || currentState.isRefreshing) return
        if (!allowRetryAfterError && currentState.paginationError != null) return

        isFetchingNextPage = true
        _uiState.value = currentState.copy(isLoadingMore = true, paginationError = null)
        val requestKey = currentCacheKey()
        val sub = _subreddit.value
        val sortOrder = _sort.value

        nextPageJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                runSuspendCatching {
                    executeFeedRequest(after = nextAfter)
                }.onSuccess { result ->
                    Log.d(
                        TAG,
                        "Loaded next page | URL: ${result.requestedUrl} | HTTP response code: ${result.responseCode}"
                    )

                    val latestState = _uiState.value as? FeedUiState.Success ?: currentState
                    val mergedPosts = (latestState.posts + result.posts).distinctBy { it.id }
                    val existingEntry = feedCache[requestKey]
                    val cachedPosts: List<RedditPost>
                    val cachedAfter: String?
                    if (existingEntry != null && mergedPosts.size > MAX_CACHED_POSTS_PER_FEED) {
                        cachedPosts = existingEntry.posts
                        cachedAfter = existingEntry.after
                    } else {
                        cachedPosts = mergedPosts.take(MAX_CACHED_POSTS_PER_FEED)
                        cachedAfter = result.after
                    }
                    feedCache[requestKey] = CachedFeedEntry(
                        posts = cachedPosts,
                        after = cachedAfter,
                        timestampMs = clockMs(),
                        firstVisibleItemIndex = existingEntry?.firstVisibleItemIndex ?: currentScrollIndex,
                        firstVisibleItemScrollOffset = existingEntry?.firstVisibleItemScrollOffset ?: currentScrollOffset
                    )
                    if (requestKey == currentCacheKey()) {
                        _uiState.value = FeedUiState.Success(
                            posts = mergedPosts,
                            after = result.after,
                            isLoadingMore = false,
                            isRefreshing = false,
                            paginationError = null
                        )
                    }
                }.onFailure { throwable ->
                    val errorDetails = logRequestFailure(
                        action = "loadNextPage",
                        sub = sub,
                        sort = sortOrder,
                        after = nextAfter,
                        throwable = throwable
                    )
                    val latestState = _uiState.value as? FeedUiState.Success
                    if (latestState != null && requestKey == currentCacheKey()) {
                        _uiState.value = latestState.copy(
                            isLoadingMore = false,
                            paginationError = errorDetails
                        )
                    }
                }
            } finally {
                if (nextPageJob === coroutineContext[Job]) {
                    isFetchingNextPage = false
                    nextPageJob = null
                    if (requestKey == currentCacheKey()) {
                        val latestState = _uiState.value as? FeedUiState.Success
                        if (latestState != null && latestState.isLoadingMore) {
                            _uiState.value = latestState.copy(isLoadingMore = false)
                        }
                    }
                }
            }
        }
        nextPageJob = job
        job.start()
    }

    private fun logRequestFailure(
        action: String,
        sub: String,
        sort: String,
        after: String?,
        throwable: Throwable
    ): String {
        val fallbackUrl = buildString {
            append("https://oauth.reddit.com/r/$sub/$sort?raw_json=1")
            if (!after.isNullOrBlank()) append("&after=$after")
        }

        val httpException = throwable as? HttpException
        val exactUrl = httpException?.response()?.raw()?.request?.url?.toString()
            ?: fallbackUrl
        val statusCode = httpException?.code()

        Log.e(
            TAG,
            "[$action] Reddit API request failed | URL: $exactUrl | HTTP response code: ${statusCode ?: "N/A"} | Error: ${throwable.message}",
            throwable
        )

        return when {
            throwable is RateLimitException ->
                "Rate limit reached (${throwable.bucket}). Retry in ${throwable.retryAfterSeconds}s."
            statusCode != null ->
                "HTTP $statusCode ($exactUrl): ${throwable.localizedMessage ?: "Request failed"}"
            else ->
                throwable.localizedMessage ?: "Failed to load r/$sub"
        }
    }

    /**
     * Switches the active subreddit, resets scroll to top, and serves fresh cache or fetches.
     */
    fun selectSubreddit(newSubreddit: String) {
        var cleaned = newSubreddit.trim().trimStart('/')
        if (cleaned.startsWith("r/", ignoreCase = true)) {
            cleaned = cleaned.substring(2)
        }
        cleaned = cleaned.trim('/')
        if (cleaned.isNotEmpty()) {
            val switching = !cleaned.equals(_subreddit.value, ignoreCase = true) || _searchQuery.value != null
            if (switching) {
                pushBackStack(currentCacheKey())
            }
            _searchQuery.value = null
            _subreddit.value = cleaned
            if (cleaned.equals("frontpage", ignoreCase = true) && _sort.value == "hot") {
                _sort.value = "best"
            } else if (!cleaned.equals("frontpage", ignoreCase = true) && _sort.value == "best") {
                _sort.value = "hot"
            }
            emitScrollTarget(0, 0)
            loadInitialFeed(forceRefresh = false)
        }
    }

    /**
     * Switches the active sort order and optional time filter (`hour`, `day`, `week`, `month`, `year`, `all`).
     */
    fun selectSort(newSort: String, timeFilter: String? = null) {
        val normalized = newSort.trim().lowercase()
        if (normalized.isNotEmpty()) {
            _searchQuery.value = null
            _sort.value = normalized
            if (!timeFilter.isNullOrBlank()) {
                _timeFilter.value = timeFilter.trim().lowercase()
            }
            emitScrollTarget(0, 0)
            loadInitialFeed(forceRefresh = false)
        }
    }

    /**
     * Performs a server-side Reddit search (`/r/{subreddit}/search` or global `/search`).
     */
    fun performSearch(
        query: String,
        restrictToSub: Boolean = true,
        includeNsfw: Boolean = false,
        sort: String = "relevance",
        timeFilter: String = "all"
    ) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return
        pushBackStack(currentCacheKey())
        _searchQuery.value = cleanQuery
        _searchRestrictToSubreddit.value = restrictToSub
        _searchIncludeNsfw.value = includeNsfw
        _searchSort.value = sort
        _timeFilter.value = timeFilter
        emitScrollTarget(0, 0)
        loadInitialFeed(forceRefresh = true)
    }

    fun clearSearch() {
        if (_searchQuery.value != null) {
            if (!navigateBackInFeed()) {
                _searchQuery.value = null
                emitScrollTarget(0, 0)
                loadInitialFeed(forceRefresh = false)
            }
        }
    }

    companion object {
        private const val TAG = "FeedViewModel"
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private const val MAX_CACHE_ENTRIES = 20
        const val MAX_CACHED_POSTS_PER_FEED = 200
        private const val MAX_BACK_STACK = 20
        const val DEFAULT_SUBREDDIT = "all"
        const val DEFAULT_SORT = "hot"
        const val DEFAULT_TIME_FILTER = "day"
        val SORT_OPTIONS = listOf("hot", "new", "saved", "top", "controversial", "rising")
    }
}

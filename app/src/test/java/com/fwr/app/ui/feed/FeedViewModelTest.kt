package com.fwr.app.ui.feed

import com.fwr.app.data.api.RedditApiService
import com.fwr.app.data.model.MeResponse
import com.fwr.app.data.model.MoreChildrenResponse
import com.fwr.app.data.model.RedditListing
import com.fwr.app.data.model.RedditListingData
import com.fwr.app.data.model.RedditMessage
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.model.RedditThing
import com.fwr.app.data.model.SubredditAbout
import com.fwr.app.data.model.SubredditAboutWrapper
import com.fwr.app.data.model.TrophyListResponse
import com.fwr.app.data.model.UserAboutWrapper
import com.fwr.app.data.network.RateLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makePost(id: String, over18: Boolean = false) = RedditPost(
        id = id,
        title = "Post $id",
        subreddit = "kotlin",
        over_18 = over18
    )

    private fun listingResponse(
        posts: List<RedditPost>,
        after: String?
    ): Response<RedditListing<RedditPost>> {
        val children = posts.map { RedditThing(kind = "t3", data = it) }
        return Response.success(
            RedditListing(
                data = RedditListingData(
                    children = children,
                    after = after
                )
            )
        )
    }

    private class FakeRedditApiService(
        var handler: suspend (sub: String, sort: String, after: String?) -> Response<RedditListing<RedditPost>>
    ) : RedditApiService {
        var callCount = 0
        var searchHandler: (suspend (sub: String?, query: String, includeOver18: String, after: String?) -> Response<RedditListing<RedditPost>>)? = null

        override suspend fun getSubredditPosts(
            sub: String,
            sort: String,
            timeFilter: String?,
            after: String?,
            rawJson: Int
        ): Response<RedditListing<RedditPost>> {
            callCount++
            return handler(sub, sort, after)
        }

        override suspend fun getFrontpagePosts(
            sort: String,
            timeFilter: String?,
            after: String?,
            rawJson: Int
        ): Response<RedditListing<RedditPost>> {
            callCount++
            return handler("frontpage", sort, after)
        }

        override suspend fun getMe(): MeResponse = throw UnsupportedOperationException()
        override suspend fun getUserAbout(username: String): UserAboutWrapper = throw UnsupportedOperationException()
        override suspend fun getUserTrophies(username: String): TrophyListResponse = throw UnsupportedOperationException()
        override suspend fun getUserProfileSection(username: String, section: String, after: String?, sort: String?): RedditListing<Any> = throw UnsupportedOperationException()
        override suspend fun getUserProfileSectionResponse(username: String, section: String, after: String?, sort: String?): Response<RedditListing<Any>> = throw UnsupportedOperationException()
        override suspend fun searchSubredditPosts(sub: String, query: String, restrictSr: String, sort: String, timeFilter: String, includeOver18: String, after: String?, rawJson: Int): Response<RedditListing<RedditPost>> {
            callCount++
            return searchHandler?.invoke(sub, query, includeOver18, after) ?: throw UnsupportedOperationException()
        }
        override suspend fun searchAllPosts(query: String, sort: String, timeFilter: String, includeOver18: String, after: String?, rawJson: Int): Response<RedditListing<RedditPost>> {
            callCount++
            return searchHandler?.invoke(null, query, includeOver18, after) ?: throw UnsupportedOperationException()
        }
        override suspend fun getPostComments(articleId: String, sort: String?, limit: Int?, depth: Int?, rawJson: Int): List<RedditListing<Any>> = throw UnsupportedOperationException()
        override suspend fun getPostSubtreeComments(articleId: String, commentId: String, sort: String?, limit: Int, depth: Int, rawJson: Int): List<RedditListing<Any>> = throw UnsupportedOperationException()
        override suspend fun getMoreChildren(linkId: String, childrenCsv: String, apiType: String, rawJson: Int): MoreChildrenResponse = throw UnsupportedOperationException()
        override suspend fun getSubredditAbout(sub: String): SubredditAboutWrapper = throw UnsupportedOperationException()
        override suspend fun getSubscribedSubreddits(after: String?): RedditListing<SubredditAbout> = throw UnsupportedOperationException()
        override suspend fun setSubredditSubscription(action: String, subredditName: String) = throw UnsupportedOperationException()
        override suspend fun getMessages(where: String, after: String?): RedditListing<RedditMessage> = throw UnsupportedOperationException()
        override suspend fun vote(fullname: String, dir: Int) = throw UnsupportedOperationException()
        override suspend fun save(fullname: String) = throw UnsupportedOperationException()
        override suspend fun unsave(fullname: String) = throw UnsupportedOperationException()
        override suspend fun hide(fullname: String) = throw UnsupportedOperationException()
        override suspend fun unhide(fullname: String) = throw UnsupportedOperationException()
        override suspend fun postComment(parentFullname: String, markdownText: String, apiType: String): ResponseBody = throw UnsupportedOperationException()
        override suspend fun submitPost(subreddit: String, kind: String, title: String, url: String?, text: String?, sendReplies: Boolean, apiType: String): ResponseBody = throw UnsupportedOperationException()
        override suspend fun markMessageRead(fullnamesCsv: String) = throw UnsupportedOperationException()
        override suspend fun markMessageUnread(fullnamesCsv: String) = throw UnsupportedOperationException()
        override suspend fun composeMessage(recipient: String, subject: String, body: String, apiType: String): ResponseBody = throw UnsupportedOperationException()
        override suspend fun reportThing(thingId: String, reason: String, apiType: String): ResponseBody = throw UnsupportedOperationException()
        override suspend fun deleteThing(fullname: String) = throw UnsupportedOperationException()
    }

    @Test
    fun initialLoad_successPopulatesUiStateAndServesFreshCacheOnBackNavigation() = runTest {
        var nowMs = 1_000_000L
        val api = FakeRedditApiService { sub, _, _ ->
            listingResponse(listOf(makePost("${sub}_1")), after = "t3_${sub}_1")
        }
        val vm = FeedViewModel(
            apiService = api,
            usernameProvider = { null },
            clockMs = { nowMs },
            autoLoad = false
        )

        vm.loadInitialFeed()
        advanceUntilIdle()
        assertEquals(1, api.callCount)
        assertEquals(listOf("all_1"), (vm.uiState.value as FeedUiState.Success).posts.map { it.id })

        vm.selectSubreddit("kotlin")
        advanceUntilIdle()
        assertEquals(2, api.callCount)
        assertEquals(listOf("kotlin_1"), (vm.uiState.value as FeedUiState.Success).posts.map { it.id })

        nowMs += 60_000L // within 30 min TTL
        assertTrue(vm.navigateBackInFeed())
        advanceUntilIdle()
        assertEquals(2, api.callCount)
        assertEquals(listOf("all_1"), (vm.uiState.value as FeedUiState.Success).posts.map { it.id })
    }

    @Test
    fun loadNextPage_failureSetsPaginationErrorAndPreventsAutoRetryUntilRetryNextPage() = runTest {
        var shouldFailNext = true
        val api = FakeRedditApiService { _, _, after ->
            if (after == null) {
                listingResponse(listOf(makePost("p1")), after = "cursor_1")
            } else if (shouldFailNext) {
                throw IllegalStateException("Network glitch")
            } else {
                listingResponse(listOf(makePost("p2")), after = null)
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.loadNextPage()
        advanceUntilIdle()
        val afterFail = vm.uiState.value as FeedUiState.Success
        assertNotNull(afterFail.paginationError)
        assertEquals(2, api.callCount)

        // Subsequent automatic loadNextPage() must be ignored while paginationError != null
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(2, api.callCount)

        // Manual retry succeeds and clears paginationError
        shouldFailNext = false
        vm.retryNextPage()
        advanceUntilIdle()
        val afterRetry = vm.uiState.value as FeedUiState.Success
        assertNull(afterRetry.paginationError)
        assertEquals(3, api.callCount)
        assertEquals(listOf("p1", "p2"), afterRetry.posts.map { it.id })
    }

    @Test
    fun loadNextPage_rateLimitExceptionFormatsRetryAfterMessage() = runTest {
        val api = FakeRedditApiService { _, _, after ->
            if (after == null) {
                listingResponse(listOf(makePost("p1")), after = "cursor_1")
            } else {
                throw RateLimitException(retryAfterSeconds = 42L, bucket = "oauth.reddit.com")
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.loadNextPage()
        advanceUntilIdle()

        val state = vm.uiState.value as FeedUiState.Success
        assertEquals("Rate limit reached (oauth.reddit.com). Retry in 42s.", state.paginationError)
    }

    @Test
    fun loadInitialFeed_and_navigateBackInFeed_cancelInFlightNextPageJobWithoutSwallowingCancellation() = runTest {
        val gate = CompletableDeferred<Response<RedditListing<RedditPost>>>()
        val api = FakeRedditApiService { sub, _, after ->
            if (sub == "all" && after == null) {
                listingResponse(listOf(makePost("all_1")), after = "all_after")
            } else if (sub == "all" && after == "all_after") {
                gate.await()
            } else {
                listingResponse(listOf(makePost("${sub}_1")), after = "next_${sub}")
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.selectSubreddit("kotlin")
        advanceUntilIdle()
        assertTrue(vm.navigateBackInFeed())
        advanceUntilIdle()

        // Start pagination on "all", then immediately switch/reload while gate is suspended
        vm.loadNextPage()
        testScheduler.runCurrent()
        assertTrue((vm.uiState.value as FeedUiState.Success).isLoadingMore)

        vm.loadInitialFeed(forceRefresh = true)
        advanceUntilIdle()

        // Complete stale deferred; it must not overwrite current state or leave isFetchingNextPage stuck
        gate.complete(listingResponse(listOf(makePost("stale_page_2")), after = null))
        advanceUntilIdle()

        val current = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("all_1"), current.posts.map { it.id })
        assertNull(current.paginationError)
    }

    @Test
    fun cancellationExceptionOnFetchJobDoesNotProduceErrorState() = runTest {
        val api = FakeRedditApiService { _, _, _ ->
            throw CancellationException("Simulated job cancel")
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)

        vm.loadInitialFeed()
        advanceUntilIdle()

        assertTrue(vm.uiState.value is FeedUiState.Loading)
    }

    @Test
    fun loadNextPage_deduplicatesMergedPostsById() = runTest {
        val api = FakeRedditApiService { _, _, after ->
            if (after == null) {
                listingResponse(listOf(makePost("p1"), makePost("p2")), after = "page2")
            } else {
                listingResponse(listOf(makePost("p2"), makePost("p3")), after = null)
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.loadNextPage()
        advanceUntilIdle()

        val state = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("p1", "p2", "p3"), state.posts.map { it.id })
    }

    @Test
    fun feedCache_boundsCachedPostsToMaxPerFeedAndClampsRestoredScrollIndex() = runTest {
        var nowMs = 1_000_000L
        val page1 = (1..150).map { makePost("p$it") }
        val page2 = (151..250).map { makePost("p$it") }
        val api = FakeRedditApiService { sub, _, after ->
            if (sub == "all" && after == null) {
                listingResponse(page1, after = "cursor_150")
            } else if (sub == "all" && after == "cursor_150") {
                listingResponse(page2, after = "cursor_250")
            } else {
                listingResponse(listOf(makePost("other_1")), after = null)
            }
        }
        val vm = FeedViewModel(
            apiService = api,
            usernameProvider = { null },
            clockMs = { nowMs },
            autoLoad = false
        )
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.saveScrollPosition(index = 240, offset = 35)
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(250, (vm.uiState.value as FeedUiState.Success).posts.size)

        vm.selectSubreddit("kotlin")
        advanceUntilIdle()

        assertTrue(vm.navigateBackInFeed())
        advanceUntilIdle()

        val restored = vm.uiState.value as FeedUiState.Success
        assertTrue(restored.posts.size <= FeedViewModel.MAX_CACHED_POSTS_PER_FEED)
        assertEquals(150, restored.posts.size)
        assertEquals(149, vm.scrollTarget.value.index)
        assertEquals(0, vm.scrollTarget.value.offset)
    }

    @Test
    fun navigateBackInFeed_reloadsWhenCacheTtlExpired() = runTest {
        var nowMs = 1_000_000L
        val api = FakeRedditApiService { sub, _, _ ->
            listingResponse(listOf(makePost("${sub}_${nowMs}")), after = null)
        }
        val vm = FeedViewModel(
            apiService = api,
            usernameProvider = { null },
            clockMs = { nowMs },
            autoLoad = false
        )
        vm.loadInitialFeed()
        advanceUntilIdle()
        assertEquals(1, api.callCount)

        vm.selectSubreddit("kotlin")
        advanceUntilIdle()
        assertEquals(2, api.callCount)

        nowMs += 31L * 60L * 1000L // past 30 min TTL
        assertTrue(vm.navigateBackInFeed())
        advanceUntilIdle()
        assertEquals(3, api.callCount)
        assertEquals(listOf("all_${nowMs}"), (vm.uiState.value as FeedUiState.Success).posts.map { it.id })
    }

    @Test
    fun cancellationExceptionOnNextPageResetsIsLoadingMoreAndAllowsSubsequentPagination() = runTest {
        var cancelNextPage = true
        val api = FakeRedditApiService { _, _, after ->
            if (after == null) {
                listingResponse(listOf(makePost("p1")), after = "c1")
            } else if (cancelNextPage) {
                throw CancellationException("Cancelled page 2")
            } else {
                listingResponse(listOf(makePost("p2")), after = null)
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.loadNextPage()
        advanceUntilIdle()

        val afterCancel = vm.uiState.value as FeedUiState.Success
        assertEquals(false, afterCancel.isLoadingMore)
        assertNull(afterCancel.paginationError)

        cancelNextPage = false
        vm.loadNextPage()
        advanceUntilIdle()

        val afterRetry = vm.uiState.value as FeedUiState.Success
        assertEquals(false, afterRetry.isLoadingMore)
        assertEquals(listOf("p1", "p2"), afterRetry.posts.map { it.id })
    }

    @Test
    fun cancellationExceptionOnRefreshResetsIsRefreshingAndDoesNotBlockPagination() = runTest {
        var callIndex = 0
        val api = FakeRedditApiService { _, _, after ->
            callIndex++
            when {
                after == null && callIndex == 1 -> listingResponse(listOf(makePost("p1")), after = "c1")
                after == null && callIndex == 2 -> throw CancellationException("Cancelled refresh")
                else -> listingResponse(listOf(makePost("p2")), after = null)
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        val afterCancel = vm.uiState.value as FeedUiState.Success
        assertEquals(false, afterCancel.isRefreshing)

        vm.loadNextPage()
        advanceUntilIdle()

        val afterPage2 = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("p1", "p2"), afterPage2.posts.map { it.id })
    }

    @Test
    fun selectSubreddit_normalizesCaseInsensitivePrefixAndTrailingSlashes() = runTest {
        val requestedSubs = mutableListOf<String>()
        val api = FakeRedditApiService { sub, _, _ ->
            requestedSubs += sub
            listingResponse(listOf(makePost("${sub}_1")), after = null)
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)

        vm.selectSubreddit("R/Kotlin")
        advanceUntilIdle()
        assertEquals("Kotlin", vm.subreddit.value)

        vm.selectSubreddit("/r/android/")
        advanceUntilIdle()
        assertEquals("android", vm.subreddit.value)
        assertEquals(listOf("Kotlin", "android"), requestedSubs)
    }

    @Test
    fun refreshDuringInFlightNextPageClearsIsLoadingMoreAndDoesNotDeadlockOnRefreshCancellation() = runTest {
        val nextPageGate = CompletableDeferred<Response<RedditListing<RedditPost>>>()
        var refreshCallCount = 0
        var page2Phase = 0
        val api = FakeRedditApiService { _, _, after ->
            if (after == null) {
                refreshCallCount++
                if (refreshCallCount == 1) {
                    listingResponse(listOf(makePost("p1")), after = "c1")
                } else {
                    throw CancellationException("Cancelled refresh")
                }
            } else {
                page2Phase++
                if (page2Phase == 1) {
                    nextPageGate.await()
                } else {
                    listingResponse(listOf(makePost("p2")), after = null)
                }
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        // Start loadNextPage() and leave it suspended
        vm.loadNextPage()
        testScheduler.runCurrent()
        assertTrue((vm.uiState.value as FeedUiState.Success).isLoadingMore)

        // Interrupt loadNextPage() with refresh(); isLoadingMore must clear immediately
        vm.refresh()
        val duringRefresh = vm.uiState.value as FeedUiState.Success
        assertTrue(duringRefresh.isRefreshing)
        assertEquals(false, duringRefresh.isLoadingMore)

        // Let cancelled refresh finish and complete the stale nextPage gate
        advanceUntilIdle()
        nextPageGate.complete(listingResponse(listOf(makePost("stale_p2")), after = null))
        advanceUntilIdle()

        val afterCancelledRefresh = vm.uiState.value as FeedUiState.Success
        assertEquals(false, afterCancelledRefresh.isRefreshing)
        assertEquals(false, afterCancelledRefresh.isLoadingMore)

        // Subsequent pagination must NOT be deadlocked
        vm.loadNextPage()
        advanceUntilIdle()
        val afterPaginate = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("p1", "p2"), afterPaginate.posts.map { it.id })
    }

    @Test
    fun selectSubreddit_prefixOnlyRSlashNormalizesToEmptyAndIsIgnored() = runTest {
        val api = FakeRedditApiService { sub, _, _ ->
            listingResponse(listOf(makePost("${sub}_1")), after = null)
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()
        assertEquals("all", vm.subreddit.value)
        assertEquals(1, api.callCount)

        vm.selectSubreddit("r/")
        vm.selectSubreddit("/r/")
        vm.selectSubreddit("  /R/  ")
        advanceUntilIdle()

        assertEquals("all", vm.subreddit.value)
        assertEquals(1, api.callCount)
    }

    @Test
    fun performSearch_filtersNsfwResultsWhenUncheckedAndSkipsAllNsfwFirstPage() = runTest {
        val recordedOver18Flags = mutableListOf<String>()
        val api = FakeRedditApiService { _, _, _ ->
            listingResponse(listOf(makePost("feed_1")), after = null)
        }.apply {
            searchHandler = { _, _, includeOver18, after ->
                recordedOver18Flags += includeOver18
                when {
                    includeOver18 == "false" && after == null -> {
                        listingResponse(listOf(makePost("nsfw_only", over18 = true)), after = "page2")
                    }
                    includeOver18 == "false" && after == "page2" -> {
                        listingResponse(
                            listOf(
                                makePost("nsfw_2", over18 = true),
                                makePost("sfw_1", over18 = false)
                            ),
                            after = null
                        )
                    }
                    else -> {
                        listingResponse(
                            listOf(
                                makePost("nsfw_2", over18 = true),
                                makePost("sfw_1", over18 = false)
                            ),
                            after = null
                        )
                    }
                }
            }
        }
        val vm = FeedViewModel(apiService = api, usernameProvider = { null }, autoLoad = false)
        vm.loadInitialFeed()
        advanceUntilIdle()

        vm.performSearch(query = "test", restrictToSub = false, includeNsfw = false)
        advanceUntilIdle()

        assertEquals(listOf("false", "false"), recordedOver18Flags)
        val sfwResult = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("sfw_1"), sfwResult.posts.map { it.id })

        // Enabling includeNsfw issues a fresh query with "on" and keeps NSFW posts
        vm.performSearch(query = "test", restrictToSub = false, includeNsfw = true)
        advanceUntilIdle()
        val nsfwResult = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("nsfw_2", "sfw_1"), nsfwResult.posts.map { it.id })
        assertEquals("on", recordedOver18Flags.last())

        // Navigating back restores previous SFW-only search cache without losing distinct cache key
        assertTrue(vm.navigateBackInFeed())
        advanceUntilIdle()
        val restoredSfw = vm.uiState.value as FeedUiState.Success
        assertEquals(listOf("sfw_1"), restoredSfw.posts.map { it.id })
    }
}

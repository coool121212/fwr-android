package com.fwr.app.data.api

import com.fwr.app.data.model.MeResponse
import com.fwr.app.data.model.MoreChildrenResponse
import com.fwr.app.data.model.RedditListing
import com.fwr.app.data.model.RedditMessage
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.model.SubredditAbout
import com.fwr.app.data.model.SubredditAboutWrapper
import com.fwr.app.data.model.TrophyListResponse
import com.fwr.app.data.model.UserAboutWrapper
import com.fwr.app.data.network.RedditNetworkClient
import kotlinx.serialization.Contextual
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit API definition for Reddit OAuth endpoints (`https://oauth.reddit.com/`).
 */
interface RedditApiService {

    /**
     * Fetches the authenticated user's account identity (`/api/v1/me?raw_json=1`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("api/v1/me?raw_json=1")
    suspend fun getMe(): MeResponse

    /**
     * Fetches public profile details (`link_karma`, `comment_karma`, `created_utc`) for [username].
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("user/{username}/about.json?raw_json=1")
    suspend fun getUserAbout(
        @Path("username") username: String
    ): UserAboutWrapper

    /**
     * Fetches the trophy case for [username] (`/api/v1/user/{username}/trophies.json?raw_json=1`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("api/v1/user/{username}/trophies.json?raw_json=1")
    suspend fun getUserTrophies(
        @Path("username") username: String
    ): TrophyListResponse

    /**
     * Fetches a mixed or filtered history listing (`t1` comments and `t3` posts) for [username]:
     * [section] can be `overview`, `comments`, `submitted`, `gilded`, `upvoted`, `downvoted`, `hidden`, `saved`.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("user/{username}/{section}.json?raw_json=1")
    suspend fun getUserProfileSection(
        @Path("username") username: String,
        @Path("section") section: String = "overview",
        @Query("after") after: String? = null,
        @Query("sort") sort: String? = null
    ): RedditListing<@Contextual Any>

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("user/{username}/{section}.json?raw_json=1")
    suspend fun getUserProfileSectionResponse(
        @Path("username") username: String,
        @Path("section") section: String = "overview",
        @Query("after") after: String? = null,
        @Query("sort") sort: String? = null
    ): Response<RedditListing<@Contextual Any>>

    /**
     * Fetches the user's Reddit front page listing (`best`, `hot`, `new`, `top`, `controversial`, `rising`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("{sort}.json")
    suspend fun getFrontpagePosts(
        @Path("sort") sort: String = "best",
        @Query("t") timeFilter: String? = null,
        @Query("after") after: String? = null,
        @Query("raw_json") rawJson: Int = 1
    ): Response<RedditListing<RedditPost>>

    /**
     * Fetches a paginated listing of posts from a given subreddit sorted by [sort]
     * (e.g., `https://oauth.reddit.com/r/all/hot?raw_json=1`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("r/{subreddit}/{sort}.json")
    suspend fun getSubredditPosts(
        @Path("subreddit") sub: String,
        @Path("sort") sort: String = "hot",
        @Query("t") timeFilter: String? = null,
        @Query("after") after: String? = null,
        @Query("raw_json") rawJson: Int = 1
    ): Response<RedditListing<RedditPost>>

    /**
     * Server-side subreddit search (`/r/{subreddit}/search`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("r/{subreddit}/search.json")
    suspend fun searchSubredditPosts(
        @Path("subreddit") sub: String,
        @Query("q") query: String,
        @Query("restrict_sr") restrictSr: String = "on",
        @Query("sort") sort: String = "relevance",
        @Query("t") timeFilter: String = "all",
        @Query("include_over_18") includeOver18: String = "on",
        @Query("after") after: String? = null,
        @Query("raw_json") rawJson: Int = 1
    ): Response<RedditListing<RedditPost>>

    /**
     * Server-side global Reddit search (`/search`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("search.json")
    suspend fun searchAllPosts(
        @Query("q") query: String,
        @Query("sort") sort: String = "relevance",
        @Query("t") timeFilter: String = "all",
        @Query("include_over_18") includeOver18: String = "on",
        @Query("after") after: String? = null,
        @Query("raw_json") rawJson: Int = 1
    ): Response<RedditListing<RedditPost>>

    /**
     * Fetches the post detail and comment tree for a given [articleId].
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("comments/{article}.json")
    suspend fun getPostComments(
        @Path("article") articleId: String,
        @Query("sort") sort: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("depth") depth: Int? = null,
        @Query("raw_json") rawJson: Int = 1
    ): List<RedditListing<@Contextual Any>>

    /**
     * Fetches a specific comment subtree (`/comments/{article}/_/{comment}`), used by
     * notBrian2's "Fix load more comments" fallback when `/api/morechildren` is blocked.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("comments/{article}/_/{comment}.json")
    suspend fun getPostSubtreeComments(
        @Path("article") articleId: String,
        @Path("comment") commentId: String,
        @Query("sort") sort: String? = null,
        @Query("limit") limit: Int = 500,
        @Query("depth") depth: Int = 10,
        @Query("context") context: Int? = null,
        @Query("raw_json") rawJson: Int = 1
    ): List<RedditListing<@Contextual Any>>

    /**
     * Loads truncated child comments from a `"more"` stub (`kind == "more"`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("api/morechildren")
    suspend fun getMoreChildren(
        @Query("link_id") linkId: String,
        @Query("children") childrenCsv: String,
        @Query("api_type") apiType: String = "json",
        @Query("raw_json") rawJson: Int = 1
    ): MoreChildrenResponse

    /**
     * Fetches sidebar, subscriber counts, and rules for [sub].
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("r/{subreddit}/about.json?raw_json=1")
    suspend fun getSubredditAbout(
        @Path("subreddit") sub: String
    ): SubredditAboutWrapper

    /**
     * Fetches the authenticated user's subscribed subreddits.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("subreddits/mine/subscriber.json?limit=100&raw_json=1")
    suspend fun getSubscribedSubreddits(
        @Query("after") after: String? = null
    ): RedditListing<SubredditAbout>

    /**
     * Subscribes (`sub`) or unsubscribes (`unsub`) to a subreddit.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/subscribe")
    suspend fun setSubredditSubscription(
        @Field("action") action: String,
        @Field("sr_name") subredditName: String
    ): Unit

    /**
     * Fetches inbox / modmail listings (`inbox`, `unread`, `messages`, `comments`, `selfreply`, `sent`, `moderator`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @GET("message/{where}.json?raw_json=1")
    suspend fun getMessages(
        @Path("where") where: String = "inbox",
        @Query("after") after: String? = null
    ): RedditListing<RedditMessage>

    /**
     * Casts a vote (`1` = upvote, `0` = clear, `-1` = downvote) on a `t1_` comment or `t3_` post.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/vote")
    suspend fun vote(
        @Field("id") fullname: String,
        @Field("dir") dir: Int
    ): Unit

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/save")
    suspend fun save(
        @Field("id") fullname: String
    ): Unit

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/unsave")
    suspend fun unsave(
        @Field("id") fullname: String
    ): Unit

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/hide")
    suspend fun hide(
        @Field("id") fullname: String
    ): Unit

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/unhide")
    suspend fun unhide(
        @Field("id") fullname: String
    ): Unit

    /**
     * Submits a reply to a post (`t3_...`), comment (`t1_...`), or message (`t4_...`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/comment")
    suspend fun postComment(
        @Field("thing_id") parentFullname: String,
        @Field("text") markdownText: String,
        @Field("api_type") apiType: String = "json"
    ): ResponseBody

    /**
     * Submits a new link (`kind = "link"`) or self post (`kind = "self"`) to [subreddit].
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/submit")
    suspend fun submitPost(
        @Field("sr") subreddit: String,
        @Field("kind") kind: String,
        @Field("title") title: String,
        @Field("url") url: String? = null,
        @Field("text") text: String? = null,
        @Field("sendreplies") sendReplies: Boolean = true,
        @Field("api_type") apiType: String = "json"
    ): ResponseBody

    /**
     * Marks messages read or unread (`id` comma-separated fullnames).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/read_message")
    suspend fun markMessageRead(
        @Field("id") fullnamesCsv: String
    ): Unit

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/unread_message")
    suspend fun markMessageUnread(
        @Field("id") fullnamesCsv: String
    ): Unit

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/compose")
    suspend fun composeMessage(
        @Field("to") recipient: String,
        @Field("subject") subject: String,
        @Field("text") body: String,
        @Field("api_type") apiType: String = "json"
    ): ResponseBody

    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/report")
    suspend fun reportThing(
        @Field("thing_id") thingId: String,
        @Field("reason") reason: String = "other",
        @Field("api_type") apiType: String = "json"
    ): ResponseBody

    /**
     * Permanently deletes a comment (`t1_...`) or post (`t3_...`) authored by the current user.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/del")
    suspend fun deleteThing(
        @Field("id") fullname: String
    ): Unit

    /**
     * Edits the markdown text of a comment (`t1_...`) or self post (`t3_...`) authored by the current user.
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/editusertext")
    suspend fun editUserText(
        @Field("thing_id") thingId: String,
        @Field("text") markdownText: String,
        @Field("api_type") apiType: String = "json",
        @Field("raw_json") rawJson: Int = 1
    ): ResponseBody

    /**
     * Enables or disables inbox reply notifications for an authored comment (`t1_...`) or post (`t3_...`).
     */
    @Headers("User-Agent: ${RedditNetworkClient.CUSTOM_USER_AGENT}")
    @FormUrlEncoded
    @POST("api/sendreplies")
    suspend fun setSendReplies(
        @Field("id") fullname: String,
        @Field("state") state: Boolean
    ): Unit
}

package com.fwr.app.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from `/api/v1/me` or inside `user/{username}/about.json` (`t2` thing).
 */
@Immutable
@Serializable
data class MeResponse(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("link_karma")
    val link_karma: Int = 0,
    @SerialName("comment_karma")
    val comment_karma: Int = 0,
    @SerialName("total_karma")
    val total_karma: Int = 0,
    @SerialName("created_utc")
    val created_utc: Double = 0.0,
    @SerialName("icon_img")
    val icon_img: String? = null,
    @SerialName("snoovatar_img")
    val snoovatar_img: String? = null,
    @SerialName("is_gold")
    val is_gold: Boolean = false,
    @SerialName("is_mod")
    val is_mod: Boolean = false
)

/**
 * Wrapper returned by `GET user/{username}/about.json?raw_json=1`.
 */
@Immutable
@Serializable
data class UserAboutWrapper(
    @SerialName("kind")
    val kind: String = "t2",
    @SerialName("data")
    val data: MeResponse = MeResponse()
)

/**
 * Response returned by `GET api/v1/user/{username}/trophies.json?raw_json=1`.
 * Structure: `{ "kind": "TrophyList", "data": { "trophies": [ { "kind": "t6", "data": { ... } } ] } }`
 */
@Immutable
@Serializable
data class TrophyListResponse(
    @SerialName("kind")
    val kind: String = "TrophyList",
    @SerialName("data")
    val data: TrophyListData = TrophyListData()
)

@Immutable
@Serializable
data class TrophyListData(
    @SerialName("trophies")
    val trophies: List<TrophyItemWrapper> = emptyList()
)

@Immutable
@Serializable
data class TrophyItemWrapper(
    @SerialName("kind")
    val kind: String = "t6",
    @SerialName("data")
    val data: TrophyItem = TrophyItem()
)

@Immutable
@Serializable
data class TrophyItem(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String = "",
    @SerialName("description")
    val description: String? = null,
    @SerialName("icon_70")
    val icon_70: String = "",
    @SerialName("icon_40")
    val icon_40: String = "",
    @SerialName("award_id")
    val award_id: String? = null,
    @SerialName("granted_at")
    val granted_at: Long? = null
)

/**
 * Represents an entry in `all_awardings` on a `t3` [RedditPost] or `t1` [RedditComment].
 */
@Immutable
@Serializable
data class RedditAward(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("count")
    val count: Int = 1,
    @SerialName("icon_url")
    val icon_url: String = "",
    @SerialName("resized_icons")
    val resized_icons: List<RedditPreviewSource> = emptyList()
) {
    val bestIconUrl: String
        get() = resized_icons.firstOrNull { it.width in 16..64 && it.url.isNotBlank() }?.url
            ?: icon_url
}

/**
 * Wrapper returned by `GET r/{subreddit}/about.json?raw_json=1` (`t5` thing).
 */
@Immutable
@Serializable
data class SubredditAboutWrapper(
    @SerialName("kind")
    val kind: String = "t5",
    @SerialName("data")
    val data: SubredditAbout = SubredditAbout()
)

@Immutable
@Serializable
data class SubredditAbout(
    @SerialName("id")
    val id: String = "",
    @SerialName("display_name")
    val display_name: String = "",
    @SerialName("title")
    val title: String = "",
    @SerialName("public_description")
    val public_description: String = "",
    @SerialName("description")
    val description: String = "",
    @SerialName("subscribers")
    val subscribers: Long = 0L,
    @SerialName("accounts_active")
    val accounts_active: Int? = null,
    @SerialName("active_user_count")
    val active_user_count: Int? = null,
    @SerialName("over18")
    val over18: Boolean = false,
    @SerialName("user_is_subscriber")
    val user_is_subscriber: Boolean = false
)

/**
 * Represents a message or reply item (`t1` comment reply or `t4` private message) in `/message/{where}.json`.
 */
@Immutable
@Serializable
data class RedditMessage(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("author")
    val author: String? = null,
    @SerialName("dest")
    val dest: String? = null,
    @SerialName("subject")
    val subject: String = "",
    @SerialName("body")
    val body: String = "",
    @SerialName("subreddit")
    val subreddit: String? = null,
    @SerialName("link_title")
    val link_title: String? = null,
    @SerialName("context")
    val context: String? = null,
    @SerialName("created_utc")
    val created_utc: Double = 0.0,
    @SerialName("new")
    val new: Boolean = false,
    @SerialName("was_comment")
    val was_comment: Boolean = false,
    @SerialName("score")
    val score: Int = 0,
    @SerialName("likes")
    val likes: Boolean? = null
) {
    val resolvedFullname: String
        get() = name.ifBlank { if (was_comment) "t1_$id" else "t4_$id" }
}

/**
 * Response from `GET /api/morechildren?api_type=json`.
 */
@Immutable
@Serializable
data class MoreChildrenResponse(
    @SerialName("json")
    val json: MoreChildrenJson = MoreChildrenJson()
)

@Immutable
@Serializable
data class MoreChildrenJson(
    @SerialName("data")
    val data: MoreChildrenData = MoreChildrenData()
)

@Immutable
@Serializable
data class MoreChildrenData(
    @SerialName("things")
    val things: List<RedditThing<RedditComment>> = emptyList()
)

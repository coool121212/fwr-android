package com.fwr.app.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Reddit link/submission ('t3' thing).
 */
@Immutable
@Serializable
data class RedditPost(
    @SerialName("id")
    val id: String = "",
    @SerialName("title")
    val title: String = "",
    @SerialName("author")
    val author: String = "[deleted]",
    @SerialName("subreddit")
    val subreddit: String = "",
    @SerialName("score")
    val score: Int = 0,
    @SerialName("num_comments")
    val num_comments: Int = 0,
    @SerialName("created_utc")
    val created_utc: Double = 0.0,
    @SerialName("url")
    val url: String = "",
    @SerialName("url_overridden_by_dest")
    val url_overridden_by_dest: String? = null,
    @SerialName("thumbnail")
    val thumbnail: String? = null,
    @SerialName("selftext")
    val selftext: String = "",
    @SerialName("is_self")
    val is_self: Boolean = false,
    @SerialName("is_video")
    val is_video: Boolean = false,
    @SerialName("permalink")
    val permalink: String = "",
    @SerialName("is_gallery")
    val is_gallery: Boolean = false,
    @SerialName("gallery_data")
    val gallery_data: GalleryData? = null,
    @SerialName("media_metadata")
    val media_metadata: Map<String, MediaMetadata>? = null,
    @SerialName("secure_media")
    val secure_media: RedditMedia? = null,
    @SerialName("media")
    val media: RedditMedia? = null,
    @SerialName("preview")
    val preview: RedditPreview? = null,
    @SerialName("domain")
    val domain: String? = null,
    @SerialName("post_hint")
    val post_hint: String? = null,
    @SerialName("over_18")
    val over_18: Boolean = false,
    @SerialName("spoiler")
    val spoiler: Boolean = false,
    @SerialName("stickied")
    val stickied: Boolean = false,
    @SerialName("link_flair_text")
    val link_flair_text: String? = null,
    @SerialName("author_flair_text")
    val author_flair_text: String? = null,
    @SerialName("gilded")
    val gilded: Int = 0,
    @SerialName("all_awardings")
    val all_awardings: List<RedditAward> = emptyList(),
    @SerialName("name")
    val fullname: String = "",
    @SerialName("likes")
    val likes: Boolean? = null,
    @SerialName("saved")
    val saved: Boolean = false,
    @SerialName("hidden")
    val hidden: Boolean = false,
    @SerialName("locked")
    val locked: Boolean = false,
    @SerialName("archived")
    val archived: Boolean = false,
    @SerialName("distinguished")
    val distinguished: String? = null,
    @SerialName("suggested_sort")
    val suggested_sort: String? = null,
    @SerialName("crosspost_parent_list")
    val crosspost_parent_list: List<RedditPost> = emptyList(),
    @SerialName("is_reddit_media_domain")
    val is_reddit_media_domain: Boolean = false
) {
    val resolvedFullname: String get() = fullname.ifBlank { "t3_$id" }
    val numComments: Int get() = num_comments
    val createdUtc: Double get() = created_utc
    val isSelf: Boolean get() = is_self
    val isVideo: Boolean get() = is_video
    val isGallery: Boolean get() = is_gallery
    val galleryData: GalleryData? get() = gallery_data
    val mediaMetadata: Map<String, MediaMetadata>? get() = media_metadata
}

@Immutable
@Serializable
data class GalleryData(
    @SerialName("items")
    val items: List<GalleryItem> = emptyList()
)

@Immutable
@Serializable
data class GalleryItem(
    @SerialName("media_id")
    val media_id: String = "",
    @SerialName("id")
    val id: Long? = null,
    @SerialName("caption")
    val caption: String? = null,
    @SerialName("outbound_url")
    val outbound_url: String? = null
) {
    val mediaId: String get() = media_id
    val outboundUrl: String? get() = outbound_url
}

@Immutable
@Serializable
data class MediaMetadata(
    @SerialName("status")
    val status: String? = null,
    @SerialName("e")
    val e: String? = null,
    @SerialName("m")
    val m: String? = null,
    @SerialName("s")
    val s: MediaImageSource? = null,
    @SerialName("p")
    val p: List<MediaImageSource> = emptyList(),
    @SerialName("id")
    val id: String? = null
)

@Immutable
@Serializable
data class MediaImageSource(
    @SerialName("x")
    val x: Int = 0,
    @SerialName("y")
    val y: Int = 0,
    @SerialName("u")
    val u: String? = null,
    @SerialName("gif")
    val gif: String? = null,
    @SerialName("mp4")
    val mp4: String? = null
)

@Immutable
@Serializable
data class RedditMedia(
    @SerialName("reddit_video")
    val reddit_video: RedditVideoInfo? = null
)

@Immutable
@Serializable
data class RedditVideoInfo(
    @SerialName("fallback_url")
    val fallback_url: String? = null,
    @SerialName("dash_url")
    val dash_url: String? = null,
    @SerialName("hls_url")
    val hls_url: String? = null,
    @SerialName("width")
    val width: Int? = null,
    @SerialName("height")
    val height: Int? = null,
    @SerialName("is_gif")
    val is_gif: Boolean = false,
    @SerialName("has_audio")
    val has_audio: Boolean? = null
)

@Immutable
@Serializable
data class RedditPreview(
    @SerialName("images")
    val images: List<RedditPreviewImage> = emptyList(),
    @SerialName("reddit_video_preview")
    val reddit_video_preview: RedditVideoInfo? = null
)

@Immutable
@Serializable
data class RedditPreviewImage(
    @SerialName("source")
    val source: RedditPreviewSource? = null,
    @SerialName("resolutions")
    val resolutions: List<RedditPreviewSource> = emptyList()
)

@Immutable
@Serializable
data class RedditPreviewSource(
    @SerialName("url")
    val url: String = "",
    @SerialName("width")
    val width: Int = 0,
    @SerialName("height")
    val height: Int = 0
)

package com.fwr.app.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Represents a Reddit comment ('t1' thing) with support for nested replies.
 */
@Immutable
@Serializable
data class RedditComment(
    @SerialName("id")
    val id: String = "",
    @SerialName("author")
    val author: String = "[deleted]",
    @SerialName("body")
    val body: String = "",
    @SerialName("score")
    val score: Int = 0,
    @SerialName("created_utc")
    val created_utc: Double = 0.0,
    @SerialName("depth")
    val depth: Int = 0,
    @SerialName("parent_id")
    val parent_id: String? = null,
    @SerialName("link_id")
    val link_id: String? = null,
    @SerialName("link_title")
    val link_title: String? = null,
    @SerialName("link_permalink")
    val link_permalink: String? = null,
    @SerialName("link_url")
    val link_url: String? = null,
    @SerialName("subreddit")
    val subreddit: String = "",
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
    @SerialName("archived")
    val archived: Boolean = false,
    @SerialName("locked")
    val locked: Boolean = false,
    @SerialName("permalink")
    val permalink: String? = null,
    @SerialName("controversiality")
    val controversiality: Int = 0,
    @SerialName("distinguished")
    val distinguished: String? = null,
    @SerialName("is_submitter")
    val is_submitter: Boolean = false,
    @SerialName("score_hidden")
    val score_hidden: Boolean = false,
    @SerialName("edited")
    @Serializable(with = RedditEditedSerializer::class)
    val edited: Boolean = false,
    @SerialName("count")
    val count: Int? = null,
    @SerialName("children")
    val moreChildrenIds: List<String> = emptyList(),
    @SerialName("media_metadata")
    val media_metadata: Map<String, MediaMetadata>? = null,
    @SerialName("replies")
    @Serializable(with = CommentRepliesSerializer::class)
    val replies: RedditListing<RedditComment>? = null
) {
    val createdUtc: Double get() = created_utc
    val resolvedFullname: String get() = fullname.ifBlank { "t1_$id" }
}

/**
 * Reddit's JSON API encodes `replies` as an empty string `""` when a comment has no replies,
 * and as a JSON object (`RedditListing<RedditComment>`) when replies exist.
 */
object CommentRepliesSerializer : KSerializer<RedditListing<RedditComment>?> {
    private val delegateSerializer = RedditListing.serializer(RedditComment.serializer())

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun deserialize(decoder: Decoder): RedditListing<RedditComment>? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return delegateSerializer.deserialize(decoder)

        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonObject) {
            jsonDecoder.json.decodeFromJsonElement(delegateSerializer, element)
        } else {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: RedditListing<RedditComment>?) {
        if (value == null) {
            encoder.encodeString("")
        } else {
            delegateSerializer.serialize(encoder, value)
        }
    }
}

/**
 * Reddit encodes `edited` as `false` when unedited or as a Unix timestamp float when edited.
 */
object RedditEditedSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RedditEdited", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return false
        return element.booleanOrNull == true || (element.doubleOrNull ?: 0.0) > 0.0
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

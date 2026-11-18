package com.fwr.app.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Generic wrapper for Reddit API Listing responses.
 */
@Immutable
@Serializable
data class RedditListing<T>(
    @SerialName("kind")
    val kind: String = "Listing",
    @SerialName("data")
    val data: RedditListingData<T> = RedditListingData()
)

@Immutable
@Serializable
data class RedditListingData<T>(
    @SerialName("children")
    val children: List<RedditThing<T>> = emptyList(),
    @SerialName("after")
    val after: String? = null,
    @SerialName("before")
    val before: String? = null,
    @SerialName("dist")
    val dist: Int? = null,
    @SerialName("modhash")
    val modhash: String? = null
)

/**
 * Generic envelope for individual items inside a Reddit Listing (e.g., "t1" comment, "t3" post).
 */
@Immutable
@Serializable
data class RedditThing<T>(
    @SerialName("kind")
    val kind: String = "",
    @SerialName("data")
    val data: T
)

package com.fwr.app.data.model

import androidx.compose.runtime.Immutable
import com.fwr.app.data.network.RedditNetworkClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Wrapper returned inside `/api/multi/mine` and `/api/multi/user/{username}` (`kind == "LabeledMulti"`).
 */
@Immutable
@Serializable
data class RedditMultiredditWrapper(
    @SerialName("kind")
    val kind: String = "LabeledMulti",
    @SerialName("data")
    val data: RedditMultireddit = RedditMultireddit()
)

@Immutable
@Serializable
data class RedditMultiredditSubreddit(
    @SerialName("name")
    val name: String = ""
)

/**
 * Represents a Reddit Multireddit (Custom Feed).
 */
@Immutable
@Serializable
data class RedditMultireddit(
    @SerialName("name")
    val name: String = "",
    @SerialName("display_name")
    val display_name: String = "",
    @SerialName("path")
    val path: String = "",
    @SerialName("owner")
    val owner: String = "",
    @SerialName("description_md")
    val description_md: String = "",
    @SerialName("visibility")
    val visibility: String = "private",
    @SerialName("over_18")
    val over_18: Boolean = false,
    @SerialName("can_edit")
    val can_edit: Boolean = false,
    @SerialName("icon_url")
    val icon_url: String? = null,
    @SerialName("subreddits")
    val subreddits: List<RedditMultiredditSubreddit> = emptyList()
) {
    val resolvedName: String
        get() = name.ifBlank { MultiredditUtils.parseMultiredditPath(path)?.name.orEmpty() }

    val resolvedOwner: String
        get() = owner.ifBlank { MultiredditUtils.parseMultiredditPath(path)?.owner.orEmpty() }

    val resolvedDisplayName: String
        get() = display_name.ifBlank { resolvedName }

    val canonicalFeedKey: String
        get() {
            val cleanName = resolvedName.trim()
            if (cleanName.isBlank()) return ""
            val cleanOwner = resolvedOwner.trim()
            return if (cleanOwner.isNotBlank()) {
                "user/$cleanOwner/m/$cleanName"
            } else {
                "me/m/$cleanName"
            }
        }

    val subredditNames: List<String>
        get() = subreddits
            .asSequence()
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
            .toList()
}

/**
 * Parsed multireddit path identifier. `owner == null` indicates `/me/m/{name}`.
 */
@Immutable
data class ParsedMultiredditPath(
    val owner: String?,
    val name: String
)

/**
 * Shared helpers for parsing, normalizing, formatting, and serializing Reddit multireddits.
 */
object MultiredditUtils {

    private val NON_SLUG_CHARS_REGEX = Regex("[^a-z0-9_]+")
    private val MULTI_UNDERSCORE_REGEX = Regex("_+")

    /**
     * Parses multireddit targets such as:
     * - `/user/{owner}/m/{name}` or `user/{owner}/m/{name}`
     * - `/u/{owner}/m/{name}` or `u/{owner}/m/{name}`
     * - `/me/m/{name}` or `me/m/{name}`
     * - `/m/{name}` or `m/{name}`
     */
    fun parseMultiredditPath(raw: String): ParsedMultiredditPath? {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return null
        val segments = trimmed.split('/')
        if (segments.any { it.isBlank() }) return null

        return when {
            segments.size == 4 &&
                (segments[0].equals("user", ignoreCase = true) || segments[0].equals("u", ignoreCase = true)) &&
                segments[2].equals("m", ignoreCase = true) -> {
                val owner = segments[1].trim()
                val name = segments[3].trim()
                if (owner.isNotEmpty() && name.isNotEmpty()) {
                    ParsedMultiredditPath(owner = owner, name = name)
                } else {
                    null
                }
            }
            segments.size == 3 &&
                segments[0].equals("me", ignoreCase = true) &&
                segments[1].equals("m", ignoreCase = true) -> {
                val name = segments[2].trim()
                if (name.isNotEmpty()) {
                    ParsedMultiredditPath(owner = null, name = name)
                } else {
                    null
                }
            }
            segments.size == 2 && segments[0].equals("m", ignoreCase = true) -> {
                val name = segments[1].trim()
                if (name.isNotEmpty()) {
                    ParsedMultiredditPath(owner = null, name = name)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun isMultiredditTarget(raw: String): Boolean = parseMultiredditPath(raw) != null

    /**
     * Normalizes either a multireddit target (returning `user/{owner}/m/{name}` or `me/m/{name}`)
     * or a standard subreddit name (stripping leading `r/` and slashes).
     */
    fun normalizeFeedTarget(raw: String, currentUsername: String? = null): String {
        val parsed = parseMultiredditPath(raw)
        if (parsed != null) {
            val resolvedOwner = parsed.owner?.takeIf { it.isNotBlank() }
                ?: currentUsername?.trim()?.takeIf { it.isNotEmpty() }
            return if (resolvedOwner != null) {
                "user/$resolvedOwner/m/${parsed.name}"
            } else {
                "me/m/${parsed.name}"
            }
        }
        var cleaned = raw.trim().trimStart('/')
        if (cleaned.startsWith("r/", ignoreCase = true)) {
            cleaned = cleaned.substring(2)
        }
        return cleaned.trim('/')
    }

    fun formatFeedHeaderTitle(
        feedTarget: String,
        displayNameOverride: String? = null,
        currentUsername: String? = null
    ): String {
        if (feedTarget.equals("frontpage", ignoreCase = true)) {
            return "reddit: the front page of the internet"
        }
        val parsed = parseMultiredditPath(feedTarget)
        if (parsed != null) {
            val label = displayNameOverride?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.name
            return if (parsed.owner == null || parsed.owner.equals(currentUsername, ignoreCase = true)) {
                "/m/$label"
            } else {
                "/u/${parsed.owner}/m/$label"
            }
        }
        return "/r/$feedTarget"
    }

    fun formatFeedWebUrl(feedTarget: String, sort: String): String {
        val cleanSort = sort.trim().ifEmpty { "hot" }
        if (feedTarget.equals("frontpage", ignoreCase = true)) {
            return "https://www.reddit.com/$cleanSort"
        }
        val parsed = parseMultiredditPath(feedTarget)
        if (parsed != null) {
            return if (parsed.owner != null) {
                "https://www.reddit.com/user/${parsed.owner}/m/${parsed.name}/$cleanSort"
            } else {
                "https://www.reddit.com/me/m/${parsed.name}/$cleanSort"
            }
        }
        return "https://www.reddit.com/r/$feedTarget/$cleanSort"
    }

    /**
     * Decodes `/api/multi/mine` and `/api/multi/user/{username}` responses, supporting both
     * Reddit's top-level JSON array of `LabeledMulti` wrappers and `Listing` object envelopes.
     */
    fun parseMultiredditsJson(
        element: JsonElement,
        json: Json = RedditNetworkClient.json
    ): List<RedditMultireddit> {
        val items = mutableListOf<RedditMultireddit>()

        fun decodeEntry(entry: JsonElement) {
            val obj = entry as? JsonObject ?: return
            val decoded = if ("data" in obj) {
                runCatching {
                    json.decodeFromJsonElement(RedditMultiredditWrapper.serializer(), obj).data
                }.getOrNull()
            } else {
                runCatching {
                    json.decodeFromJsonElement(RedditMultireddit.serializer(), obj)
                }.getOrNull()
            }
            if (decoded != null && decoded.resolvedName.isNotBlank()) {
                items.add(decoded)
            }
        }

        when (element) {
            is JsonArray -> element.forEach(::decodeEntry)
            is JsonObject -> {
                val children = runCatching {
                    element["data"]?.jsonObject?.get("children")?.jsonArray
                }.getOrNull()
                if (children != null) {
                    children.forEach(::decodeEntry)
                } else if ("data" in element || "name" in element) {
                    decodeEntry(element)
                }
            }
            else -> Unit
        }

        return items
            .distinctBy { it.canonicalFeedKey.lowercase(Locale.ROOT) }
            .sortedBy { it.resolvedDisplayName.lowercase(Locale.ROOT) }
    }

    fun buildMultiModelJson(
        displayName: String,
        descriptionMd: String,
        visibility: String,
        subreddits: List<String>
    ): String {
        val normalizedVisibility = visibility.trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("private", "public", "hidden") }
            ?: "private"
        val cleanedSubs = subreddits
            .asSequence()
            .map { it.trim().trimStart('/').let { s -> if (s.startsWith("r/", ignoreCase = true)) s.substring(2) else s }.trim('/') }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()

        return buildJsonObject {
            put("display_name", displayName.trim().take(50))
            put("description_md", descriptionMd)
            put("visibility", normalizedVisibility)
            put(
                "subreddits",
                buildJsonArray {
                    cleanedSubs.forEach { sub ->
                        add(buildJsonObject { put("name", sub) })
                    }
                }
            )
        }.toString()
    }

    fun buildMultiSubredditModelJson(subredditName: String): String {
        val cleaned = subredditName.trim().trimStart('/')
            .let { s -> if (s.startsWith("r/", ignoreCase = true)) s.substring(2) else s }
            .trim('/')
        return buildJsonObject {
            put("name", cleaned)
        }.toString()
    }

    fun sanitizeMultiSlug(displayName: String): String {
        val lower = displayName.trim().lowercase(Locale.ROOT)
        val replaced = NON_SLUG_CHARS_REGEX.replace(lower, "_")
        val collapsed = MULTI_UNDERSCORE_REGEX.replace(replaced, "_").trim('_')
        return collapsed.take(50).ifEmpty { "custom_feed" }
    }
}

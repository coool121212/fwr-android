package com.fwr.app.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.network.RedditNetworkClient
import com.fwr.app.data.prefs.ExternalBrowserRule
import com.fwr.app.data.prefs.RifAppSettings
import com.fwr.app.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale

/**
 * Represents the resolved destination of a [RedditPost] link or media attachment.
 */
sealed interface ResolvedMedia {
    data class DirectImage(
        val url: String,
        val title: String = ""
    ) : ResolvedMedia

    data class Gallery(
        val imageUrls: List<String>,
        val captions: List<String?> = emptyList(),
        val title: String = "",
        val postId: String = ""
    ) : ResolvedMedia

    data class RedditVideo(
        val fallbackUrl: String,
        val dashUrl: String? = null,
        val hlsUrl: String? = null,
        val audioUrls: List<String> = emptyList(),
        val posterUrl: String? = null,
        val sourceUrl: String = fallbackUrl,
        val title: String = ""
    ) : ResolvedMedia

    data class RedgifsVideo(
        val gifId: String,
        val fallbackWebUrl: String,
        val title: String = ""
    ) : ResolvedMedia

    data class ImgurAlbum(
        val albumId: String,
        val fallbackWebUrl: String,
        val title: String = ""
    ) : ResolvedMedia

    data class ExternalWeb(
        val url: String,
        val title: String = ""
    ) : ResolvedMedia

    data object SelfPost : ResolvedMedia
}

/**
 * Represents a Reddit-native navigation destination parsed from a Reddit URL.
 */
sealed interface RedditLinkTarget {
    data class Post(
        val subreddit: String,
        val articleId: String,
        val commentId: String? = null,
        val context: Int? = null,
        val title: String = ""
    ) : RedditLinkTarget

    data class ShareLink(
        val url: String
    ) : RedditLinkTarget

    data class Subreddit(
        val subreddit: String,
        val sort: String? = null
    ) : RedditLinkTarget

    data class Multireddit(
        val owner: String?,
        val name: String,
        val sort: String? = null
    ) : RedditLinkTarget

    data class User(
        val username: String
    ) : RedditLinkTarget
}

/**
 * Resolved stream endpoints for a Reddit-hosted video (`v.redd.it` or `.gifv`).
 */
data class RedditVideoStreamInfo(
    val fallbackUrl: String,
    val dashUrl: String?,
    val hlsUrl: String?,
    val audioUrls: List<String>,
    val sourceUrl: String
)

/**
 * Media and URL router handling link classification, gallery repair, RedGIFs resolution,
 * Imgur album extraction, and Reddit media unwrapping.
 */
object MediaResolver {

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
    private val COMMENT_VIDEO_PATTERN = Regex("""https?://(?:www\.|old\.|np\.)?reddit\.com/link/([A-Za-z0-9]+)/video/([A-Za-z0-9]+)/player""")
    private val REDGIFS_URL_PATTERN = Regex("""https?://(?:[a-z0-9-]+\.)?redgifs\.com/(?:watch|ifr)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val GIPHY_URL_PATTERN = Regex("""https?://(?:media\d*\.)?giphy\.com/(?:gifs/(?:[^/]+-)?|media/)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val SUBREDDIT_SORT_SEGMENTS = setOf("hot", "new", "top", "controversial", "rising", "best")
    private val STRAY_PERCENT_REGEX = Regex("""%(?![0-9A-Fa-f]{2})""")

    private data class ParsedUrl(
        val normalizedUrl: String,
        val host: String,
        val path: String,
        val pathSegments: List<String>,
        val rawPathSegments: List<String>,
        val queryParams: Map<String, String>
    )

    private fun parseUriWithSanitization(candidate: String): URI? {
        return try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            val sanitized = STRAY_PERCENT_REGEX.replace(candidate, "%25")
                .replace(" ", "%20")
                .replace("|", "%7C")
                .replace("^", "%5E")
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace("\"", "%22")
                .replace("\\", "%5C")
            runCatching { URI(sanitized) }.getOrNull()
        }
    }

    private fun parseUrl(rawUrl: String): ParsedUrl? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        var normalizedUrl = trimmed
        var uri = parseUriWithSanitization(normalizedUrl) ?: return null
        if (uri.scheme == null && uri.host == null && uri.rawAuthority == null) {
            val firstSegment = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
            val looksLikeDomain = firstSegment.isNotEmpty() &&
                '.' in firstSegment &&
                !firstSegment.startsWith('.') &&
                !firstSegment.endsWith('.') &&
                firstSegment.none { it.isWhitespace() }
            if (looksLikeDomain) {
                val promoted = "https://$trimmed"
                val promotedUri = parseUriWithSanitization(promoted)
                if (promotedUri != null && !promotedUri.host.isNullOrBlank()) {
                    normalizedUrl = promoted
                    uri = promotedUri
                }
            }
        } else if (uri.scheme == null && !uri.rawAuthority.isNullOrBlank() && trimmed.startsWith("//")) {
            val promoted = "https:$trimmed"
            val promotedUri = parseUriWithSanitization(promoted)
            if (promotedUri != null && !promotedUri.host.isNullOrBlank()) {
                normalizedUrl = promoted
                uri = promotedUri
            }
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: uri.rawAuthority?.substringAfter('@')?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: ""
        val path = uri.path ?: uri.rawPath.orEmpty()
        val rawSegments = if (path.trim('/').isEmpty()) {
            emptyList()
        } else {
            path.trim('/').split('/')
        }
        val segments = rawSegments.filter { it.isNotEmpty() }
        val queryMap = LinkedHashMap<String, String>()
        val rawQuery = uri.rawQuery
        if (!rawQuery.isNullOrEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
                val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
                val decodedKey = runCatching {
                    URLDecoder.decode(rawKey, Charsets.UTF_8.name())
                }.getOrDefault(rawKey)
                val decodedVal = runCatching {
                    URLDecoder.decode(rawVal, Charsets.UTF_8.name())
                }.getOrDefault(rawVal)
                if (decodedKey.isNotEmpty() && decodedKey !in queryMap) {
                    queryMap[decodedKey] = decodedVal
                }
            }
        }
        return ParsedUrl(
            normalizedUrl = normalizedUrl,
            host = host,
            path = path,
            pathSegments = segments,
            rawPathSegments = rawSegments,
            queryParams = queryMap
        )
    }

    internal fun parseRedgifsTokenJson(rawJson: String): String? {
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val tokenPrimitive = root["token"] as? JsonPrimitive ?: return null
        if (!tokenPrimitive.isString) return null
        return tokenPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    internal fun parseRedgifsMp4UrlJson(rawJson: String): String? {
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val gifObj = root["gif"] as? JsonObject ?: return null
        val urlsObj = gifObj["urls"] as? JsonObject ?: return null
        val hd = (urlsObj["hd"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.startsWith("http") }
        if (hd != null) return hd
        return (urlsObj["sd"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.startsWith("http") }
    }

    internal fun parseImgurAlbumJson(rawJson: String): Pair<List<String>, List<String?>> {
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: return emptyList<String>() to emptyList()
        val dataObj = root["data"] as? JsonObject ?: return emptyList<String>() to emptyList()
        val images = dataObj["images"] as? JsonArray ?: return emptyList<String>() to emptyList()
        val urls = mutableListOf<String>()
        val descriptions = mutableListOf<String?>()
        for (element in images) {
            val img = element as? JsonObject ?: continue
            val mp4 = (img["mp4"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.startsWith("http") }
            val link = mp4
                ?: (img["link"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.startsWith("http") }
                ?: continue
            urls += link
            descriptions += (img["description"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return urls to descriptions
    }

    private val _activeInAppMedia = MutableStateFlow<ResolvedMedia?>(null)
    val activeInAppMedia: StateFlow<ResolvedMedia?> = _activeInAppMedia.asStateFlow()

    private val _activeRedditLinkTarget = MutableStateFlow<RedditLinkTarget?>(null)
    val activeRedditLinkTarget: StateFlow<RedditLinkTarget?> = _activeRedditLinkTarget.asStateFlow()

    fun dismissInAppMedia() {
        _activeInAppMedia.value = null
    }

    fun consumeRedditLinkTarget() {
        _activeRedditLinkTarget.value = null
    }

    fun emitRedditLinkTarget(target: RedditLinkTarget) {
        _activeRedditLinkTarget.value = target
    }

    /**
     * Unwraps `reddit.com/media?url=...` wrapper links.
     */
    fun unwrapRedditMediaUrl(rawUrl: String): String {
        var current = rawUrl.trim().replace("&amp;", "&")
        repeat(4) {
            val parsed = parseUrl(current) ?: return current
            val host = parsed.host.lowercase(Locale.US)
            if ((host == "reddit.com" || host.endsWith(".reddit.com")) && parsed.path == "/media") {
                val wrapped = parsed.queryParams["url"]
                if (!wrapped.isNullOrBlank()) {
                    var next = wrapped.trim().replace("&amp;", "&")
                    if (next.startsWith("http%3A", ignoreCase = true) ||
                        next.startsWith("https%3A", ignoreCase = true)
                    ) {
                        next = runCatching {
                            URLDecoder.decode(next, Charsets.UTF_8.name())
                        }.getOrDefault(next)
                    }
                    if (next != current) {
                        current = next
                        return@repeat
                    }
                }
            }
            return current
        }
        return current
    }

    /**
     * Extracts clean Imgur id when links include a title slug (`some-title-AbC123` -> `AbC123`).
     */
    fun extractImgurCleanId(rawSegment: String): String {
        val trimmed = rawSegment.substringBefore('.').trim()
        return if (trimmed.contains('-')) trimmed.substringAfterLast('-') else trimmed
    }

    /**
     * Resolves a [RedditPost] into a [ResolvedMedia] type.
     */
    fun resolve(post: RedditPost): ResolvedMedia {
        val rawBeforeUnwrap = (post.url_overridden_by_dest ?: post.url).trim().replace("&amp;", "&")
        val rawUrl = unwrapRedditMediaUrl(rawBeforeUnwrap)

        val crosspostParent = post.crosspost_parent_list.firstOrNull()
        val effectivePost = crosspostParent?.takeIf {
            !post.is_gallery &&
                post.media_metadata.isNullOrEmpty() &&
                post.secure_media?.reddit_video == null &&
                post.media?.reddit_video == null &&
                post.preview?.reddit_video_preview == null &&
                extractDirectImageUrl(post, rawUrl) == null &&
                extractRedditVideoFallbackUrl(post, rawUrl) == null
        } ?: post

        val effectiveRawUrl = if (effectivePost != post) {
            val parsedOuter = if (rawUrl.isNotBlank()) parseRedditLink(rawUrl) else null
            val pointsToParent = parsedOuter is RedditLinkTarget.Post &&
                (effectivePost.id.isBlank() || parsedOuter.articleId.equals(effectivePost.id, ignoreCase = true))
            val isRelativeRedditPermalink = rawUrl.startsWith("/r/", ignoreCase = true) ||
                rawUrl.startsWith("/comments/", ignoreCase = true)
            if (rawUrl.isBlank() || pointsToParent || isRelativeRedditPermalink) {
                unwrapRedditMediaUrl(
                    (effectivePost.url_overridden_by_dest ?: effectivePost.url).trim().replace("&amp;", "&")
                )
            } else {
                rawUrl
            }
        } else {
            rawUrl
        }

        // 1. Check for Reddit Gallery
        if (post.is_gallery ||
            !post.gallery_data?.items.isNullOrEmpty() ||
            effectivePost.is_gallery ||
            !effectivePost.gallery_data?.items.isNullOrEmpty() ||
            rawUrl.contains("/gallery/") ||
            effectiveRawUrl.contains("/gallery/")
        ) {
            val galleryEntries = extractGalleryEntries(post)
            val galleryUrls = galleryEntries.map { it.first }
            val captions = galleryEntries.map { it.second }
            val resolvedGalleryPostId = if (post.gallery_data?.items.isNullOrEmpty() &&
                post.media_metadata.isNullOrEmpty() &&
                crosspostParent != null
            ) {
                crosspostParent.id.ifBlank { post.id }
            } else {
                post.id.ifBlank { effectivePost.id }
            }
            if (galleryUrls.isNotEmpty() ||
                ((post.is_gallery || effectivePost.is_gallery) && resolvedGalleryPostId.isNotBlank())
            ) {
                return ResolvedMedia.Gallery(
                    imageUrls = galleryUrls,
                    captions = captions,
                    title = post.title,
                    postId = resolvedGalleryPostId
                )
            }
        }

        // 2. Check for RedGIFs video
        val redgifsMatch = REDGIFS_URL_PATTERN.find(effectiveRawUrl) ?: REDGIFS_URL_PATTERN.find(rawUrl)
        if (redgifsMatch != null) {
            val gifId = redgifsMatch.groupValues[1].lowercase(Locale.US)
            if (gifId.isNotBlank()) {
                val matchedUrl = if (REDGIFS_URL_PATTERN.containsMatchIn(effectiveRawUrl)) effectiveRawUrl else rawUrl
                return ResolvedMedia.RedgifsVideo(
                    gifId = gifId,
                    fallbackWebUrl = matchedUrl,
                    title = post.title
                )
            }
        }

        // 3. Check for Reddit Video (v.redd.it, comment video link, Imgur .gifv)
        val videoStreamInfo = extractRedditVideoStreamInfo(post, effectiveRawUrl)
        if (videoStreamInfo != null) {
            val poster = (post.preview ?: effectivePost.preview)?.images?.firstOrNull()?.source?.url?.replace("&amp;", "&")
                ?: post.thumbnail?.takeIf { it.startsWith("http") }
                ?: crosspostParent?.thumbnail?.takeIf { it.startsWith("http") }
            return ResolvedMedia.RedditVideo(
                fallbackUrl = videoStreamInfo.fallbackUrl,
                dashUrl = videoStreamInfo.dashUrl,
                hlsUrl = videoStreamInfo.hlsUrl,
                audioUrls = videoStreamInfo.audioUrls,
                posterUrl = poster,
                sourceUrl = videoStreamInfo.sourceUrl,
                title = post.title
            )
        }

        // 4. Check for Imgur album (/a/... or /gallery/...)
        val parsed = parseUrl(effectiveRawUrl)
        val host = parsed?.host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        val segments = parsed?.pathSegments.orEmpty()
        if ((host == "imgur.com" || host == "m.imgur.com") &&
            segments.size >= 2 &&
            segments[0] in setOf("a", "gallery")
        ) {
            val albumId = extractImgurCleanId(segments[1])
            if (albumId.isNotBlank()) {
                return ResolvedMedia.ImgurAlbum(
                    albumId = albumId,
                    fallbackWebUrl = effectiveRawUrl,
                    title = post.title
                )
            }
        }

        // 5. Check for Direct Image or Imgur single image link
        val directImageUrl = extractDirectImageUrl(post, effectiveRawUrl)
        if (directImageUrl != null) {
            return ResolvedMedia.DirectImage(
                url = directImageUrl,
                title = post.title
            )
        }

        // 6. Self Post vs External Web Link
        if (post.is_self || (effectivePost != post && effectivePost.is_self) || effectiveRawUrl.isBlank()) {
            return ResolvedMedia.SelfPost
        }

        return ResolvedMedia.ExternalWeb(
            url = effectiveRawUrl,
            title = post.title
        )
    }

    /**
     * Returns true if [post] can be displayed inside the in-app [com.fwr.app.ui.media.MediaViewerScreen].
     */
    fun hasInAppMedia(post: RedditPost): Boolean {
        return when (resolve(post)) {
            is ResolvedMedia.DirectImage,
            is ResolvedMedia.Gallery,
            is ResolvedMedia.RedditVideo,
            is ResolvedMedia.RedgifsVideo,
            is ResolvedMedia.ImgurAlbum -> true
            is ResolvedMedia.ExternalWeb,
            ResolvedMedia.SelfPost -> false
        }
    }

    /**
     * Extracts ordered full-resolution image URLs from `post.media_metadata` using `post.gallery_data.items`.
     * Repairs `{"status":"failed"}` entries by falling back to `https://i.redd.it/{media_id}.jpg`
     * (MojiRS/GalleryRepair). Also inspects `crosspost_parent_list` when present.
     */
    private fun extractGalleryEntries(post: RedditPost): List<Pair<String, String?>> {
        val targetPost = if (post.gallery_data?.items.isNullOrEmpty() &&
            post.media_metadata.isNullOrEmpty() &&
            post.crosspost_parent_list.isNotEmpty()
        ) {
            post.crosspost_parent_list.first()
        } else {
            post
        }
        val metadataMap = targetPost.media_metadata
        val itemsById = targetPost.gallery_data?.items?.associateBy { it.media_id }.orEmpty()
        val galleryIds = targetPost.gallery_data?.items?.map { it.media_id }.orEmpty()
        val extraKeys = metadataMap?.keys?.filter { it !in galleryIds }.orEmpty()
        val orderedIds = (galleryIds + extraKeys).distinct()

        return orderedIds.mapNotNull { mediaId ->
            val meta = metadataMap?.get(mediaId)
            val raw = meta?.s?.u
                ?: meta?.s?.gif
                ?: meta?.p?.lastOrNull()?.u
            val clean = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
            val resolvedUrl = if (clean != null) {
                clean
            } else if (mediaId.matches(Regex("[A-Za-z0-9]+"))) {
                // MojiRS GalleryRepair fallback when Reddit returns {"status":"failed"} with no size
                "https://i.redd.it/$mediaId.jpg"
            } else {
                null
            }
            resolvedUrl?.let { it to itemsById[mediaId]?.caption }
        }
    }

    fun extractGalleryUrls(post: RedditPost): List<String> =
        extractGalleryEntries(post).map { it.first }

    fun extractGalleryCaptions(post: RedditPost): List<String?> =
        extractGalleryEntries(post).map { it.second }

    /**
     * Extracts [RedditVideoStreamInfo] (`fallbackUrl`, `dashUrl`, `hlsUrl`, `audioUrls`, `sourceUrl`)
     * for Reddit videos so ExoPlayer can play DASH/HLS audio reliably.
     */
    fun extractRedditVideoStreamInfo(
        post: RedditPost,
        rawUrl: String = post.url
    ): RedditVideoStreamInfo? {
        val crosspost = post.crosspost_parent_list.firstOrNull()
        val videoInfo = post.secure_media?.reddit_video
            ?: post.media?.reddit_video
            ?: post.preview?.reddit_video_preview
            ?: crosspost?.secure_media?.reddit_video
            ?: crosspost?.media?.reddit_video
            ?: crosspost?.preview?.reddit_video_preview

        val fallbackUrl = extractRedditVideoFallbackUrl(post, rawUrl)
            ?: crosspost?.let { extractRedditVideoFallbackUrl(it, it.url_overridden_by_dest ?: it.url) }
            ?: return null

        val explicitDash = videoInfo?.dash_url?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
        val explicitHls = videoInfo?.hls_url?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
        val isVReddit = fallbackUrl.contains("v.redd.it", ignoreCase = true)

        val baseVRedditUrl = if (isVReddit) {
            fallbackUrl.substringBefore('?').substringBeforeLast('/')
        } else {
            null
        }
        val dashUrl = explicitDash ?: baseVRedditUrl?.let { "$it/DASHPlaylist.mpd" }
        val hlsUrl = explicitHls ?: baseVRedditUrl?.let { "$it/HLSPlaylist.m3u8" }
        val audioUrls = if (baseVRedditUrl != null && videoInfo?.is_gif != true) {
            listOf(
                "$baseVRedditUrl/DASH_AUDIO_128.mp4",
                "$baseVRedditUrl/DASH_AUDIO_64.mp4",
                "$baseVRedditUrl/DASH_audio_128.mp4",
                "$baseVRedditUrl/DASH_audio_64.mp4",
                "$baseVRedditUrl/DASH_audio.mp4",
                "$baseVRedditUrl/audio",
                "$baseVRedditUrl/audio.mp4",
                "$baseVRedditUrl/HLS_AUDIO_160_K.aac",
                "$baseVRedditUrl/HLS_AUDIO_128.aac",
                "$baseVRedditUrl/HLS_AUDIO_64_K.aac"
            )
        } else {
            emptyList()
        }
        val sourceUrl = rawUrl.trim().ifBlank { baseVRedditUrl ?: fallbackUrl }
        return RedditVideoStreamInfo(
            fallbackUrl = fallbackUrl,
            dashUrl = dashUrl,
            hlsUrl = hlsUrl,
            audioUrls = audioUrls,
            sourceUrl = sourceUrl
        )
    }

    /**
     * Extracts the MP4 `fallback_url` for `v.redd.it` videos, Reddit comment video links,
     * or Imgur `.gifv` links.
     */
    fun extractRedditVideoFallbackUrl(post: RedditPost, rawUrl: String = post.url): String? {
        val crosspost = post.crosspost_parent_list.firstOrNull()
        val videoInfo = post.secure_media?.reddit_video
            ?: post.media?.reddit_video
            ?: post.preview?.reddit_video_preview
            ?: crosspost?.secure_media?.reddit_video
            ?: crosspost?.media?.reddit_video
            ?: crosspost?.preview?.reddit_video_preview

        val explicitFallback = videoInfo?.fallback_url?.trim()?.replace("&amp;", "&")
        if (!explicitFallback.isNullOrEmpty() && explicitFallback.startsWith("http")) {
            return explicitFallback
        }

        // MojiRS FixCommentVideoLinks: reddit.com/link/{id}/video/{mediaId}/player
        val commentVideoMatch = COMMENT_VIDEO_PATTERN.find(rawUrl)
        if (commentVideoMatch != null) {
            val contentId = commentVideoMatch.groupValues[1]
            val mediaId = commentVideoMatch.groupValues[2]
            return "https://v.redd.it/link/$contentId/asset/$mediaId/DASH_720.mp4"
        }

        val parsed = parseUrl(rawUrl)
        val host = parsed?.host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        val pathLower = parsed?.path?.lowercase(Locale.US).orEmpty()

        if ((host == "imgur.com" || host == "m.imgur.com" || host == "i.imgur.com") &&
            (pathLower.endsWith(".gifv") || pathLower.endsWith(".mp4"))
        ) {
            val lastSeg = parsed?.pathSegments?.lastOrNull().orEmpty()
            val cleanId = extractImgurCleanId(lastSeg)
            if (cleanId.isNotBlank()) {
                return "https://i.imgur.com/$cleanId.mp4"
            }
        }

        if (host == "v.redd.it" || post.is_video) {
            val videoId = parsed?.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() }
            if (videoId != null) {
                return "https://v.redd.it/$videoId/DASH_720.mp4"
            }
        }

        return null
    }

    /**
     * Determines if a post URL points to a direct image or single Imgur image and returns the full-res URL.
     */
    fun extractDirectImageUrl(post: RedditPost, rawUrl: String = post.url_overridden_by_dest ?: post.url): String? {
        val unwrapped = unwrapRedditMediaUrl(rawUrl)
        if (unwrapped.isNotBlank()) {
            val parsed = parseUrl(unwrapped)
            if (parsed != null) {
                val host = parsed.host.lowercase(Locale.US).removePrefix("www.")
                val pathLower = parsed.path.lowercase(Locale.US)

                // Direct image extension (.jpg, .jpeg, .png, .webp, .gif)
                if (IMAGE_EXTENSIONS.any { pathLower.endsWith(it) }) {
                    return parsed.normalizedUrl
                }

                // Direct Reddit image hosts
                if (host == "i.redd.it" || host == "preview.redd.it") {
                    return parsed.normalizedUrl
                }

                // Imgur single-image links (supporting title-slug links like imgur.com/some-title-abcXYZ)
                if ((host == "imgur.com" || host == "m.imgur.com" || host == "i.imgur.com") &&
                    !pathLower.endsWith(".mp4") &&
                    !pathLower.endsWith(".gifv") &&
                    !pathLower.endsWith(".webm")
                ) {
                    val segments = parsed.pathSegments
                    if (segments.size == 1) {
                        val imageHash = extractImgurCleanId(segments[0])
                        if (imageHash.isNotBlank() && imageHash !in setOf("a", "gallery", "t", "r")) {
                            return "https://i.imgur.com/$imageHash.jpg"
                        }
                    }
                }
            }
        }

        // If Reddit post_hint is explicitly "image", use preview source URL
        if (post.post_hint == "image") {
            val previewSource = post.preview?.images?.firstOrNull()?.source?.url?.replace("&amp;", "&")
            if (!previewSource.isNullOrBlank() && previewSource.startsWith("http")) {
                return previewSource
            }
        }

        val crosspost = post.crosspost_parent_list.firstOrNull()
        if (crosspost != null) {
            val crossUrl = crosspost.url_overridden_by_dest ?: crosspost.url
            return extractDirectImageUrl(crosspost, crossUrl)
        }

        return null
    }

    /**
     * Classifies a standalone URL (from a comment link, inline image, selftext, or post)
     * into an in-app [ResolvedMedia] destination.
     */
    fun resolveUrl(rawUrl: String, title: String = ""): ResolvedMedia {
        val unwrapped = unwrapRedditMediaUrl(rawUrl)
        if (unwrapped.isBlank()) return ResolvedMedia.ExternalWeb(url = rawUrl, title = title)

        GIPHY_URL_PATTERN.find(unwrapped)?.let { giphyMatch ->
            val giphyId = giphyMatch.groupValues[1]
            if (giphyId.isNotBlank()) {
                return ResolvedMedia.DirectImage(
                    url = "https://i.giphy.com/media/$giphyId/giphy.gif",
                    title = title
                )
            }
        }

        REDGIFS_URL_PATTERN.find(unwrapped)?.let { rgMatch ->
            val gifId = rgMatch.groupValues[1].lowercase(Locale.US)
            if (gifId.isNotBlank()) {
                return ResolvedMedia.RedgifsVideo(
                    gifId = gifId,
                    fallbackWebUrl = unwrapped,
                    title = title
                )
            }
        }

        val dummyPost = RedditPost(url = unwrapped, title = title)
        extractRedditVideoStreamInfo(dummyPost, unwrapped)?.let { info ->
            return ResolvedMedia.RedditVideo(
                fallbackUrl = info.fallbackUrl,
                dashUrl = info.dashUrl,
                hlsUrl = info.hlsUrl,
                audioUrls = info.audioUrls,
                sourceUrl = info.sourceUrl,
                title = title
            )
        }

        val parsed = parseUrl(unwrapped)
        val host = parsed?.host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        val segments = parsed?.pathSegments.orEmpty()
        if ((host == "imgur.com" || host == "m.imgur.com") &&
            segments.size >= 2 &&
            segments[0] in setOf("a", "gallery")
        ) {
            val albumId = extractImgurCleanId(segments[1])
            if (albumId.isNotBlank()) {
                return ResolvedMedia.ImgurAlbum(
                    albumId = albumId,
                    fallbackWebUrl = parsed?.normalizedUrl ?: unwrapped,
                    title = title
                )
            }
        }

        extractDirectImageUrl(dummyPost, unwrapped)?.let { imageUrl ->
            return ResolvedMedia.DirectImage(url = imageUrl, title = title)
        }

        return ResolvedMedia.ExternalWeb(url = unwrapped, title = title)
    }

    /**
     * Parses a Reddit URL (`reddit.com` / `redd.it`) into an in-app [RedditLinkTarget],
     * or returns `null` if the URL is not a native Reddit post, share link, subreddit, or user link.
     */
    fun parseRedditLink(rawUrl: String): RedditLinkTarget? {
        var unwrapped = unwrapRedditMediaUrl(rawUrl)
        if (unwrapped.isBlank()) return null
        if (unwrapped.startsWith("/") && !unwrapped.startsWith("//") && (
                unwrapped.startsWith("/r/", ignoreCase = true) ||
                    unwrapped.startsWith("/u/", ignoreCase = true) ||
                    unwrapped.startsWith("/user/", ignoreCase = true) ||
                    unwrapped.startsWith("/me/m/", ignoreCase = true) ||
                    unwrapped.startsWith("/comments/", ignoreCase = true)
                )
        ) {
            unwrapped = "https://www.reddit.com$unwrapped"
        }
        val parsed = parseUrl(unwrapped) ?: return null
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.").takeIf { it.isNotEmpty() } ?: return null
        val segments = parsed.pathSegments
        val rawSegments = parsed.rawPathSegments

        if (host == "redd.it") {
            val articleId = segments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return RedditLinkTarget.Post(subreddit = "", articleId = articleId)
        }

        if (host != "reddit.com" && !host.endsWith(".reddit.com")) {
            return null
        }

        // /r/{sub}/s/{shareId}, /u/{user}/s/{shareId}, or /s/{shareId}
        if ((segments.size >= 4 &&
                (segments[0].equals("r", ignoreCase = true) ||
                    segments[0].equals("u", ignoreCase = true) ||
                    segments[0].equals("user", ignoreCase = true)) &&
                segments[2].equals("s", ignoreCase = true)) ||
            (segments.size >= 2 && segments[0].equals("s", ignoreCase = true))
        ) {
            return RedditLinkTarget.ShareLink(url = parsed.normalizedUrl)
        }

        // /r/{sub}/comments/{articleId}(/{slug}(/{commentId}))
        if (rawSegments.size >= 4 &&
            rawSegments[0].equals("r", ignoreCase = true) &&
            rawSegments[2].equals("comments", ignoreCase = true)
        ) {
            val sub = rawSegments[1]
            val articleId = rawSegments[3]
            if (sub.isNotBlank() && articleId.isNotBlank()) {
                val commentId = rawSegments.getOrNull(5)?.takeIf { it.isNotBlank() }
                val contextDepth = if (commentId != null) {
                    parsed.queryParams["context"]?.toIntOrNull()?.coerceAtLeast(0)
                } else {
                    null
                }
                return RedditLinkTarget.Post(
                    subreddit = sub,
                    articleId = articleId,
                    commentId = commentId,
                    context = contextDepth
                )
            }
        }

        // /comments/{articleId}(/{slug}(/{commentId}))
        if (rawSegments.size >= 2 && rawSegments[0].equals("comments", ignoreCase = true)) {
            val articleId = rawSegments[1]
            if (articleId.isNotBlank()) {
                val commentId = rawSegments.getOrNull(3)?.takeIf { it.isNotBlank() }
                val contextDepth = if (commentId != null) {
                    parsed.queryParams["context"]?.toIntOrNull()?.coerceAtLeast(0)
                } else {
                    null
                }
                return RedditLinkTarget.Post(
                    subreddit = "",
                    articleId = articleId,
                    commentId = commentId,
                    context = contextDepth
                )
            }
        }

        // /r/{sub} or /r/{sub}/{sort}
        if (segments.size in 2..3 && segments[0].equals("r", ignoreCase = true)) {
            val sub = segments[1]
            val third = segments.getOrNull(2)?.lowercase(Locale.US)
            if (sub.isNotBlank() && (third == null || third in SUBREDDIT_SORT_SEGMENTS)) {
                return RedditLinkTarget.Subreddit(subreddit = sub, sort = third)
            }
        }

        // /user/{owner}/m/{multi}(/{sort}) or /u/{owner}/m/{multi}(/{sort})
        if (segments.size >= 4 &&
            (segments[0].equals("user", ignoreCase = true) || segments[0].equals("u", ignoreCase = true)) &&
            segments[2].equals("m", ignoreCase = true)
        ) {
            val owner = segments[1]
            val multiName = segments[3]
            val sort = segments.getOrNull(4)?.lowercase(Locale.US)
            if (segments.size <= 5 &&
                owner.isNotBlank() &&
                multiName.isNotBlank() &&
                (sort == null || sort in SUBREDDIT_SORT_SEGMENTS)
            ) {
                return RedditLinkTarget.Multireddit(owner = owner, name = multiName, sort = sort)
            }
            return null
        }

        // /me/m/{multi}(/{sort})
        if (segments.size >= 3 &&
            segments[0].equals("me", ignoreCase = true) &&
            segments[1].equals("m", ignoreCase = true)
        ) {
            val multiName = segments[2]
            val sort = segments.getOrNull(3)?.lowercase(Locale.US)
            if (segments.size <= 4 &&
                multiName.isNotBlank() &&
                (sort == null || sort in SUBREDDIT_SORT_SEGMENTS)
            ) {
                return RedditLinkTarget.Multireddit(owner = null, name = multiName, sort = sort)
            }
            return null
        }

        // /u/{username} or /user/{username}
        if (segments.size >= 2 &&
            (segments[0].equals("u", ignoreCase = true) || segments[0].equals("user", ignoreCase = true))
        ) {
            val username = segments[1]
            if (username.isNotBlank()) {
                return RedditLinkTarget.User(username = username)
            }
        }

        return null
    }

    /**
     * Returns the best matching enabled [ExternalBrowserRule] for [rawUrl], or `null` if none match.
     * Prefers `alwaysExternal` rules, then longest host match.
     */
    fun findMatchingExternalBrowserRule(
        rawUrl: String,
        rules: List<ExternalBrowserRule>
    ): ExternalBrowserRule? {
        if (rules.isEmpty() || rawUrl.isBlank()) return null
        val parsed = parseUrl(rawUrl) ?: return null
        val urlHost = parsed.host.trim().lowercase(Locale.ROOT).trimEnd('.')
        if (urlHost.isEmpty()) return null

        var bestMatch: ExternalBrowserRule? = null
        for (rule in rules) {
            if (!rule.enabled) continue
            val ruleHost = UserPreferencesRepository.normalizeExternalHost(rule.host)
            if (ruleHost.isEmpty()) continue
            val matches = if (rule.includeSubdomains) {
                urlHost == ruleHost || urlHost.endsWith(".$ruleHost")
            } else {
                urlHost == ruleHost
            }
            if (!matches) continue
            val currentBest = bestMatch
            if (currentBest == null) {
                bestMatch = rule
            } else {
                val bestHostLen = UserPreferencesRepository.normalizeExternalHost(currentBest.host).length
                val betterAlways = rule.alwaysExternal && !currentBest.alwaysExternal
                val sameAlwaysLongerHost = (rule.alwaysExternal == currentBest.alwaysExternal) &&
                    ruleHost.length > bestHostLen
                if (betterAlways || sameAlwaysLongerHost) {
                    bestMatch = rule
                }
            }
        }
        return bestMatch
    }

    /**
     * Routes a clicked URL using configured external domain rules, native in-app navigation
     * for Reddit links, or FWR's in-app media viewer / built-in browser by default.
     */
    fun openExternalLink(context: Context, url: String) {
        val sanitized = url.trim().replace("&amp;", "&")
        if (sanitized.isEmpty()) return
        val prefsRepo = runCatching { UserPreferencesRepository.getInstance(context) }.getOrNull()
        val rules = prefsRepo?.externalBrowserRules?.value.orEmpty()
        val preferExternal = prefsRepo?.appSettings?.value?.preferExternalBrowser ?: false
        val matchingRule = findMatchingExternalBrowserRule(sanitized, rules)
        if (matchingRule != null && matchingRule.alwaysExternal) {
            openInExternalApp(context, sanitized)
            return
        }
        parseRedditLink(sanitized)?.let { redditTarget ->
            _activeRedditLinkTarget.value = redditTarget
            return
        }
        val resolved = resolveUrl(sanitized)
        if (resolved is ResolvedMedia.SelfPost) return
        if (resolved is ResolvedMedia.ExternalWeb && (matchingRule != null || preferExternal)) {
            openInExternalApp(context, sanitized)
            return
        }
        _activeInAppMedia.value = resolved
    }

    /**
     * Launches a URL via `Intent.ACTION_VIEW` so native Android app links (e.g., YouTube)
     * or the default external browser handle it instead of Chrome Custom Tabs or in-app WebView.
     */
    fun openInExternalApp(context: Context, url: String) {
        val sanitized = url.trim().replace("&amp;", "&")
        if (sanitized.isEmpty()) return
        val effectiveUrl = parseUrl(sanitized)?.normalizedUrl ?: sanitized
        val uri = runCatching { Uri.parse(effectiveUrl) }.getOrNull() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            openSystemBrowser(context, effectiveUrl)
        }
    }

    fun isCurrentlyOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Computes `(suggestedFileName, mimeType)` for saving a media URL.
     */
    fun suggestMediaFileNameAndMime(url: String, title: String = ""): Pair<String, String> {
        val sanitized = url.trim().replace("&amp;", "&")
        val uri = parseUrl(sanitized)
        val pathLower = uri?.path?.lowercase(Locale.US).orEmpty()
        val (ext, mime) = when {
            pathLower.endsWith(".mp4") -> "mp4" to "video/mp4"
            pathLower.endsWith(".gif") -> "gif" to "image/gif"
            pathLower.endsWith(".png") -> "png" to "image/png"
            pathLower.endsWith(".webp") -> "webp" to "image/webp"
            pathLower.endsWith(".jpeg") || pathLower.endsWith(".jpg") -> "jpg" to "image/jpeg"
            sanitized.contains("v.redd.it", ignoreCase = true) ||
                sanitized.contains("redgifs", ignoreCase = true) -> "mp4" to "video/mp4"
            else -> "jpg" to "image/jpeg"
        }
        val safeBase = title
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "reddit_media" }
        val fileName = "${safeBase}_${System.currentTimeMillis() % 100000}.$ext"
        return fileName to mime
    }

    /**
     * Streams a remote media file directly to a user-selected Storage Access Framework [Uri].
     */
    suspend fun saveMediaToUri(context: Context, url: String, targetUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runSuspendCatching {
                val sanitized = url.trim().replace("&amp;", "&")
                if (sanitized.isEmpty()) error("Empty media URL")
                val request = Request.Builder()
                    .url(sanitized)
                    .header("User-Agent", RedditNetworkClient.CUSTOM_USER_AGENT)
                    .build()
                RedditNetworkClient.mediaHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code} downloading media")
                    }
                    val body = response.body ?: error("Empty response body")
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        body.byteStream().copyTo(out)
                    } ?: error("Unable to open chosen destination")
                }
                Unit
            }
        }

    /**
     * Downloads an image or video URL to Android's Downloads folder via [DownloadManager].
     */
    fun downloadMedia(context: Context, url: String, title: String = "", subFolder: String? = null) {
        val sanitized = url.trim().replace("&amp;", "&")
        if (sanitized.isEmpty()) return
        val uri = runCatching { Uri.parse(sanitized) }.getOrNull() ?: return
        val (fileName, _) = suggestMediaFileNameAndMime(sanitized, title)
        val relativeDest = if (!subFolder.isNullOrBlank()) {
            val cleanSub = subFolder.trim('/').replace(Regex("[^a-zA-Z0-9._/-]"), "_")
            "$cleanSub/$fileName"
        } else {
            fileName
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            Toast.makeText(context, "Download manager unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val request = DownloadManager.Request(uri).apply {
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setTitle(fileName)
                setDescription("Downloading Reddit media")
                addRequestHeader("User-Agent", RedditNetworkClient.CUSTOM_USER_AGENT)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                try {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relativeDest)
                } catch (_: SecurityException) {
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                }
            }
            downloadManager.enqueue(request)
            Toast.makeText(context, "Downloading $fileName…", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Download failed: ${err.localizedMessage ?: "Error"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Opens a URL explicitly in the system external browser or Chrome Custom Tabs.
     */
    fun openSystemBrowser(context: Context, url: String) {
        val sanitized = url.trim().replace("&amp;", "&")
        if (sanitized.isEmpty()) return
        val uri = runCatching { Uri.parse(sanitized) }.getOrNull() ?: return

        runCatching {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, uri)
        }.onFailure {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}

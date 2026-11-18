package com.fwr.app.util

import com.fwr.app.data.model.GalleryData
import com.fwr.app.data.model.GalleryItem
import com.fwr.app.data.model.MediaImageSource
import com.fwr.app.data.model.MediaMetadata
import com.fwr.app.data.model.RedditMedia
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.model.RedditPreview
import com.fwr.app.data.model.RedditPreviewImage
import com.fwr.app.data.model.RedditPreviewSource
import com.fwr.app.data.model.RedditVideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResolverTest {

    @Test
    fun unwrapRedditMediaUrl_unwrapsMediaWrapperAndPreservesOthers() {
        val wrapped = "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc.png&amp;foo=bar"
        assertEquals("https://i.redd.it/abc.png", MediaResolver.unwrapRedditMediaUrl(wrapped))

        val normal = "https://i.redd.it/xyz.jpg"
        assertEquals(normal, MediaResolver.unwrapRedditMediaUrl(normal))
    }

    @Test
    fun extractImgurCleanId_stripsExtensionAndSlugPrefix() {
        assertEquals("AbC123", MediaResolver.extractImgurCleanId("my-cool-title-AbC123.gifv"))
        assertEquals("xyz789", MediaResolver.extractImgurCleanId("xyz789.jpg"))
        assertEquals("plainId", MediaResolver.extractImgurCleanId("plainId"))
    }

    @Test
    fun extractGalleryUrlsAndCaptions_repairsFailedEntriesAndUsesCrosspost() {
        val crosspost = RedditPost(
            id = "parent1",
            is_gallery = true,
            gallery_data = GalleryData(
                items = listOf(
                    GalleryItem(media_id = "imgA", caption = "First"),
                    GalleryItem(media_id = "failed123", caption = "Repaired")
                )
            ),
            media_metadata = mapOf(
                "imgA" to MediaMetadata(s = MediaImageSource(u = "https://preview.redd.it/a.jpg&amp;w=100")),
                "failed123" to MediaMetadata(status = "failed", s = null),
                "extraB" to MediaMetadata(p = listOf(MediaImageSource(u = "https://preview.redd.it/b.png")))
            )
        )
        val post = RedditPost(
            id = "child1",
            title = "Crossposted Gallery",
            is_gallery = true,
            crosspost_parent_list = listOf(crosspost)
        )

        val urls = MediaResolver.extractGalleryUrls(post)
        val captions = MediaResolver.extractGalleryCaptions(post)

        assertEquals(
            listOf(
                "https://preview.redd.it/a.jpg&w=100",
                "https://i.redd.it/failed123.jpg",
                "https://preview.redd.it/b.png"
            ),
            urls
        )
        assertEquals(listOf("First", "Repaired", null), captions)
    }

    @Test
    fun resolvePost_handlesGalleryRedgifsVideoImgurAlbumDirectImageSelfAndExternal() {
        val gallery = MediaResolver.resolve(
            RedditPost(id = "g1", title = "Empty failed gallery", is_gallery = true, url = "https://www.reddit.com/gallery/g1")
        )
        assertTrue(gallery is ResolvedMedia.Gallery)
        assertEquals("g1", (gallery as ResolvedMedia.Gallery).postId)

        val redgifs = MediaResolver.resolve(
            RedditPost(id = "r1", title = "RG", url = "https://www.redgifs.com/watch/FooBar")
        )
        assertEquals(
            ResolvedMedia.RedgifsVideo(
                gifId = "foobar",
                fallbackWebUrl = "https://www.redgifs.com/watch/FooBar",
                title = "RG"
            ),
            redgifs
        )

        val video = MediaResolver.resolve(
            RedditPost(
                id = "v1",
                title = "Vid",
                is_video = true,
                url = "https://v.redd.it/vid123",
                secure_media = RedditMedia(
                    reddit_video = RedditVideoInfo(
                        fallback_url = "https://v.redd.it/vid123/DASH_720.mp4?source=fallback"
                    )
                )
            )
        )
        assertTrue(video is ResolvedMedia.RedditVideo)
        val rv = video as ResolvedMedia.RedditVideo
        assertEquals("https://v.redd.it/vid123/DASH_720.mp4?source=fallback", rv.fallbackUrl)
        assertEquals("https://v.redd.it/vid123/DASHPlaylist.mpd", rv.dashUrl)
        assertEquals("https://v.redd.it/vid123/HLSPlaylist.m3u8", rv.hlsUrl)

        val album = MediaResolver.resolve(
            RedditPost(id = "a1", title = "Album", url = "https://imgur.com/a/cool-slug-AbC99")
        )
        assertEquals(
            ResolvedMedia.ImgurAlbum(
                albumId = "AbC99",
                fallbackWebUrl = "https://imgur.com/a/cool-slug-AbC99",
                title = "Album"
            ),
            album
        )

        val singleImgur = MediaResolver.resolve(
            RedditPost(id = "i1", title = "Img", url = "https://imgur.com/my-pic-XyZ12")
        )
        assertEquals(ResolvedMedia.DirectImage("https://i.imgur.com/XyZ12.jpg", "Img"), singleImgur)

        val previewHint = MediaResolver.resolve(
            RedditPost(
                id = "p1",
                title = "Preview",
                post_hint = "image",
                url = "https://example.com/page",
                preview = RedditPreview(
                    images = listOf(RedditPreviewImage(source = RedditPreviewSource(url = "https://i.redd.it/prev.jpg&amp;a=1")))
                )
            )
        )
        assertEquals(ResolvedMedia.DirectImage("https://i.redd.it/prev.jpg&a=1", "Preview"), previewHint)

        assertEquals(ResolvedMedia.SelfPost, MediaResolver.resolve(RedditPost(id = "s1", is_self = true)))
        assertEquals(
            ResolvedMedia.ExternalWeb("https://kotlinlang.org", "Kotlin"),
            MediaResolver.resolve(RedditPost(id = "e1", title = "Kotlin", url = "https://kotlinlang.org"))
        )
    }

    @Test
    fun resolveUrl_handlesGiphyCommentVideoGifvAndDirectImages() {
        assertEquals(
            ResolvedMedia.DirectImage("https://i.giphy.com/media/12345/giphy.gif", "G"),
            MediaResolver.resolveUrl("https://giphy.com/gifs/funny-cat-12345", "G")
        )
        val commentVid = MediaResolver.resolveUrl("https://www.reddit.com/link/abc12/video/vid99/player")
        assertTrue(commentVid is ResolvedMedia.RedditVideo)
        assertEquals("https://v.redd.it/link/abc12/asset/vid99/DASH_720.mp4", (commentVid as ResolvedMedia.RedditVideo).fallbackUrl)

        val gifv = MediaResolver.resolveUrl("https://i.imgur.com/sample1.gifv")
        assertTrue(gifv is ResolvedMedia.RedditVideo)
        assertEquals("https://i.imgur.com/sample1.mp4", (gifv as ResolvedMedia.RedditVideo).fallbackUrl)
    }

    @Test
    fun parseRedditLink_handlesAllSupportedFormsAndRejectsInvalid() {
        assertEquals(
            RedditLinkTarget.Post(subreddit = "", articleId = "abc123"),
            MediaResolver.parseRedditLink("https://redd.it/abc123")
        )
        assertEquals(
            RedditLinkTarget.ShareLink("https://www.reddit.com/r/kotlin/s/share99"),
            MediaResolver.parseRedditLink("https://www.reddit.com/r/kotlin/s/share99")
        )
        assertEquals(
            RedditLinkTarget.ShareLink("https://www.reddit.com/s/share88"),
            MediaResolver.parseRedditLink("https://www.reddit.com/s/share88")
        )
        assertEquals(
            RedditLinkTarget.Post(subreddit = "android", articleId = "post1", commentId = "comm2"),
            MediaResolver.parseRedditLink("https://old.reddit.com/r/android/comments/post1/my_slug/comm2")
        )
        assertEquals(
            RedditLinkTarget.Post(subreddit = "", articleId = "post1", commentId = "comm2"),
            MediaResolver.parseRedditLink("https://www.reddit.com/comments/post1/my_slug/comm2")
        )
        assertEquals(
            RedditLinkTarget.Subreddit(subreddit = "kotlin", sort = "top"),
            MediaResolver.parseRedditLink("https://www.reddit.com/r/kotlin/top")
        )
        assertNull(MediaResolver.parseRedditLink("https://www.reddit.com/r/kotlin/wiki"))
        assertEquals(
            RedditLinkTarget.User(username = "spez"),
            MediaResolver.parseRedditLink("https://www.reddit.com/u/spez")
        )
        assertEquals(
            RedditLinkTarget.User(username = "spez"),
            MediaResolver.parseRedditLink("https://www.reddit.com/user/spez")
        )
        assertNull(MediaResolver.parseRedditLink("https://example.com/r/kotlin"))
    }

    @Test
    fun suggestMediaFileNameAndMime_detectsTypesAndSanitizesTitle() {
        val (mp4Name, mp4Mime) = MediaResolver.suggestMediaFileNameAndMime("https://v.redd.it/abc/DASH_720.mp4", "My Video: Test!")
        assertTrue(mp4Name.startsWith("My_Video__Test_"))
        assertTrue(mp4Name.endsWith(".mp4"))
        assertEquals("video/mp4", mp4Mime)

        val (pngName, pngMime) = MediaResolver.suggestMediaFileNameAndMime("https://i.redd.it/pic.png?width=500", "A".repeat(80))
        assertEquals("image/png", pngMime)
        assertTrue(pngName.endsWith(".png"))
        assertTrue(pngName.substringBeforeLast('_').length <= 48)

        val (_, fallbackMime) = MediaResolver.suggestMediaFileNameAndMime("https://example.com/unknown")
        assertEquals("image/jpeg", fallbackMime)
    }

    @Test
    fun parseRedgifsAndImgurJsonHelpers_parseSafely() {
        assertEquals("tok_abc", MediaResolver.parseRedgifsTokenJson("""{"token":"tok_abc"}"""))
        assertNull(MediaResolver.parseRedgifsTokenJson("""{"token":""}"""))
        assertNull(MediaResolver.parseRedgifsTokenJson("not-json"))

        assertEquals(
            "https://media.redgifs.com/hd.mp4",
            MediaResolver.parseRedgifsMp4UrlJson(
                """{"gif":{"urls":{"hd":"https://media.redgifs.com/hd.mp4","sd":"https://media.redgifs.com/sd.mp4"}}}"""
            )
        )
        assertEquals(
            "https://media.redgifs.com/sd.mp4",
            MediaResolver.parseRedgifsMp4UrlJson(
                """{"gif":{"urls":{"hd":"ftp://bad","sd":"https://media.redgifs.com/sd.mp4"}}}"""
            )
        )
        assertNull(MediaResolver.parseRedgifsMp4UrlJson("""{"gif":{}}"""))

        val (urls, descs) = MediaResolver.parseImgurAlbumJson(
            """
            {
              "data": {
                "images": [
                  {"mp4":"https://i.imgur.com/a.mp4","link":"https://i.imgur.com/a.gif","description":"Clip 1"},
                  {"link":"https://i.imgur.com/b.jpg","description":"   "},
                  {"link":"ftp://ignored"}
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals(listOf("https://i.imgur.com/a.mp4", "https://i.imgur.com/b.jpg"), urls)
        assertEquals(listOf("Clip 1", null), descs)
        assertEquals(emptyList<String>() to emptyList<String?>(), MediaResolver.parseImgurAlbumJson("{bad"))
    }

    @Test
    fun extractGalleryUrlsAndCaptions_stayAlignedWhenNonAlphanumericFailedEntryDropped() {
        val post = RedditPost(
            id = "gal1",
            is_gallery = true,
            gallery_data = GalleryData(
                items = listOf(
                    GalleryItem(media_id = "bad-id!", caption = "Dropped caption"),
                    GalleryItem(media_id = "ok123", caption = "Real caption")
                )
            ),
            media_metadata = mapOf(
                "bad-id!" to MediaMetadata(status = "failed", s = null),
                "ok123" to MediaMetadata(s = MediaImageSource(u = "https://i.redd.it/ok123.jpg"))
            )
        )

        val urls = MediaResolver.extractGalleryUrls(post)
        val captions = MediaResolver.extractGalleryCaptions(post)
        assertEquals(listOf("https://i.redd.it/ok123.jpg"), urls)
        assertEquals(listOf("Real caption"), captions)

        val resolved = MediaResolver.resolve(post) as ResolvedMedia.Gallery
        assertEquals(urls, resolved.imageUrls)
        assertEquals(captions, resolved.captions)
    }

    @Test
    fun unwrapRedditMediaUrl_rejectsLookalikeDomainsAndHandlesNestedDoubleEncodedAndBadPercent() {
        val lookalike = "https://notreddit.com/media?url=https://evil.com/payload"
        assertEquals(lookalike, MediaResolver.unwrapRedditMediaUrl(lookalike))

        val nested = "https://www.reddit.com/media?url=https%3A%2F%2Fwww.reddit.com%2Fmedia%3Furl%3Dhttps%253A%252F%252Fi.redd.it%252Fabc.png"
        assertEquals("https://i.redd.it/abc.png", MediaResolver.unwrapRedditMediaUrl(nested))

        val doubleEncoded = "https://www.reddit.com/media?url=https%253A%252F%252Fi.redd.it%252Fabc.png"
        assertEquals("https://i.redd.it/abc.png", MediaResolver.unwrapRedditMediaUrl(doubleEncoded))

        val badPercent = "https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc.png&bad=%ZZ"
        assertEquals("https://i.redd.it/abc.png", MediaResolver.unwrapRedditMediaUrl(badPercent))
    }

    @Test
    fun schemelessUrlsAndImgurMp4AndEmptySlugCommentLinksResolveCorrectly() {
        val directNoScheme = MediaResolver.resolveUrl("i.redd.it/abc")
        assertTrue(directNoScheme is ResolvedMedia.DirectImage)
        assertEquals("https://i.redd.it/abc", (directNoScheme as ResolvedMedia.DirectImage).url)

        val shareNoScheme = MediaResolver.parseRedditLink("www.reddit.com/r/kotlin/s/share99")
        assertEquals(RedditLinkTarget.ShareLink("https://www.reddit.com/r/kotlin/s/share99"), shareNoScheme)

        val albumNoScheme = MediaResolver.resolveUrl("imgur.com/a/AbC123")
        assertTrue(albumNoScheme is ResolvedMedia.ImgurAlbum)
        assertEquals("AbC123", (albumNoScheme as ResolvedMedia.ImgurAlbum).albumId)

        val imgurMp4 = MediaResolver.resolveUrl("https://i.imgur.com/AbC123.mp4")
        assertTrue("Expected RedditVideo for Imgur .mp4, got $imgurMp4", imgurMp4 is ResolvedMedia.RedditVideo)
        assertEquals("https://i.imgur.com/AbC123.mp4", (imgurMp4 as ResolvedMedia.RedditVideo).fallbackUrl)
        assertNull(MediaResolver.extractDirectImageUrl(RedditPost(url = "https://i.imgur.com/AbC123.mp4")))

        val emptySlugPost = MediaResolver.parseRedditLink("https://www.reddit.com/r/sub/comments/post1//comm2")
        assertEquals(
            RedditLinkTarget.Post(subreddit = "sub", articleId = "post1", commentId = "comm2"),
            emptySlugPost
        )
        val emptySlugRootComments = MediaResolver.parseRedditLink("https://www.reddit.com/comments/post1//comm2")
        assertEquals(
            RedditLinkTarget.Post(subreddit = "", articleId = "post1", commentId = "comm2"),
            emptySlugRootComments
        )
    }
}

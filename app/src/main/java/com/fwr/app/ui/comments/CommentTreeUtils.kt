package com.fwr.app.ui.comments

import androidx.compose.runtime.Immutable
import com.fwr.app.data.model.MoreChildrenResponse
import com.fwr.app.data.model.RedditComment
import com.fwr.app.data.model.RedditListing
import com.fwr.app.data.model.RedditListingData
import com.fwr.app.data.model.RedditThing
import kotlinx.serialization.json.Json

@Immutable
data class FlattenedCommentNode(
    val comment: RedditComment,
    val depth: Int,
    val parentCommentId: String?,
    val rootCommentId: String,
    val childCount: Int,
    val isMorePlaceholder: Boolean,
    val stableKey: String
)

private data class PendingFlattenNode(
    val comment: RedditComment,
    val depth: Int,
    val parentCommentId: String?,
    val rootCommentId: String
)

private data class StubSearchFrame(
    val comment: RedditComment,
    val parentCommentId: String?,
    val childIndexInParent: Int,
    val parentFrameIndex: Int
)

internal fun countDescendants(comment: RedditComment): Int {
    val stack = ArrayDeque<RedditComment>()
    val initialChildren = comment.replies?.data?.children.orEmpty()
    for (i in initialChildren.indices.reversed()) {
        val child = initialChildren[i]
        if (child.kind == "t1" && child.data.body.isNotBlank()) {
            stack.addLast(child.data)
        }
    }

    var total = 0
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        total++
        val children = current.replies?.data?.children.orEmpty()
        for (i in children.indices.reversed()) {
            val child = children[i]
            if (child.kind == "t1" && child.data.body.isNotBlank()) {
                stack.addLast(child.data)
            }
        }
    }
    return total
}

internal fun collectCommentIds(comments: List<RedditComment>): Set<String> {
    if (comments.isEmpty()) return emptySet()
    val ids = HashSet<String>(comments.size * 2)
    val stack = ArrayDeque<RedditComment>(comments.size)
    for (i in comments.indices.reversed()) {
        stack.addLast(comments[i])
    }

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val isMore = current.body.isBlank() && current.moreChildrenIds.isNotEmpty()
        if (!isMore && current.body.isNotBlank() && current.id.isNotBlank()) {
            ids.add(current.id)
        }
        val children = current.replies?.data?.children.orEmpty()
        for (i in children.indices.reversed()) {
            stack.addLast(children[i].data)
        }
    }
    return ids
}

internal fun flattenVisibleComments(
    comments: List<RedditComment>,
    collapsedIds: Map<String, Boolean>,
    initialDepth: Int = 0,
    initialParentCommentId: String? = null,
    initialRootCommentId: String = ""
): List<FlattenedCommentNode> {
    if (comments.isEmpty()) return emptyList()

    val result = ArrayList<FlattenedCommentNode>(comments.size)
    val seenKeyCounts = HashMap<String, Int>(comments.size)
    val stack = ArrayDeque<PendingFlattenNode>(comments.size)

    for (i in comments.indices.reversed()) {
        stack.addLast(
            PendingFlattenNode(
                comment = comments[i],
                depth = initialDepth,
                parentCommentId = initialParentCommentId,
                rootCommentId = initialRootCommentId
            )
        )
    }

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val comment = current.comment
        val isMore = comment.body.isBlank() && comment.moreChildrenIds.isNotEmpty()
        if (!isMore && comment.id.isBlank() && comment.body.isBlank()) continue

        val effectiveRoot = if (current.depth == 0) comment.id else current.rootCommentId
        val isCollapsed = !isMore && collapsedIds[comment.id] == true
        val childCount = if (isCollapsed) countDescendants(comment) else 0

        val baseKey = if (isMore) {
            val stubToken = comment.id.ifBlank { "_" }
            val firstChild = comment.moreChildrenIds.firstOrNull().orEmpty()
            "more_${current.parentCommentId ?: "root"}_${stubToken}_$firstChild"
        } else if (comment.id.isNotBlank()) {
            "t1_${comment.id}"
        } else {
            "t1_anon_${current.parentCommentId ?: "root"}_${current.depth}"
        }

        val occurrence = (seenKeyCounts[baseKey] ?: 0) + 1
        seenKeyCounts[baseKey] = occurrence
        val stableKey = if (occurrence == 1) baseKey else "${baseKey}#$occurrence"

        result.add(
            FlattenedCommentNode(
                comment = comment,
                depth = current.depth,
                parentCommentId = current.parentCommentId,
                rootCommentId = effectiveRoot,
                childCount = childCount,
                isMorePlaceholder = isMore,
                stableKey = stableKey
            )
        )

        if (!isMore && !isCollapsed) {
            val children = comment.replies?.data?.children.orEmpty()
            for (i in children.indices.reversed()) {
                val child = children[i]
                val keep = (child.kind == "t1" && child.data.body.isNotBlank()) ||
                    (child.kind == "more" && child.data.moreChildrenIds.isNotEmpty())
                if (keep) {
                    stack.addLast(
                        PendingFlattenNode(
                            comment = child.data,
                            depth = current.depth + 1,
                            parentCommentId = comment.id,
                            rootCommentId = effectiveRoot
                        )
                    )
                }
            }
        }
    }

    return result
}

internal fun replaceMoreStubInTree(
    tree: List<RedditComment>,
    stubId: String,
    loadedComments: List<RedditComment>,
    parentCommentId: String? = null,
    stubFirstChildId: String? = null,
    requireRootParent: Boolean = false
): List<RedditComment> {
    if (tree.isEmpty()) return tree

    val existingIds = collectCommentIds(tree)
    val allLoadedCommentIds = loadedComments.asSequence()
        .filter { !(it.body.isBlank() && it.moreChildrenIds.isNotEmpty()) && it.body.isNotBlank() && it.id.isNotBlank() }
        .map { it.id }
        .toSet()
    val seenLoadedIds = HashSet<String>()
    val seenMoreKeys = HashSet<String>()
    val deduplicatedLoaded = ArrayList<RedditComment>(loadedComments.size)
    for (loaded in loadedComments) {
        val isMore = loaded.body.isBlank() && loaded.moreChildrenIds.isNotEmpty()
        if (isMore) {
            val remainingIds = loaded.moreChildrenIds.filter {
                it !in existingIds && it !in seenLoadedIds && it !in allLoadedCommentIds
            }
            if (remainingIds.isNotEmpty()) {
                val moreKey = "${loaded.id}:${remainingIds.first()}"
                if (seenMoreKeys.add(moreKey)) {
                    deduplicatedLoaded.add(
                        if (remainingIds.size == loaded.moreChildrenIds.size) {
                            loaded
                        } else {
                            loaded.copy(moreChildrenIds = remainingIds)
                        }
                    )
                }
            }
        } else if (loaded.body.isNotBlank()) {
            if (loaded.id.isBlank() || (loaded.id !in existingIds && seenLoadedIds.add(loaded.id))) {
                deduplicatedLoaded.add(loaded)
            }
        }
    }

    val frames = ArrayList<StubSearchFrame>()
    val stack = ArrayDeque<Int>()
    for (i in tree.indices.reversed()) {
        val frameIndex = frames.size
        frames.add(
            StubSearchFrame(
                comment = tree[i],
                parentCommentId = null,
                childIndexInParent = i,
                parentFrameIndex = -1
            )
        )
        stack.addLast(frameIndex)
    }

    var matchedFrameIndex = -1
    while (stack.isNotEmpty()) {
        val frameIndex = stack.removeLast()
        val frame = frames[frameIndex]
        val item = frame.comment
        val matchesParent = when {
            requireRootParent -> frame.parentCommentId == null
            parentCommentId != null -> frame.parentCommentId == parentCommentId
            else -> true
        }
        val matchesFirstChild = stubFirstChildId == null ||
            item.moreChildrenIds.firstOrNull() == stubFirstChildId

        if (item.moreChildrenIds.isNotEmpty() &&
            item.id == stubId &&
            matchesParent &&
            matchesFirstChild
        ) {
            matchedFrameIndex = frameIndex
            break
        }

        val children = item.replies?.data?.children.orEmpty()
        for (i in children.indices.reversed()) {
            val childFrameIndex = frames.size
            frames.add(
                StubSearchFrame(
                    comment = children[i].data,
                    parentCommentId = item.id,
                    childIndexInParent = i,
                    parentFrameIndex = frameIndex
                )
            )
            stack.addLast(childFrameIndex)
        }
    }

    if (matchedFrameIndex == -1) return tree

    val replacementThings = deduplicatedLoaded.map { loaded ->
        val kind = if (loaded.body.isBlank() && loaded.moreChildrenIds.isNotEmpty()) "more" else "t1"
        RedditThing(kind = kind, data = loaded)
    }

    val matchedFrame = frames[matchedFrameIndex]
    if (matchedFrame.parentFrameIndex == -1) {
        val updatedRoot = ArrayList<RedditComment>(tree.size + deduplicatedLoaded.size)
        for (i in tree.indices) {
            if (i == matchedFrame.childIndexInParent) {
                updatedRoot.addAll(deduplicatedLoaded)
            } else {
                updatedRoot.add(tree[i])
            }
        }
        return updatedRoot
    }

    var childIndexToReplace = matchedFrame.childIndexInParent
    var currentParentFrameIndex = matchedFrame.parentFrameIndex

    val firstParent = frames[currentParentFrameIndex].comment
    val firstParentReplies = firstParent.replies ?: return tree
    val firstParentChildren = firstParentReplies.data.children
    val rebuiltFirstChildren = ArrayList<RedditThing<RedditComment>>(
        firstParentChildren.size + replacementThings.size
    )
    for (i in firstParentChildren.indices) {
        if (i == childIndexToReplace) {
            rebuiltFirstChildren.addAll(replacementThings)
        } else {
            rebuiltFirstChildren.add(firstParentChildren[i])
        }
    }
    val updatedSubtree = firstParent.copy(
        replies = firstParentReplies.copy(
            data = firstParentReplies.data.copy(children = rebuiltFirstChildren)
        )
    )
    return rebuildTreeFromParentFrame(tree, frames, currentParentFrameIndex, updatedSubtree)
}

internal fun findNextCommentIndex(nodes: List<FlattenedCommentNode>, currentIndex: Int): Int? {
    val current = nodes.getOrNull(currentIndex) ?: return null
    for (i in (currentIndex + 1) until nodes.size) {
        val candidate = nodes[i]
        if (candidate.depth > current.depth) continue
        if (!candidate.isMorePlaceholder) {
            return i
        }
    }
    return null
}

internal fun findPrevCommentIndex(nodes: List<FlattenedCommentNode>, currentIndex: Int): Int? {
    val current = nodes.getOrNull(currentIndex) ?: return null
    if (current.depth == 0) {
        for (i in (currentIndex - 1) downTo 0) {
            val candidate = nodes[i]
            if (candidate.depth == 0 && !candidate.isMorePlaceholder) {
                return i
            }
        }
        return null
    }
    for (i in (currentIndex - 1) downTo 0) {
        val candidate = nodes[i]
        if (candidate.comment.id == current.parentCommentId || candidate.depth < current.depth) {
            break
        }
        if (candidate.depth == current.depth && !candidate.isMorePlaceholder) {
            return i
        }
    }
    for (i in (currentIndex - 1) downTo 0) {
        val candidate = nodes[i]
        if (candidate.comment.id == current.parentCommentId) continue
        if (candidate.depth <= current.depth && !candidate.isMorePlaceholder) {
            return i
        }
    }
    return null
}

private val postedCommentJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private fun rebuildTreeFromParentFrame(
    tree: List<RedditComment>,
    frames: List<StubSearchFrame>,
    parentFrameIndex: Int,
    updatedParentComment: RedditComment
): List<RedditComment> {
    var currentParentFrameIndex = parentFrameIndex
    var updatedSubtree = updatedParentComment
    while (frames[currentParentFrameIndex].parentFrameIndex != -1) {
        val childFrame = frames[currentParentFrameIndex]
        currentParentFrameIndex = childFrame.parentFrameIndex
        val parentComment = frames[currentParentFrameIndex].comment
        val parentReplies = parentComment.replies ?: return tree
        val updatedChildren = parentReplies.data.children.toMutableList()
        updatedChildren[childFrame.childIndexInParent] =
            updatedChildren[childFrame.childIndexInParent].copy(data = updatedSubtree)
        updatedSubtree = parentComment.copy(
            replies = parentReplies.copy(
                data = parentReplies.data.copy(children = updatedChildren)
            )
        )
    }
    val topIndex = frames[currentParentFrameIndex].childIndexInParent
    val updatedRoot = tree.toMutableList()
    updatedRoot[topIndex] = updatedSubtree
    return updatedRoot
}

internal fun parsePostedCommentFromResponse(
    responseBody: String,
    fallbackAuthor: String = "",
    fallbackBody: String = ""
): RedditComment? {
    if (responseBody.isBlank()) return null
    val parsed = runCatching {
        postedCommentJson.decodeFromString(MoreChildrenResponse.serializer(), responseBody)
    }.getOrNull() ?: return null
    val things = parsed.json.data.things
    val raw = things.firstOrNull { it.kind == "t1" }?.data ?: things.firstOrNull()?.data ?: return null
    val normalizedId = raw.id.ifBlank { raw.fullname.removePrefix("t1_") }.trim()
    if (normalizedId.isBlank()) return null
    val normalizedFullname = raw.fullname.ifBlank { "t1_$normalizedId" }
    val normalizedAuthor = raw.author.takeIf { it.isNotBlank() && it != "[deleted]" }
        ?: fallbackAuthor.ifBlank { raw.author }
    val normalizedBody = raw.body.ifBlank { fallbackBody }
    if (normalizedBody.isBlank()) return null
    return raw.copy(
        id = normalizedId,
        fullname = normalizedFullname,
        author = normalizedAuthor,
        body = normalizedBody
    )
}

private fun findCommentFrame(
    tree: List<RedditComment>,
    targetId: String
): Pair<List<StubSearchFrame>, Int>? {
    if (tree.isEmpty() || targetId.isBlank()) return null
    val frames = ArrayList<StubSearchFrame>()
    val stack = ArrayDeque<Int>()
    for (i in tree.indices.reversed()) {
        val frameIndex = frames.size
        frames.add(
            StubSearchFrame(
                comment = tree[i],
                parentCommentId = null,
                childIndexInParent = i,
                parentFrameIndex = -1
            )
        )
        stack.addLast(frameIndex)
    }

    while (stack.isNotEmpty()) {
        val frameIndex = stack.removeLast()
        val frame = frames[frameIndex]
        val item = frame.comment
        val isMore = item.body.isBlank() && item.moreChildrenIds.isNotEmpty()
        if (!isMore && item.id == targetId) {
            return frames to frameIndex
        }
        val children = item.replies?.data?.children.orEmpty()
        for (i in children.indices.reversed()) {
            val childFrameIndex = frames.size
            frames.add(
                StubSearchFrame(
                    comment = children[i].data,
                    parentCommentId = item.id,
                    childIndexInParent = i,
                    parentFrameIndex = frameIndex
                )
            )
            stack.addLast(childFrameIndex)
        }
    }
    return null
}

internal fun insertPostedCommentInTree(
    tree: List<RedditComment>,
    parentFullname: String,
    newComment: RedditComment
): List<RedditComment> {
    if (newComment.id.isNotBlank() && newComment.id in collectCommentIds(tree)) {
        return tree
    }
    if (parentFullname.startsWith("t3_")) {
        return listOf(newComment) + tree
    }
    if (!parentFullname.startsWith("t1_")) {
        return tree
    }
    val targetParentId = parentFullname.removePrefix("t1_").trim()
    val (frames, matchedFrameIndex) = findCommentFrame(tree, targetParentId) ?: return tree

    val targetParent = frames[matchedFrameIndex].comment
    val existingReplies = targetParent.replies ?: RedditListing(data = RedditListingData())
    val updatedChildren = listOf(RedditThing(kind = "t1", data = newComment)) + existingReplies.data.children
    val updatedParent = targetParent.copy(
        replies = existingReplies.copy(
            data = existingReplies.data.copy(children = updatedChildren)
        )
    )
    return rebuildTreeFromParentFrame(tree, frames, matchedFrameIndex, updatedParent)
}

internal fun deleteCommentInTree(
    tree: List<RedditComment>,
    commentId: String
): Pair<List<RedditComment>, Boolean> {
    val (frames, matchedFrameIndex) = findCommentFrame(tree, commentId) ?: return tree to false

    val matchedFrame = frames[matchedFrameIndex]
    val target = matchedFrame.comment
    val hasChildren = target.replies?.data?.children?.any {
        (it.kind == "t1" && it.data.body.isNotBlank()) ||
            (it.kind == "more" && it.data.moreChildrenIds.isNotEmpty())
    } == true

    val deletedPlaceholder = target.copy(
        author = "[deleted]",
        body = "[deleted]",
        author_flair_text = null,
        distinguished = null,
        is_submitter = false
    )

    if (matchedFrame.parentFrameIndex == -1) {
        val updatedRoot = tree.toMutableList()
        return if (hasChildren) {
            updatedRoot[matchedFrame.childIndexInParent] = deletedPlaceholder
            updatedRoot to false
        } else {
            updatedRoot.removeAt(matchedFrame.childIndexInParent)
            updatedRoot to true
        }
    }

    val parentFrame = frames[matchedFrame.parentFrameIndex]
    val parentComment = parentFrame.comment
    val parentReplies = parentComment.replies ?: return tree to false
    val updatedChildren = parentReplies.data.children.toMutableList()
    if (hasChildren) {
        updatedChildren[matchedFrame.childIndexInParent] =
            updatedChildren[matchedFrame.childIndexInParent].copy(data = deletedPlaceholder)
    } else {
        updatedChildren.removeAt(matchedFrame.childIndexInParent)
    }
    val updatedParent = parentComment.copy(
        replies = parentReplies.copy(
            data = parentReplies.data.copy(children = updatedChildren)
        )
    )
    return rebuildTreeFromParentFrame(tree, frames, matchedFrame.parentFrameIndex, updatedParent) to !hasChildren
}

internal fun computePreservedScrollOffset(
    anchorTopOffsetPx: Int?,
    viewportHeightPx: Int,
    minVisibleBottomPx: Int = 120
): Int {
    if (anchorTopOffsetPx == null) return 0
    val maxOffset = (viewportHeightPx - minVisibleBottomPx).coerceAtLeast(0)
    return anchorTopOffsetPx.coerceIn(0, maxOffset)
}

package com.fwr.app.ui.comments

import com.fwr.app.data.model.RedditComment
import com.fwr.app.data.model.RedditListing
import com.fwr.app.data.model.RedditListingData
import com.fwr.app.data.model.RedditThing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTreeUtilsTest {

    private fun t1(
        id: String,
        body: String = "Body of $id",
        replies: List<RedditThing<RedditComment>> = emptyList()
    ): RedditComment {
        return RedditComment(
            id = id,
            author = "user_$id",
            body = body,
            replies = if (replies.isEmpty()) {
                null
            } else {
                RedditListing(data = RedditListingData(children = replies))
            }
        )
    }

    private fun moreStub(
        id: String = "_",
        childrenIds: List<String>,
        count: Int = childrenIds.size
    ): RedditComment {
        return RedditComment(
            id = id,
            body = "",
            count = count,
            moreChildrenIds = childrenIds
        )
    }

    @Test
    fun stableKeyPreservedAcrossCollapseAndExpand() {
        val child1 = t1("c1_1")
        val child2 = t1("c1_2")
        val stub1 = moreStub(id = "m1", childrenIds = listOf("x1", "x2"))
        val parent1 = t1(
            "p1",
            replies = listOf(
                RedditThing(kind = "t1", data = child1),
                RedditThing(kind = "t1", data = child2),
                RedditThing(kind = "more", data = stub1)
            )
        )
        val parent2 = t1("p2")
        val siblingStub = moreStub(id = "_", childrenIds = listOf("y1"))
        val tree = listOf(parent1, parent2, siblingStub)

        val expanded = flattenVisibleComments(tree, collapsedIds = emptyMap())
        val collapsed = flattenVisibleComments(tree, collapsedIds = mapOf("p1" to true))

        assertEquals(listOf("p1", "c1_1", "c1_2", "m1", "p2", "_"), expanded.map { it.comment.id })
        assertEquals(listOf("p1", "p2", "_"), collapsed.map { it.comment.id })

        val collapsedP1 = collapsed.first { it.comment.id == "p1" }
        assertEquals(2, collapsedP1.childCount)

        val p2KeyExpanded = expanded.first { it.comment.id == "p2" }.stableKey
        val p2KeyCollapsed = collapsed.first { it.comment.id == "p2" }.stableKey
        assertEquals(p2KeyExpanded, p2KeyCollapsed)

        val stubKeyExpanded = expanded.last().stableKey
        val stubKeyCollapsed = collapsed.last().stableKey
        assertEquals(stubKeyExpanded, stubKeyCollapsed)
    }

    @Test
    fun duplicateAndBlankMoreStubsHaveUniqueDeterministicKeys() {
        val stubSameParentA = moreStub(id = "_", childrenIds = listOf("a1"))
        val stubSameParentB = moreStub(id = "_", childrenIds = listOf("a1"))
        val stubOtherParent = moreStub(id = "_", childrenIds = listOf("b1"))
        val blankStub = moreStub(id = "", childrenIds = listOf("c1"))

        val p1 = t1(
            "p1",
            replies = listOf(
                RedditThing(kind = "more", data = stubSameParentA),
                RedditThing(kind = "more", data = stubSameParentB)
            )
        )
        val p2 = t1(
            "p2",
            replies = listOf(
                RedditThing(kind = "more", data = stubOtherParent),
                RedditThing(kind = "more", data = blankStub)
            )
        )

        val nodes = flattenVisibleComments(listOf(p1, p2), collapsedIds = emptyMap())
        val keys = nodes.map { it.stableKey }

        assertEquals(6, keys.size)
        assertEquals(6, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
        assertNotEquals(nodes[1].stableKey, nodes[2].stableKey)
    }

    @Test
    fun deepTree2000CommentsFlattensAndCountsWithoutStackOverflow() {
        var current = t1("n1999")
        for (i in 1998 downTo 0) {
            current = t1("n$i", replies = listOf(RedditThing(kind = "t1", data = current)))
        }
        val tree = listOf(current)

        val flattened = flattenVisibleComments(tree, collapsedIds = emptyMap())
        assertEquals(2000, flattened.size)
        assertEquals(0, flattened.first().depth)
        assertEquals(1999, flattened.last().depth)
        assertEquals("n0", flattened.last().rootCommentId)

        val collapsedRoot = flattenVisibleComments(tree, collapsedIds = mapOf("n0" to true))
        assertEquals(1, collapsedRoot.size)
        assertEquals(1999, collapsedRoot.first().childCount)
        assertEquals(1999, countDescendants(current))
    }

    @Test
    fun wideTree2000CommentsFlattensInSinglePass() {
        val topLevel = (0 until 2000).map { idx ->
            if (idx % 10 == 0) {
                t1("top_$idx", replies = listOf(RedditThing(kind = "t1", data = t1("child_$idx"))))
            } else {
                t1("top_$idx")
            }
        }

        val flattened = flattenVisibleComments(topLevel, collapsedIds = emptyMap())
        assertEquals(2200, flattened.size)
        assertEquals(2200, flattened.map { it.stableKey }.toSet().size)
    }

    @Test
    fun replaceMoreStubInTreePreservesNestedMoreKindAndDeduplicatesIds() {
        val stubUnderP1 = moreStub(id = "_", childrenIds = listOf("c_new", "c_dup"))
        val stubUnderP2 = moreStub(id = "_", childrenIds = listOf("other_1"))
        val existingChild = t1("c_existing")
        val p1 = t1(
            "p1",
            replies = listOf(
                RedditThing(kind = "t1", data = existingChild),
                RedditThing(kind = "more", data = stubUnderP1)
            )
        )
        val p2 = t1(
            "p2",
            replies = listOf(RedditThing(kind = "more", data = stubUnderP2))
        )

        val nestedMore = moreStub(id = "_", childrenIds = listOf("tail_1", "tail_2"))
        val loaded = listOf(
            t1("c_existing"), // already in tree -> should be deduplicated
            t1("c_new"),
            t1("c_new"),      // duplicate within response -> should be deduplicated
            nestedMore
        )

        val updated = replaceMoreStubInTree(
            tree = listOf(p1, p2),
            stubId = "_",
            loadedComments = loaded,
            parentCommentId = "p1",
            stubFirstChildId = "c_new"
        )

        val updatedP1Children = updated[0].replies?.data?.children.orEmpty()
        assertEquals(3, updatedP1Children.size)
        assertEquals("t1", updatedP1Children[0].kind)
        assertEquals("c_existing", updatedP1Children[0].data.id)
        assertEquals("t1", updatedP1Children[1].kind)
        assertEquals("c_new", updatedP1Children[1].data.id)
        assertEquals("more", updatedP1Children[2].kind)
        assertEquals(listOf("tail_1", "tail_2"), updatedP1Children[2].data.moreChildrenIds)

        // Unrelated "_" stub under p2 must remain intact
        val updatedP2Children = updated[1].replies?.data?.children.orEmpty()
        assertEquals(1, updatedP2Children.size)
        assertEquals("more", updatedP2Children[0].kind)
        assertEquals(listOf("other_1"), updatedP2Children[0].data.moreChildrenIds)

        // Flattening updated tree surfaces nested "more" stub
        val flattened = flattenVisibleComments(updated, collapsedIds = emptyMap())
        assertTrue(flattened.any { it.isMorePlaceholder && it.parentCommentId == "p1" })
    }

    @Test
    fun replaceMoreStubInTreeDepth600DoesNotOverflowStack() {
        val targetStub = moreStub(id = "_", childrenIds = listOf("leaf_loaded"))
        var node = t1("d599", replies = listOf(RedditThing(kind = "more", data = targetStub)))
        for (i in 598 downTo 0) {
            node = t1("d$i", replies = listOf(RedditThing(kind = "t1", data = node)))
        }

        val updated = replaceMoreStubInTree(
            tree = listOf(node),
            stubId = "_",
            loadedComments = listOf(t1("leaf_loaded")),
            parentCommentId = "d599",
            stubFirstChildId = "leaf_loaded"
        )

        val ids = collectCommentIds(updated)
        assertTrue("leaf_loaded" in ids)
        assertEquals(601, ids.size)
    }

    @Test
    fun collectCommentIdsAndNavigationHelpers() {
        val c1 = t1(
            "c1",
            replies = listOf(
                RedditThing(kind = "t1", data = t1("c1a")),
                RedditThing(kind = "more", data = moreStub(id = "_", childrenIds = listOf("c1b")))
            )
        )
        val c2 = t1("c2")
        val tree = listOf(c1, c2)

        val ids = collectCommentIds(tree)
        assertEquals(setOf("c1", "c1a", "c2"), ids)
        assertFalse("_" in ids)

        val nodes = flattenVisibleComments(tree, collapsedIds = emptyMap())
        // [c1 (0), c1a (1), _ (2, more), c2 (3)]
        assertEquals(3, findNextCommentIndex(nodes, 0)) // skips c1a and more stub
        assertNull(findNextCommentIndex(nodes, 3))
        assertEquals(0, findPrevCommentIndex(nodes, 3))
        assertNull(findPrevCommentIndex(nodes, 1)) // skips immediate parent c1
    }

    @Test
    fun replaceMoreStubInTree_requireRootParentTargetsRootStubEvenWhenNestedStubSharesId() {
        val nestedStub = moreStub(id = "_", childrenIds = listOf("shared_1"))
        val rootStub = moreStub(id = "_", childrenIds = listOf("shared_1"))
        val p1 = t1("p1", replies = listOf(RedditThing(kind = "more", data = nestedStub)))

        val updated = replaceMoreStubInTree(
            tree = listOf(p1, rootStub),
            stubId = "_",
            loadedComments = listOf(t1("shared_1")),
            parentCommentId = null,
            stubFirstChildId = "shared_1",
            requireRootParent = true
        )

        // Nested stub under p1 must remain untouched
        val p1Children = updated[0].replies?.data?.children.orEmpty()
        assertEquals(1, p1Children.size)
        assertEquals("more", p1Children[0].kind)
        assertEquals(listOf("shared_1"), p1Children[0].data.moreChildrenIds)

        // Root stub must be replaced by shared_1
        assertEquals(listOf("p1", "shared_1"), updated.map { it.id })
    }

    @Test
    fun replaceMoreStubInTree_deduplicatesNestedMoreStubIdsAgainstBatchLoadedComments() {
        val rootStub = moreStub(id = "_", childrenIds = listOf("c_new"))
        val nestedMore = moreStub(id = "_", childrenIds = listOf("c_new", "c_tail"))
        val p1 = t1("p1", replies = listOf(RedditThing(kind = "more", data = rootStub)))

        val updated = replaceMoreStubInTree(
            tree = listOf(p1),
            stubId = "_",
            loadedComments = listOf(t1("c_new"), nestedMore),
            parentCommentId = "p1",
            stubFirstChildId = "c_new",
            requireRootParent = false
        )

        val children = updated[0].replies?.data?.children.orEmpty()
        assertEquals(listOf("t1", "more"), children.map { it.kind })
        assertEquals(listOf("c_tail"), children[1].data.moreChildrenIds)
    }
}

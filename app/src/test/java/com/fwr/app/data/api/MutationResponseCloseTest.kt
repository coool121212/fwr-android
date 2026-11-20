package com.fwr.app.data.api

import com.fwr.app.data.network.RedditNetworkClient
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MutationResponseCloseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun allTenMutationEndpointsCloseBodiesAndReuseSingleConnection() = runTest {
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)
        val bodyEnded = AtomicInteger(0)

        val listener = object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                acquired.incrementAndGet()
            }

            override fun connectionReleased(call: Call, connection: Connection) {
                released.incrementAndGet()
            }

            override fun responseBodyEnd(call: Call, byteCount: Long) {
                bodyEnded.incrementAndGet()
            }
        }

        val pool = ConnectionPool(1, 5, TimeUnit.SECONDS)
        val client = OkHttpClient.Builder()
            .connectionPool(pool)
            .eventListener(listener)
            .build()

        val api = RedditNetworkClient.createRetrofit(
            baseUrl = server.url("/").toString(),
            client = client
        ).create(RedditApiService::class.java)

        val largeBody = """{"json":{"errors":[]},"pad":"${"x".repeat(4096)}"}"""
        repeat(10) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(largeBody))
        }

        api.setSubredditSubscription("sub", "kotlin")
        api.vote("t3_abc", 1)
        api.save("t3_abc")
        api.unsave("t3_abc")
        api.hide("t3_abc")
        api.unhide("t3_abc")
        api.markMessageRead("t4_1")
        api.markMessageUnread("t4_1")
        api.deleteThing("t1_abc")
        api.setSendReplies("t1_abc", true)

        assertEquals(10, server.requestCount)
        assertEquals(10, acquired.get())
        assertEquals(10, released.get())
        assertEquals(10, bodyEnded.get())
        assertTrue(pool.connectionCount() <= 1)
    }

    @Test
    fun http500OnMutationThrowsHttpExceptionAndReleasesConnection() = runTest {
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)
        val listener = object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                acquired.incrementAndGet()
            }

            override fun connectionReleased(call: Call, connection: Connection) {
                released.incrementAndGet()
            }
        }
        val pool = ConnectionPool(1, 5, TimeUnit.SECONDS)
        val client = OkHttpClient.Builder()
            .connectionPool(pool)
            .eventListener(listener)
            .build()

        val api = RedditNetworkClient.createRetrofit(
            baseUrl = server.url("/").toString(),
            client = client
        ).create(RedditApiService::class.java)

        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":"server_error","pad":"${"e".repeat(4096)}"}""")
        )

        try {
            api.vote("t3_err", 1)
            fail("Expected HttpException on HTTP 500")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }

        assertEquals(1, acquired.get())
        assertEquals(1, released.get())
    }

    @Test
    fun reflectionVerifiesTenMutationEndpointsDoNotReturnResponseBody() {
        val mutationMethodNames = setOf(
            "setSubredditSubscription",
            "vote",
            "save",
            "unsave",
            "hide",
            "unhide",
            "markMessageRead",
            "markMessageUnread",
            "deleteThing",
            "setSendReplies"
        )
        val methods = RedditApiService::class.java.declaredMethods
            .filter { it.name in mutationMethodNames }
        assertEquals(10, methods.size)

        for (method in methods) {
            val continuationType = method.genericParameterTypes.last().toString()
            assertFalse(
                "Method ${method.name} should not return ResponseBody, got $continuationType",
                continuationType.contains(ResponseBody::class.java.name)
            )
        }
    }
}

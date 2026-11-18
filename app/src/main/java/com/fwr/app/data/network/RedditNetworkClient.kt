package com.fwr.app.data.network

import android.content.Context
import com.fwr.app.BuildConfig
import com.fwr.app.data.api.RedditApiService
import com.fwr.app.data.auth.RedditOAuthManager
import com.fwr.app.data.model.RedditComment
import com.fwr.app.data.model.RedditPost
import com.fwr.app.data.prefs.UserPreferencesRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Provides configured OkHttp, kotlinx.serialization, and Retrofit instances for communicating
 * with Reddit's OAuth API (`https://oauth.reddit.com/`), with [AuthInterceptor] routing keyless
 * requests to `https://www.reddit.com/` and [RateLimitInterceptor] enforcing Reddit rate limits.
 */
object RedditNetworkClient {

    const val OAUTH_BASE_URL = "https://oauth.reddit.com/"
    const val CUSTOM_USER_AGENT = "android:com.fwr.app:v${BuildConfig.VERSION_NAME}"
    const val DEFAULT_FALLBACK_CLIENT_ID = UserPreferencesRepository.DEFAULT_FALLBACK_CLIENT_ID

    private val initLock = Any()

    @Volatile
    private var preferencesRepository: UserPreferencesRepository? = null

    @Volatile
    private var oauthManager: RedditOAuthManager? = null

    internal val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    val rateLimitTracker: RedditRateLimitTracker = RedditRateLimitTracker()
    val rateLimitInterceptor: RateLimitInterceptor = RateLimitInterceptor(rateLimitTracker)

    /**
     * Explicit User-Agent interceptor ensuring every OkHttp request sends a Reddit-compliant
     * custom User-Agent rather than the default OkHttp User-Agent which Reddit rejects with HTTP 403.
     */
    val userAgentInterceptor: Interceptor = Interceptor { chain ->
        val requestWithUserAgent = chain.request().newBuilder()
            .removeHeader("User-Agent")
            .header("User-Agent", CUSTOM_USER_AGENT)
            .build()
        chain.proceed(requestWithUserAgent)
    }

    val authInterceptor: AuthInterceptor = AuthInterceptor(
        userAgent = CUSTOM_USER_AGENT,
        preferencesProvider = { preferencesRepository ?: UserPreferencesRepository.peekInstance() },
        oauthManagerProvider = { getOrCreateOAuthManager() }
    )

    private fun getOrCreateOAuthManager(): RedditOAuthManager? {
        oauthManager?.let { return it }
        val prefs = preferencesRepository ?: UserPreferencesRepository.peekInstance() ?: return null
        return synchronized(initLock) {
            oauthManager ?: RedditOAuthManager(prefs, CUSTOM_USER_AGENT).also {
                preferencesRepository = prefs
                oauthManager = it
            }
        }
    }

    /**
     * Idempotently initializes [RedditNetworkClient] with the application's [UserPreferencesRepository]
     * and optional [RedditOAuthManager].
     */
    fun initialize(
        context: Context,
        prefsRepo: UserPreferencesRepository = UserPreferencesRepository.getInstance(context),
        manager: RedditOAuthManager? = null
    ) {
        synchronized(initLock) {
            val resolvedPrefs = preferencesRepository ?: prefsRepo
            preferencesRepository = resolvedPrefs
            if (oauthManager == null) {
                oauthManager = manager ?: RedditOAuthManager(resolvedPrefs, CUSTOM_USER_AGENT)
            }
        }
    }

    fun getOAuthManager(context: Context): RedditOAuthManager {
        oauthManager?.let { return it }
        return synchronized(initLock) {
            oauthManager?.let { return it }
            val prefs = preferencesRepository ?: UserPreferencesRepository.getInstance(context)
            RedditOAuthManager(prefs, CUSTOM_USER_AGENT).also {
                preferencesRepository = prefs
                oauthManager = it
            }
        }
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(Any::class, RedditDynamicThingSerializer)
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(rateLimitInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        }
                    )
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Dedicated OkHttp client for media CDNs (`v.redd.it`, `i.redd.it`, `i.imgur.com`, `redgifs.com`, etc.)
     * that preserves the target host and sends [CUSTOM_USER_AGENT] without attaching Reddit API OAuth tokens
     * or rewriting hosts through [authInterceptor].
     */
    val mediaHttpClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .addInterceptor(userAgentInterceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Primary Retrofit instance targeting `https://oauth.reddit.com/`.
     * When no OAuth token is set on [authInterceptor], requests automatically fall back to `https://www.reddit.com/`.
     */
    val retrofit: Retrofit by lazy {
        createRetrofit(baseUrl = OAUTH_BASE_URL)
    }

    val apiService: RedditApiService by lazy {
        retrofit.create(RedditApiService::class.java)
    }

    /**
     * Updates the current OAuth Bearer token used for authenticated requests.
     */
    fun setOAuthToken(token: String?) {
        authInterceptor.oauthToken = token
    }

    fun createRetrofit(
        baseUrl: String = OAUTH_BASE_URL,
        client: OkHttpClient = okHttpClient
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}

/**
 * Contextual serializer for heterogeneous `RedditListing<Any>` responses (such as `/comments/{article}.json`),
 * decoding `t3` post objects into [RedditPost], `t1` comment objects into [RedditComment],
 * and any other JSON payloads into [JsonElement].
 */
object RedditDynamicThingSerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()

        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonObject) {
            return when {
                "body" in element ||
                    ("replies" in element && "title" !in element) ||
                    ("children" in element && "count" in element) -> {
                    jsonDecoder.json.decodeFromJsonElement(RedditComment.serializer(), element)
                }
                "title" in element && ("selftext" in element || "is_self" in element || "url" in element) -> {
                    jsonDecoder.json.decodeFromJsonElement(RedditPost.serializer(), element)
                }
                else -> element
            }
        }
        return element
    }

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as? JsonEncoder ?: return
        when (value) {
            is RedditPost -> jsonEncoder.encodeSerializableValue(RedditPost.serializer(), value)
            is RedditComment -> jsonEncoder.encodeSerializableValue(RedditComment.serializer(), value)
            is JsonElement -> jsonEncoder.encodeJsonElement(value)
            else -> jsonEncoder.encodeJsonElement(JsonObject(emptyMap()))
        }
    }
}

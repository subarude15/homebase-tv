package com.homebase.tv.data.backend

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header

@Serializable
data class Health(
    val status: String,
    val version: String,
    val database: Boolean,
    val authenticated: Boolean,
)

@Serializable
enum class Shape {
    @SerialName("landscape") Landscape,
    @SerialName("poster") Poster,
    @SerialName("square") Square,
}

@Serializable
data class MediaItem(
    val id: String,
    val type: String,
    val source: String = "emby",
    @SerialName("content_id") val contentId: String? = null,
    val title: String,
    val subtitle: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String = "",
    val year: Int? = null,
    @SerialName("runtime_minutes") val runtimeMinutes: Int? = null,
    val progress: Float? = null,
    val image: String? = null,
    val backdrop: String? = null,
)

@Serializable
data class MediaRow(val id: String, val title: String, val shape: Shape, val items: List<MediaItem>)

@Serializable
data class HomeResponse(
    val rows: List<MediaRow>,
    @SerialName("updated_at") val updatedAt: Long,
    val stale: Boolean = false,
    @SerialName("server_id") val serverId: String? = null,
)

val MediaItem.isPlayable get() = type in setOf("Movie", "Episode", "Video", "MusicVideo")

/** Resumes a partly watched video, like Nuvio's links; everything else opens its detail page. */
fun embyLink(serverId: String, item: MediaItem): String =
    "emby://${if (item.isPlayable && item.progress != null) "play" else "items"}/$serverId/${item.id}"

const val NUVIO_PACKAGE = "com.nuvio.tv"

val MediaItem.nuvioType get() = if (type == "Movie") "movie" else "series"

/** Nuvio's detail page; [MediaItem.contentId] is an IMDb ID or "tmdb:…", both of which Nuvio accepts here. */
fun nuvioLink(item: MediaItem): String = "nuvio://${item.nuvioType}/${item.contentId}"

/** Nuvio's ID for the exact video: the movie's own ID, or "show:season:episode". */
fun nuvioVideoId(item: MediaItem): String =
    if (item.season != null && item.episode != null) "${item.contentId}:${item.season}:${item.episode}" else item.contentId.orEmpty()

/** Image paths from the backend are relative; [width] asks the proxy for a larger rendition. */
fun imageUrl(backendUrl: String, path: String, width: Int? = null): String =
    backendUrl.trim().trimEnd('/') + path + (width?.let { "&width=$it" } ?: "")

/** [error] is set when the backend couldn't be reached; [home] may then be the last saved copy. */
data class HomeResult(val home: HomeResponse?, val error: String?)

interface BackendApi {
    @GET("health")
    suspend fun health(@Header("Authorization") authorization: String?): Health

    @GET("api/home")
    suspend fun home(@Header("Authorization") authorization: String?): HomeResponse
}

val backendJson = Json { ignoreUnknownKeys = true }

class BackendRepository(cacheDir: File) {
    private val homeCache = File(cacheDir, "home.json")

    @Volatile
    private var token: String = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Used by Coil; adds the current token so artwork goes through the authenticated proxy. */
    val imageClient: OkHttpClient = client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (token.isBlank()) request else request.newBuilder().header("Authorization", "Bearer $token").build())
        }
        .build()

    suspend fun health(baseUrl: String, apiToken: String): Result<Health> = runCatching {
        api(baseUrl).health(bearer(apiToken))
    }

    /** Must be set before saved rows are shown, or their artwork requests go out unauthenticated. */
    fun useToken(apiToken: String) {
        token = apiToken.trim()
    }

    suspend fun home(baseUrl: String, apiToken: String): HomeResult = withContext(Dispatchers.IO) {
        useToken(apiToken)
        runCatching { api(baseUrl).home(bearer(apiToken)) }.fold(
            onSuccess = {
                homeCache.writeText(backendJson.encodeToString(HomeResponse.serializer(), it))
                HomeResult(it, null)
            },
            onFailure = { HomeResult(cachedHome(), it.message ?: it::class.simpleName.orEmpty()) },
        )
    }

    fun cachedHome(): HomeResponse? = runCatching {
        backendJson.decodeFromString(HomeResponse.serializer(), homeCache.readText())
    }.getOrNull()

    private fun bearer(apiToken: String) = apiToken.trim().takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    private fun api(baseUrl: String): BackendApi = Retrofit.Builder()
        .baseUrl(baseUrl.trim().trimEnd('/') + "/")
        .client(client)
        .addConverterFactory(backendJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BackendApi::class.java)
}

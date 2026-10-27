package com.homebase.tv.data.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private interface HomebaseApi {
    @GET("health")
    suspend fun health(@Header("Authorization") authorization: String?): Health

    @GET("api/home")
    suspend fun home(@Header("Authorization") authorization: String): HomeResponse
}

class BackendRepository(filesDir: File) {
    private val cacheFile = File(filesDir, "home-cache.json")
    private val authToken = AtomicReference("")

    /** Coil uses this so image proxy requests carry the same Bearer token as API calls. */
    val imageClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = authToken.get()
            val req = if (token.isBlank()) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(req)
        }
        .build()

    private fun api(baseUrl: String): HomebaseApi {
        val root = baseUrl.trim().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(root)
            .client(imageClient)
            .addConverterFactory(backendJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HomebaseApi::class.java)
    }

    private fun bearer(token: String) = "Bearer ${token.trim()}"

    suspend fun health(url: String, token: String): Result<Health> = withContext(Dispatchers.IO) {
        runCatching {
            authToken.set(token.trim())
            api(url).health(if (token.isBlank()) null else bearer(token))
        }
    }

    suspend fun home(url: String, token: String): HomeResult = withContext(Dispatchers.IO) {
        if (url.isBlank() || token.isBlank()) {
            return@withContext HomeResult(cachedHome(), "Backend URL or API token is not set")
        }
        authToken.set(token.trim())
        try {
            val home = api(url).home(bearer(token))
            cacheFile.writeText(backendJson.encodeToString(HomeResponse.serializer(), home))
            HomeResult(home, null)
        } catch (e: Exception) {
            HomeResult(cachedHome(), e.message ?: e::class.simpleName.orEmpty())
        }
    }

    fun cachedHome(): HomeResponse? = runCatching {
        if (!cacheFile.isFile) return null
        backendJson.decodeFromString(HomeResponse.serializer(), cacheFile.readText())
    }.getOrNull()
}

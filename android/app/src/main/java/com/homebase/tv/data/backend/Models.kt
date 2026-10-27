package com.homebase.tv.data.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val backendJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
data class Health(
    val status: String = "",
    val version: String = "",
    val database: Boolean = false,
    val authenticated: Boolean = false,
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
    val title: String,
    val subtitle: String = "",
    val overview: String = "",
    val year: Int? = null,
    @SerialName("runtime_minutes") val runtimeMinutes: Int? = null,
    val progress: Float? = null,
    val image: String? = null,
    val backdrop: String? = null,
)

@Serializable
data class HomeRow(
    val id: String,
    val title: String,
    val shape: Shape,
    val items: List<MediaItem> = emptyList(),
)

@Serializable
data class HomeResponse(
    val rows: List<HomeRow> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0,
    val stale: Boolean = false,
)

data class HomeResult(val home: HomeResponse?, val error: String?)

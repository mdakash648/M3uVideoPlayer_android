package com.mdaksh.m3uvideoplayer.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Xtream Codes providers are notoriously inconsistent about JSON types — the same field
 * (e.g. "category_id", "stream_id", "auth") can come back as either a JSON number or a
 * JSON string depending on the panel software/version. Gson's default String adapter can
 * consume either representation safely, so every id-like/flag-like field below is typed
 * as String? on purpose instead of Int?/Boolean?. This avoids crashes on providers that
 * don't match the "expected" type.
 */

// ---- player_api.php (no action) — login/auth check ----

data class XtreamAuthResponse(
    @SerializedName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerializedName("server_info") val serverInfo: XtreamServerInfo? = null
)

data class XtreamUserInfo(
    val username: String? = null,
    val auth: String? = null, // "1" / "0" (or numeric 1/0 depending on provider)
    val status: String? = null, // e.g. "Active", "Expired", "Banned"
    val message: String? = null,
    @SerializedName("exp_date") val expDate: String? = null,
    @SerializedName("is_trial") val isTrial: String? = null,
    @SerializedName("active_cons") val activeCons: String? = null,
    @SerializedName("max_connections") val maxConnections: String? = null
) {
    val isAuthenticated: Boolean
        get() = auth == "1" && (status == null || status.equals("Active", ignoreCase = true))
}

data class XtreamServerInfo(
    val url: String? = null,
    val port: String? = null,
    @SerializedName("https_port") val httpsPort: String? = null,
    @SerializedName("server_protocol") val serverProtocol: String? = null,
    val timezone: String? = null
)

// ---- get_live_categories / get_vod_categories / get_series_categories ----

data class XtreamCategoryDto(
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("category_name") val categoryName: String? = null,
    @SerializedName("parent_id") val parentId: String? = null
)

// ---- get_live_streams ----

data class XtreamLiveStreamDto(
    val num: String? = null,
    val name: String? = null,
    @SerializedName("stream_type") val streamType: String? = null,
    @SerializedName("stream_id") val streamId: String? = null,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("epg_channel_id") val epgChannelId: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("tv_archive") val tvArchive: String? = null
)

// ---- get_vod_streams ----

data class XtreamVodStreamDto(
    val num: String? = null,
    val name: String? = null,
    @SerializedName("stream_type") val streamType: String? = null,
    @SerializedName("stream_id") val streamId: String? = null,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = null,
    val rating: String? = null
)

// ---- get_series ----

data class XtreamSeriesDto(
    val num: String? = null,
    val name: String? = null,
    @SerializedName("series_id") val seriesId: String? = null,
    val cover: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    val plot: String? = null,
    val rating: String? = null
)

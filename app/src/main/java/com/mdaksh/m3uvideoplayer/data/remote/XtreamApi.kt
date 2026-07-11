package com.mdaksh.m3uvideoplayer.data.remote

import com.mdaksh.m3uvideoplayer.data.remote.dto.XtreamAuthResponse
import com.mdaksh.m3uvideoplayer.data.remote.dto.XtreamCategoryDto
import com.mdaksh.m3uvideoplayer.data.remote.dto.XtreamLiveStreamDto
import com.mdaksh.m3uvideoplayer.data.remote.dto.XtreamSeriesDto
import com.mdaksh.m3uvideoplayer.data.remote.dto.XtreamVodStreamDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Xtream Codes "player_api.php" endpoint. Every call is stateless — username/password
 * are sent as query params on every request, there's no session token. The base URL
 * (scheme://host:port/) is different per provider, so this interface is instantiated
 * dynamically per playlist via [XtreamApiFactory] instead of a single fixed Retrofit
 * singleton.
 */
interface XtreamApi {

    /** No "action" param = login/auth check. Returns account + server info. */
    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String
    ): XtreamAuthResponse

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): List<XtreamCategoryDto>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null
    ): List<XtreamLiveStreamDto>

    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<XtreamCategoryDto>

    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null
    ): List<XtreamVodStreamDto>

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): List<XtreamCategoryDto>

    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null
    ): List<XtreamSeriesDto>
}

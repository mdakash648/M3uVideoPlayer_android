package com.mdaksh.m3uvideoplayer.data.remote

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each Xtream Codes provider has its own domain/port, so a single fixed-baseUrl Retrofit
 * instance doesn't work here (unlike a typical single-backend app). This factory builds
 * one [XtreamApi] per unique base URL and caches it so repeated syncs of the same
 * playlist don't rebuild Retrofit/OkHttp every time.
 */
@Singleton
class XtreamApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private val cache = ConcurrentHashMap<String, XtreamApi>()

    fun create(baseUrl: String): XtreamApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return cache.getOrPut(normalized) {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(XtreamApi::class.java)
        }
    }
}

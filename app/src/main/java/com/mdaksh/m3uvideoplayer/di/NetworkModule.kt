package com.mdaksh.m3uvideoplayer.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Xtream Codes providers each have their own base URL, so there's no single fixed-baseUrl
 * Retrofit instance here. Instead we provide the shared OkHttpClient/Gson pieces and let
 * [XtreamApiFactory] build a per-provider Retrofit/XtreamApi on demand (see Step 5 of the
 * implementation plan).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().setLenient().create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // XtreamApiFactory itself is NOT provided here — it has its own @Inject constructor
    // (OkHttpClient + Gson from above), so Hilt builds it automatically. Adding a manual
    // @Provides for it here as well would cause a duplicate-binding error.
}

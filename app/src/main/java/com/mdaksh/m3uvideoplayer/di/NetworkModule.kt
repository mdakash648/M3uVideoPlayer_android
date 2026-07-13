package com.mdaksh.m3uvideoplayer.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mdaksh.m3uvideoplayer.data.time.TrustedTimeSource
import com.mdaksh.m3uvideoplayer.data.time.TrustedTimeTrustManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Xtream Codes providers each have their own base URL, so there's no single fixed-baseUrl
 * Retrofit instance here. Instead we provide the shared OkHttpClient/Gson pieces and let
 * [XtreamApiFactory] build a per-provider Retrofit/XtreamApi on demand (see Step 5 of the
 * implementation plan).
 *
 * The OkHttpClient uses a [TrustedTimeTrustManager] to validate SSL certificates against
 * the network-anchored trusted time from [TrustedTimeSource]. This ensures HTTPS connections
 * work even when the device's system clock is wrong (common on cheap Android TV / Firestick
 * boxes) — certificates that are actually valid won't be rejected just because the device
 * thinks it's the wrong year.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().setLenient().create()

    @Provides
    @Singleton
    fun provideOkHttpClient(trustedTimeSource: TrustedTimeSource): OkHttpClient {
        // Get the platform's default trust manager (validates CA chain, cert signatures, etc.)
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val defaultTrustManager = trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        // Wrap it with our trusted-time-aware manager that re-validates cert dates
        // using network time when the device clock is wrong.
        val trustedTimeTrustManager = TrustedTimeTrustManager(
            defaultTrustManager = defaultTrustManager,
            trustedTimeProvider = { trustedTimeSource }
        )

        // Build an SSLContext using our custom trust manager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustedTimeTrustManager), null)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustedTimeTrustManager)
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

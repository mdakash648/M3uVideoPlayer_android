package com.mdaksh.m3uvideoplayer.data.time

import android.util.Log
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * A [X509TrustManager] wrapper that validates server certificates using the network-anchored
 * [TrustedTimeSource] instead of the device's system clock.
 *
 * ## Why this exists
 * When the device date/time is wrong (common on cheap Android TV / Firestick boxes), standard
 * SSL/TLS handshakes fail because the certificates appear expired or not-yet-valid relative to the
 * wrong device clock. This blocks **all** HTTPS traffic — playlist syncs, Xtream API calls, etc.
 *
 * This wrapper intercepts the certificate validation and, when the default check fails, re-validates
 * certificate date ranges using the **correct** time from [TrustedTimeSource]. If the certificates
 * are valid against trusted time, the connection proceeds normally even though the device clock is
 * nonsense.
 *
 * The [TrustedTimeSource] reference is accessed lazily (via lambda) to break the circular dependency
 * between OkHttpClient and TrustedTimeSource during DI construction.
 */
class TrustedTimeTrustManager(
    private val defaultTrustManager: X509TrustManager,
    private val trustedTimeProvider: () -> TrustedTimeSource?
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            defaultTrustManager.checkServerTrusted(chain, authType)
        } catch (e: Exception) {
            // The default check failed — this might be because the device clock is wrong.
            // Try re-validating certificate dates using trusted time.
            val trustedTimeSource = trustedTimeProvider()
            val trustedTime = trustedTimeSource?.now()

            if (trustedTime != null && trustedTime.verified) {
                val trustedDate = java.util.Date(trustedTime.epochMs)
                try {
                    for (cert in chain) {
                        cert.checkValidity(trustedDate)
                    }
                    // Certificates are valid with trusted time — the failure was due to wrong
                    // device clock. Allow the connection.
                    Log.d(TAG, "Certificate valid with trusted time (device clock is wrong)")
                    return
                } catch (certEx: Exception) {
                    // Certificate is genuinely invalid even with trusted time — re-throw original
                    Log.w(TAG, "Certificate invalid even with trusted time: ${certEx.message}")
                    throw e
                }
            }

            // No verified trusted time available — can't help; re-throw original error
            throw e
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        defaultTrustManager.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers

    companion object {
        private const val TAG = "TrustedTimeTrustMgr"
    }
}

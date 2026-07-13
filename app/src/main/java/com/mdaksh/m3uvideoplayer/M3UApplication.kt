package com.mdaksh.m3uvideoplayer

import android.app.Application
import com.mdaksh.m3uvideoplayer.data.time.TimeZoneDetector
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class M3UApplication : Application() {

    @Inject
    lateinit var timeZoneDetector: TimeZoneDetector

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Detect the user's real timezone from their IP on every cold start.
        // This runs in the background and silently falls back to the device timezone if offline.
        appScope.launch { timeZoneDetector.detect() }
    }
}

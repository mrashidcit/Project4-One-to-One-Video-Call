package com.mrashidcit.project4one_to_onevideocall

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's entry point. Registered in AndroidManifest.xml as android:name=".CallApplication".
 * This is where the @Singleton dependency graph (WebRtcClient, CallRepositoryImpl,
 * WebSocketSignalingClient, OkHttpClient, ...) is rooted for the whole app process.
 */
@HiltAndroidApp
class CallApplication : Application()

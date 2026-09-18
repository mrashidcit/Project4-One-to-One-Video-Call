package com.mrashidcit.project4one_to_onevideocall.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the application-level [CoroutineScope] provided below - a scope that outlives any
 * single Activity/ViewModel, used by [com.mrashidcit.project4one_to_onevideocall.data.repository.CallRepositoryImpl]
 * and [com.mrashidcit.project4one_to_onevideocall.data.signaling.WebSocketSignalingClient] so
 * that signaling/WebRTC callbacks keep working across configuration changes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** App-wide, non-WebRTC dependencies: the OkHttp client and the application CoroutineScope. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // WebSocket connections are long-lived; a ping keeps the connection alive through
        // NATs/proxies and lets OkHttp detect a dead connection instead of hanging forever
        // (see README > "Unexpected Disconnect").
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

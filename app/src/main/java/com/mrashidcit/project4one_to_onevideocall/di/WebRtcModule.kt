package com.mrashidcit.project4one_to_onevideocall.di

import com.mrashidcit.project4one_to_onevideocall.data.repository.CallRepositoryImpl
import com.mrashidcit.project4one_to_onevideocall.data.signaling.SignalingClient
import com.mrashidcit.project4one_to_onevideocall.data.signaling.WebSocketSignalingClient
import com.mrashidcit.project4one_to_onevideocall.domain.repository.CallRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the signaling + call-coordination interfaces to their implementations.
 *
 * [com.mrashidcit.project4one_to_onevideocall.data.webrtc.WebRtcClient] needs no entry here:
 * it's a concrete class with an @Inject constructor, so Hilt can provide it directly without
 * a binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebRtcModule {

    @Binds
    abstract fun bindSignalingClient(impl: WebSocketSignalingClient): SignalingClient

    @Binds
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository
}

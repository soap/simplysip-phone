package com.simplysip.phone.di

import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.fake.FakeSipEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SipModule {
    // Phase 1 swaps this for the PJSUA2 engine.
    @Provides
    @Singleton
    fun sipEngine(): SipEngine = FakeSipEngine()
}

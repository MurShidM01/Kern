package com.aliedu.kern.agent.notifications

import com.aliedu.kern.feature.agent.AgentTurnNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Android notification implementation to the feature's notifier contract. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentNotificationModule {

    @Binds
    @Singleton
    abstract fun bindAgentTurnNotifier(impl: AndroidAgentTurnNotifier): AgentTurnNotifier
}

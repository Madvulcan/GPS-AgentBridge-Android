package com.madvulcan.gpsagentbridge.di

import android.content.Context
import com.madvulcan.gpsagentbridge.data.SettingsRepository
import com.madvulcan.gpsagentbridge.location.LocationEngine
import com.madvulcan.gpsagentbridge.net.UdpSender
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency graph. Everything is application-scoped (singleton) because:
 *  - [SettingsRepository] holds a DataStore which is single-instance per process
 *  - [UdpSender] reuses a single DatagramSocket across sends
 *  - [LocationEngine] wraps a FusedLocationProviderClient which is cheap to keep around
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideUdpSender(): UdpSender = UdpSender()

    @Provides
    @Singleton
    fun provideLocationEngine(@ApplicationContext context: Context): LocationEngine =
        LocationEngine(context)
}

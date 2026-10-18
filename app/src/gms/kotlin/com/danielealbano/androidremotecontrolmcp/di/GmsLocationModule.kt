package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** gms flavor: binds the Google Play Services Fused [LocationProvider]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GmsLocationModule {
    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: LocationProviderImpl): LocationProvider
}

package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.services.location.FossLocationProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** foss flavor: binds the framework LocationManager-based [LocationProvider] (no Google Play Services). */
@Module
@InstallIn(SingletonComponent::class)
abstract class FossLocationModule {
    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: FossLocationProviderImpl): LocationProvider
}

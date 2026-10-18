package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.services.power.FossBatteryOptimizationManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** foss flavor: binds the settings-list-based [BatteryOptimizationManager] (no special permission). */
@Module
@InstallIn(SingletonComponent::class)
abstract class FossBatteryModule {
    @Binds
    @Singleton
    abstract fun bindBatteryOptimizationManager(impl: FossBatteryOptimizationManagerImpl): BatteryOptimizationManager
}

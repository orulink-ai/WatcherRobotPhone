package com.danielealbano.androidremotecontrolmcp.di

import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Qualifier

/** Qualifier for the IO [CoroutineDispatcher]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Qualifier for the CPU-bound default [CoroutineDispatcher] (e.g. on-device ML inference). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

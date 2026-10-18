package com.danielealbano.androidremotecontrolmcp.services.update

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the installed app's version name. Abstracted behind an interface so logic that depends on
 * it (the update-check coordinator) stays unit-testable without touching the generated `BuildConfig`. */
interface AppVersionProvider {
    /** The installed `versionName` (git-derived, e.g. `1.11.0` or `1.11.0-dev.7+abc1234`). */
    val versionName: String
}

@Singleton
class BuildConfigAppVersionProvider
    @Inject
    constructor() : AppVersionProvider {
        override val versionName: String = BuildConfig.VERSION_NAME
    }

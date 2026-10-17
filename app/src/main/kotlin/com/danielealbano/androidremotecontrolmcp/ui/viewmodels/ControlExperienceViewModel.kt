package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Shares the device control state with the dashboard without coupling it to server configuration. */
@HiltViewModel
class ControlExperienceViewModel
    @Inject
    constructor(
        controlSession: ControlSession,
    ) : ViewModel() {
        val controlState = controlSession.state
    }

package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.danielealbano.androidremotecontrolmcp.ui.screens.ApprovalScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the on-device OAuth approval screen; launched from the approval notification. */
@AndroidEntryPoint
class ApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidRemoteControlMcpTheme {
                ApprovalScreen(onAllHandled = ::finish)
            }
        }
    }
}

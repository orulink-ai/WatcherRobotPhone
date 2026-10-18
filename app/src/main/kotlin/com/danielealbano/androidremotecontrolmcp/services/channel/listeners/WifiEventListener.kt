package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEventFactory
import com.danielealbano.androidremotecontrolmcp.data.model.WifiChannelConfig
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WifiEventListener(
    private val eventDispatcher: EventDispatcher,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var currentConfig: WifiChannelConfig = WifiChannelConfig()
    private val previouslySeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var connectedSsid: String? = null

    private var appContext: Context? = null
    private var scanReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var scanJob: Job? = null
    private var wifiManager: WifiManager? = null
    private var connectivityManager: ConnectivityManager? = null

    fun start(
        config: WifiChannelConfig,
        context: Context,
    ) {
        currentConfig = config
        appContext = context.applicationContext
        wifiManager = context.getSystemService(WifiManager::class.java)
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        // Scan-based events (discovered/lost)
        scanReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    handleScanResults()
                }
            }
        appContext!!.registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
        )

        // Connection-based events
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    val wifiInfo = caps.transportInfo as? android.net.wifi.WifiInfo ?: return
                    val ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: return
                    if (ssid in currentConfig.ssids && connectedSsid != ssid) {
                        connectedSsid = ssid
                        if (currentConfig.notifyOnConnected) {
                            scope.launch {
                                eventDispatcher.dispatch(
                                    ChannelEventFactory.wifi(ssid, "connected", wifiInfo.bssid),
                                )
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    val ssid = connectedSsid ?: return
                    connectedSsid = null
                    if (ssid in currentConfig.ssids && currentConfig.notifyOnDisconnected) {
                        scope.launch {
                            eventDispatcher.dispatch(
                                ChannelEventFactory.wifi(ssid, "disconnected", null),
                            )
                        }
                    }
                }
            }
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)

        // Periodic scan trigger (throttled by Android: ~4/2min foreground, ~1/30min background)
        // WifiManager.startScan() is deprecated since API 28 but there is NO non-deprecated
        // replacement for triggering WiFi scans. Android provides no successor API — apps must
        // either use startScan() or rely solely on passive scan results from the system.
        // This @Suppress is genuinely unavoidable per CLAUDE.md linting suppression rules.
        // Scan-based events are best-effort; connection events are real-time and not deprecated.
        scanJob =
            scope.launch {
                while (isActive) {
                    @Suppress("DEPRECATION")
                    wifiManager?.startScan()
                    delay(SCAN_INTERVAL_MS)
                }
            }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        scanReceiver?.let { receiver -> appContext?.unregisterReceiver(receiver) }
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        scanReceiver = null
        networkCallback = null
        appContext = null
        previouslySeen.clear()
        connectedSsid = null
    }

    fun updateConfig(config: WifiChannelConfig) {
        currentConfig = config
    }

    @Suppress("DEPRECATION") // ScanResult.SSID deprecated API 33; getWifiSsid() returns WifiSsid
    // object not directly comparable to String. No non-deprecated String SSID accessor exists.
    private fun handleScanResults() {
        val cfg = currentConfig
        val results =
            try {
                wifiManager?.scanResults ?: return
            } catch (e: SecurityException) {
                // ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES may have been revoked at runtime.
                android.util.Log.w(TAG, "Wi-Fi scan results unavailable (permission denied)", e)
                return
            }
        val currentSsids =
            results
                .mapNotNull { it.SSID }
                .filter { it in cfg.ssids }
                .toSet()

        // Discovered
        if (cfg.notifyOnDiscovered) {
            for (ssid in currentSsids - previouslySeen) {
                val bssid = results.firstOrNull { it.SSID == ssid }?.BSSID
                scope.launch {
                    eventDispatcher.dispatch(ChannelEventFactory.wifi(ssid, "discovered", bssid))
                }
            }
        }

        // Lost
        if (cfg.notifyOnLost) {
            for (ssid in previouslySeen - currentSsids) {
                scope.launch {
                    eventDispatcher.dispatch(ChannelEventFactory.wifi(ssid, "lost", null))
                }
            }
        }

        previouslySeen.clear()
        previouslySeen.addAll(currentSsids)
    }

    companion object {
        private const val TAG = "MCP:WifiEventListener"
        private const val SCAN_INTERVAL_MS = 120_000L // 2 minutes
    }
}

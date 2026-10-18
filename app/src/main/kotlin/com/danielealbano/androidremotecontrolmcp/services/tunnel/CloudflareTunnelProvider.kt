package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import javax.inject.Inject

/**
 * Cloudflare tunnel provider.
 *
 * Runs the `cloudflared` binary as a child process. Two modes are supported:
 * - [CloudflareTunnelMode.FREE]: a temporary Quick Tunnel with a random
 *   `*.trycloudflare.com` URL (no account or configuration needed).
 * - [CloudflareTunnelMode.TOKEN]: a remotely-managed named tunnel run with a
 *   dashboard token. The tunnel goes Connected as soon as it registers to the
 *   Cloudflare edge — even before any public hostname is configured — and stays
 *   running. Public hostname(s) and their origin service are configured in the
 *   dashboard; cloudflared logs them as `Updated to new configuration` lines,
 *   which we parse to populate the endpoint list. Each endpoint's service is
 *   validated against the local MCP server, but validation is ADVISORY: an
 *   invalid route is flagged (`TunnelEndpoint.valid == false`) for the UI to show
 *   a warning — it never stops the tunnel.
 */
class CloudflareTunnelProvider
    @Inject
    constructor(
        private val binaryResolver: CloudflaredBinaryResolver,
    ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var process: Process? = null
        private var stderrReaderJob: Job? = null
        private var processMonitorJob: Job? = null

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(process == null) { "Tunnel is already running" }

                val binaryPath =
                    binaryResolver.resolve() ?: run {
                        _status.value = TunnelStatus.Error("cloudflared binary not found")
                        return
                    }

                when (config.cloudflareTunnelMode) {
                    CloudflareTunnelMode.FREE -> startFreeTunnel(binaryPath, localPort, config)
                    CloudflareTunnelMode.TOKEN -> startTokenTunnel(binaryPath, localPort, config)
                }
            }
        }

        private fun startFreeTunnel(
            binaryPath: String,
            localPort: Int,
            config: ServerConfig,
        ) {
            _status.value = TunnelStatus.Connecting
            try {
                val args =
                    mutableListOf(
                        binaryPath,
                        "tunnel",
                        "--url",
                        "http://localhost:$localPort",
                        "--output",
                        "json",
                    )
                if (config.cloudflareTunnelExtraArgs.isNotBlank()) {
                    args.addAll(parseCommandLineArguments(config.cloudflareTunnelExtraArgs))
                }
                val pb = ProcessBuilder(args)
                pb.redirectErrorStream(false)
                val proc = pb.start()
                process = proc

                launchStderrReader(proc) { line ->
                    val match = TUNNEL_URL_REGEX.find(line)
                    if (match != null && _status.value is TunnelStatus.Connecting) {
                        val url = match.value
                        Log.i(TAG, "Cloudflare tunnel URL: $url")
                        _status.value =
                            TunnelStatus.Connected(
                                endpoints = listOf(TunnelEndpoint(url = url, valid = true)),
                                providerType = TunnelProviderType.CLOUDFLARE,
                            )
                    }
                }
                startProcessMonitor(proc)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.e(TAG, "Failed to start cloudflared process", e)
                _status.value = TunnelStatus.Error("Failed to start cloudflared: ${e.message}")
                process = null
            }
        }

        private fun startTokenTunnel(
            binaryPath: String,
            localPort: Int,
            config: ServerConfig,
        ) {
            if (config.cloudflareTunnelToken.isEmpty()) {
                _status.value = TunnelStatus.Error("Cloudflare tunnel token is required")
                return
            }

            _status.value = TunnelStatus.Connecting
            try {
                // NOTE: exact argument order; `--output json` MUST come before `run`, and `--url`
                // MUST NOT be passed (it breaks the control stream of a remotely-managed tunnel).
                val args =
                    mutableListOf(
                        binaryPath,
                        "tunnel",
                        "--output",
                        "json",
                        "run",
                        "--token",
                        config.cloudflareTunnelToken,
                    )
                if (config.cloudflareTunnelExtraArgs.isNotBlank()) {
                    args.addAll(parseCommandLineArguments(config.cloudflareTunnelExtraArgs))
                }
                val pb = ProcessBuilder(args)
                pb.redirectErrorStream(false)
                val proc = pb.start()
                process = proc

                launchStderrReader(proc) { line -> handleTokenLine(line, localPort) }
                startProcessMonitor(proc)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.e(TAG, "Failed to start cloudflared process", e)
                _status.value = TunnelStatus.Error("Failed to start cloudflared: ${e.message}")
                process = null
            }
        }

        /**
         * Handles one token-mode stderr line. The tunnel becomes Connected (with no endpoints) as
         * soon as it registers to the edge, so it stays up while the user configures a route; each
         * `Updated to new configuration` push then refreshes the endpoint list. Per-endpoint service
         * validation is ADVISORY — an invalid route is flagged, never terminated. Non-JSON lines and
         * other messages are ignored.
         */
        private fun handleTokenLine(
            line: String,
            localPort: Int,
        ) {
            when (logMessageOf(line)) {
                MSG_REGISTERED -> {
                    if (_status.value is TunnelStatus.Connecting) {
                        _status.value =
                            TunnelStatus.Connected(
                                endpoints = emptyList(),
                                providerType = TunnelProviderType.CLOUDFLARE,
                            )
                    }
                }

                MSG_UPDATED_CONFIG -> {
                    val payload = configPayloadOf(line) ?: return
                    val endpoints =
                        ingressRoutesOf(payload).map { route ->
                            TunnelEndpoint(
                                url = "https://${route.hostname}",
                                valid = isServiceValid(route.service, localPort),
                            )
                        }
                    _status.value =
                        TunnelStatus.Connected(
                            endpoints = endpoints,
                            providerType = TunnelProviderType.CLOUDFLARE,
                        )
                }
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                val proc = process ?: return
                stderrReaderJob?.cancel()
                stderrReaderJob = null
                processMonitorJob?.cancel()
                processMonitorJob = null

                proc.destroy()
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    proc.waitFor()
                } ?: proc.destroyForcibly()

                process = null
                _status.value = TunnelStatus.Disconnected
            }
        }

        /**
         * Launches a coroutine that reads the process stderr line by line and forwards each line to
         * [onLine]. Shared by both modes; mode-specific handling lives in the caller's lambda. The
         * reader is closed on any exit path (`use`) to release the file descriptor.
         */
        private fun launchStderrReader(
            proc: Process,
            onLine: (String) -> Unit,
        ) {
            stderrReaderJob =
                scope.launch {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                            while (isActive) {
                                @Suppress("BlockingMethodInNonBlockingContext")
                                val line = reader.readLine() ?: break
                                Log.d(TAG, "cloudflared: $line")
                                onLine(line)
                            }
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        if (isActive) {
                            Log.w(TAG, "Error reading cloudflared stderr", e)
                        }
                    }
                }
        }

        private fun startProcessMonitor(proc: Process) {
            processMonitorJob =
                scope.launch {
                    // Give the process a moment to start before monitoring exit
                    delay(PROCESS_MONITOR_INITIAL_DELAY_MS)

                    @Suppress("BlockingMethodInNonBlockingContext")
                    val exitCode = proc.waitFor()

                    if (isActive && _status.value !is TunnelStatus.Disconnected) {
                        Log.w(TAG, "cloudflared process exited unexpectedly with code $exitCode")
                        _status.value =
                            TunnelStatus.Error(
                                "cloudflared process exited unexpectedly (code $exitCode)",
                            )
                        mutex.withLock {
                            process = null
                        }
                    }
                }
        }

        /** A Cloudflare ingress rule that declares a public hostname. */
        internal data class IngressRoute(
            val hostname: String,
            val service: String,
        )

        companion object {
            private const val TAG = "MCP:CloudflareTunnel"
            internal val TUNNEL_URL_REGEX =
                Regex("https://[-a-zA-Z0-9]+\\.trycloudflare\\.com")
            internal const val SHUTDOWN_TIMEOUT_MS = 5_000L
            private const val PROCESS_MONITOR_INITIAL_DELAY_MS = 1_000L
            internal const val MSG_REGISTERED = "Registered tunnel connection"
            internal const val MSG_UPDATED_CONFIG = "Updated to new configuration"
            internal val LOG_JSON = Json { ignoreUnknownKeys = true }

            /** Returns the top-level `message` field of a cloudflared JSON log line, or null for non-JSON. */
            internal fun logMessageOf(line: String): String? =
                runCatching {
                    LOG_JSON
                        .parseToJsonElement(line)
                        .jsonObject["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()

            /** Returns the escaped `config` payload of an `Updated to new configuration` line, or null. */
            internal fun configPayloadOf(line: String): String? =
                runCatching {
                    LOG_JSON
                        .parseToJsonElement(line)
                        .jsonObject["config"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()

            /** Parses the nested config JSON into the ingress entries that declare a hostname. */
            internal fun ingressRoutesOf(configJson: String): List<IngressRoute> =
                runCatching {
                    LOG_JSON
                        .parseToJsonElement(configJson)
                        .jsonObject["ingress"]
                        ?.jsonArray
                        .orEmpty()
                        .mapNotNull { element ->
                            val obj = element.jsonObject
                            val host = obj["hostname"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            IngressRoute(host, obj["service"]?.jsonPrimitive?.contentOrNull ?: "")
                        }
                }.getOrDefault(emptyList())

            /**
             * True when the routed service points at our MCP server: `http://(localhost|127.0.0.1):<port>`.
             * Scheme and host are compared case-insensitively (RFC 3986); any service carrying userinfo,
             * a query, or a fragment is rejected (cloudflared never emits those for an origin service).
             */
            internal fun isServiceValid(
                service: String,
                expectedPort: Int,
            ): Boolean {
                val uri = runCatching { URI(service) }.getOrNull() ?: return false
                val host = uri.host?.lowercase()
                val path = uri.path
                val isLoopback = host == "localhost" || host == "127.0.0.1"
                val pathIsRoot = path.isNullOrEmpty() || path == "/"
                val hasNoExtraParts = uri.userInfo == null && uri.query == null && uri.fragment == null
                return uri.scheme?.lowercase() == "http" &&
                    isLoopback &&
                    uri.port == expectedPort &&
                    pathIsRoot &&
                    hasNoExtraParts
            }

            /**
             * Parses a raw command-line argument string into discrete tokens,
             * safely handling whitespace and single/double quotes.
             */
            internal fun parseCommandLineArguments(raw: String): List<String> {
                if (raw.isBlank()) return emptyList()
                val args = mutableListOf<String>()
                val current = StringBuilder()
                var inQuote: Char? = null
                var escapeNext = false

                for (ch in raw.trim()) {
                    if (escapeNext) {
                        current.append(ch)
                        escapeNext = false
                    } else if (ch == '\\') {
                        escapeNext = true
                    } else if (inQuote != null) {
                        if (ch == inQuote) {
                            inQuote = null
                        } else {
                            current.append(ch)
                        }
                    } else if (ch == '\'' || ch == '"') {
                        inQuote = ch
                    } else if (ch.isWhitespace()) {
                        if (current.isNotEmpty()) {
                            args.add(current.toString())
                            current.clear()
                        }
                    } else {
                        current.append(ch)
                    }
                }
                if (current.isNotEmpty()) {
                    args.add(current.toString())
                }
                return args
            }
        }
    }

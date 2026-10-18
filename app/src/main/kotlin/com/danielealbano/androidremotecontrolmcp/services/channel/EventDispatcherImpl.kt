package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEvent
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventDispatcherImpl
    @Inject
    constructor(
        private val serverLog: ServerLogRepository,
    ) : EventDispatcher {
        private val _connectionStatus =
            MutableStateFlow<ChannelConnectionStatus>(ChannelConnectionStatus.Idle)
        override val connectionStatus: StateFlow<ChannelConnectionStatus> =
            _connectionStatus.asStateFlow()

        // Serializes the read-decide-write in setStatus: dispatch() and the periodic healthCheck() both
        // update status from Dispatchers.IO concurrently, so the previous-value read, the assignment, and
        // the dedup decision must be atomic or a single outage could log two identical error entries.
        private val statusLock = Any()

        private fun setStatus(status: ChannelConnectionStatus) {
            synchronized(statusLock) {
                val previous = _connectionStatus.value
                _connectionStatus.value = status
                when {
                    status is ChannelConnectionStatus.Error &&
                        (previous as? ChannelConnectionStatus.Error)?.message != status.message -> {
                        serverLog.log(ServerLogEntry.Type.CHANNEL, "Event channel error: ${status.message}")
                    }

                    status is ChannelConnectionStatus.Active && previous is ChannelConnectionStatus.Error -> {
                        serverLog.log(ServerLogEntry.Type.CHANNEL, "Event channel recovered")
                    }
                }
            }
        }

        @Volatile
        private var client: HttpClient? = null

        @Volatile
        private var endpointUrl: String = ""

        @Volatile
        private var authToken: String = ""

        override fun start(
            endpointUrl: String,
            authToken: String,
        ) {
            this.endpointUrl = endpointUrl
            this.authToken = authToken
            // No Logging plugin installed — it would expose the auth token in logs
            client =
                HttpClient(OkHttp) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                    install(io.ktor.client.plugins.HttpTimeout) {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    }
                }
            setStatus(ChannelConnectionStatus.Idle)
            Logger.i(TAG, "Event dispatcher started, endpoint=$endpointUrl")
        }

        override fun stop() {
            client?.close()
            client = null
            setStatus(ChannelConnectionStatus.Idle)
            Logger.i(TAG, "Event dispatcher stopped")
        }

        override suspend fun dispatch(event: ChannelEvent): Result<Unit> =
            withContext(Dispatchers.IO) {
                val httpClient =
                    client
                        ?: return@withContext Result.failure(
                            IllegalStateException("Dispatcher not started"),
                        )
                try {
                    val response: HttpResponse =
                        httpClient.post("$endpointUrl/event") {
                            contentType(ContentType.Application.Json)
                            if (authToken.isNotEmpty()) {
                                header("Authorization", "Bearer $authToken")
                            }
                            setBody(event)
                        }
                    if (response.status.isSuccess()) {
                        setStatus(ChannelConnectionStatus.Active)
                        Result.success(Unit)
                    } else {
                        val msg = "HTTP ${response.status.value}"
                        setStatus(ChannelConnectionStatus.Error(msg))
                        Logger.w(TAG, "Dispatch failed: $msg")
                        Result.failure(IOException(msg))
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    val msg = e.message ?: "Unknown error"
                    setStatus(ChannelConnectionStatus.Error(msg))
                    Logger.w(TAG, "Dispatch error: $msg")
                    Result.failure(e)
                }
            }

        override suspend fun healthCheck(): Result<Unit> =
            withContext(Dispatchers.IO) {
                val httpClient =
                    client
                        ?: return@withContext Result.failure(
                            IllegalStateException("Dispatcher not started"),
                        )
                try {
                    val response: HttpResponse = httpClient.get("$endpointUrl/health")
                    if (response.status.isSuccess()) {
                        setStatus(ChannelConnectionStatus.Active)
                        Result.success(Unit)
                    } else {
                        val msg = "Health check failed: HTTP ${response.status.value}"
                        setStatus(ChannelConnectionStatus.Error(msg))
                        Result.failure(IOException(msg))
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    val msg = e.message ?: "Unreachable"
                    setStatus(ChannelConnectionStatus.Error(msg))
                    Result.failure(e)
                }
            }

        companion object {
            private const val TAG = "MCP:EventDispatcher"
            private const val REQUEST_TIMEOUT_MS = 5_000L
            private const val CONNECT_TIMEOUT_MS = 3_000L
        }
    }

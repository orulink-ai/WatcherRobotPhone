package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("CloudflareTunnelProvider")
class CloudflareTunnelProviderTest {
    private val mockBinaryResolver = mockk<CloudflaredBinaryResolver>()

    private fun createProvider(): CloudflareTunnelProvider = CloudflareTunnelProvider(mockBinaryResolver)

    @Nested
    @DisplayName("start")
    inner class Start {
        @Test
        fun `start with missing binary sets error status`() =
            runTest {
                every { mockBinaryResolver.resolve() } returns null
                val provider = createProvider()

                provider.start(8080, ServerConfig())
                advanceUntilIdle()

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertEquals(
                    "cloudflared binary not found",
                    (status as TunnelStatus.Error).message,
                )
            }

        @Test
        fun `start when already running throws IllegalStateException`() =
            runTest {
                val tempScript = File.createTempFile("fake-cloudflared", ".sh")
                try {
                    tempScript.writeText("#!/bin/sh\nsleep 60\n")
                    tempScript.setExecutable(true)
                    every { mockBinaryResolver.resolve() } returns tempScript.absolutePath

                    val provider = createProvider()
                    provider.start(8080, ServerConfig())

                    val ex =
                        assertThrows<IllegalStateException> {
                            provider.start(8080, ServerConfig())
                        }
                    assertEquals("Tunnel is already running", ex.message)

                    provider.stop()
                } finally {
                    tempScript.delete()
                }
            }
    }

    @Nested
    @DisplayName("stop")
    inner class Stop {
        @Test
        fun `stop when not running is no-op`() =
            runTest {
                val provider = createProvider()

                // Should not throw
                provider.stop()

                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }
    }

    @Nested
    @DisplayName("status")
    inner class Status {
        @Test
        fun `initial status is Disconnected`() {
            val provider = createProvider()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }
    }

    @Nested
    @DisplayName("URL regex")
    inner class UrlRegex {
        @Test
        fun `matches valid trycloudflare URL`() {
            val url = "https://random-words-here.trycloudflare.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertTrue(match != null)
            assertEquals(url, match!!.value)
        }

        @Test
        fun `matches URL with hyphens and numbers`() {
            val url = "https://my-tunnel-123-abc.trycloudflare.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertTrue(match != null)
            assertEquals(url, match!!.value)
        }

        @Test
        fun `matches URL embedded in JSON line`() {
            val json =
                """{"level":"info","time":"2026-02-13T12:00:00Z",""" +
                    """"msg":"https://abc-def.trycloudflare.com registered"}"""
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(json)
            assertTrue(match != null)
            assertEquals("https://abc-def.trycloudflare.com", match!!.value)
        }

        @Test
        fun `does not match non-trycloudflare URL`() {
            val url = "https://example.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertFalse(match != null)
        }

        @Test
        fun `does not match HTTP URL`() {
            val url = "http://random-words.trycloudflare.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertFalse(match != null)
        }

        @Test
        fun `does not match URL with extra subdomain levels`() {
            val url = "https://sub.domain.trycloudflare.com"
            // The regex expects "https://" followed by a single label (no dots)
            // before .trycloudflare.com. "sub.domain" contains a dot so it
            // cannot be matched by [-a-zA-Z0-9]+.
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertFalse(match != null)
        }
    }

    @Nested
    @DisplayName("start with nonexistent binary path")
    inner class StartWithNonexistentBinaryPath {
        @Test
        fun `start with binary path to nonexistent file sets error status`() =
            runTest {
                every { mockBinaryResolver.resolve() } returns "/tmp/nonexistent-cloudflared-binary"
                val provider = createProvider()

                provider.start(8080, ServerConfig())
                advanceUntilIdle()

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue(
                    (status as TunnelStatus.Error).message.contains("Failed to start cloudflared"),
                )
            }
    }

    // --- Token-mode fixtures (real captured shapes) ---

    private val configLineValid =
        """{"config":"{\"ingress\":[{\"hostname\":\"pixel8.example.com\",\"service\":\"http://localhost:8080\"},""" +
            """{\"service\":\"http_status:404\"}]}","level":"info",""" +
            """"message":"Updated to new configuration","time":"t","version":1}"""

    private val configLineMismatch =
        """{"config":"{\"ingress\":[{\"hostname\":\"pixel8.example.com\",\"service\":\"http://localhost:9999\"},""" +
            """{\"service\":\"http_status:404\"}]}","level":"info",""" +
            """"message":"Updated to new configuration","time":"t","version":1}"""

    private val configLineMulti =
        """{"config":"{\"ingress\":[{\"hostname\":\"a.example.com\",\"service\":\"http://localhost:8080\"},""" +
            """{\"hostname\":\"b.example.com\",\"service\":\"http://localhost:8080\"},""" +
            """{\"service\":\"http_status:404\"}]}","level":"info",""" +
            """"message":"Updated to new configuration","time":"t","version":1}"""

    private val registeredLine =
        """{"connIndex":0,"connection":"abc","event":0,"ip":"198.41.192.77","level":"info",""" +
            """"location":"zrh01","message":"Registered tunnel connection","protocol":"quic","time":"t"}"""

    private val nonJsonLine =
        "2026/06/28 21:07:30 failed to sufficiently increase receive buffer size (was: 208 kiB, wanted: 7168 kiB)."

    private val innerConfigMulti =
        """{"ingress":[{"hostname":"a.example.com","service":"http://localhost:8080"},""" +
            """{"hostname":"b.example.com","service":"http://localhost:8080"},""" +
            """{"service":"http_status:404"}]}"""

    private fun tokenConfig() =
        ServerConfig(
            cloudflareTunnelMode = CloudflareTunnelMode.TOKEN,
            cloudflareTunnelToken = "fake-token",
        )

    private fun fakeBinaryEmitting(vararg stderrLines: String): String {
        val script = File.createTempFile("fake-cloudflared", ".sh")
        script.deleteOnExit()
        val sb = StringBuilder("#!/bin/sh\n")
        for (logLine in stderrLines) {
            sb.append("printf '%s\\n' '").append(logLine).append("' >&2\n")
        }
        sb.append("sleep 60\n")
        script.writeText(sb.toString())
        script.setExecutable(true)
        return script.absolutePath
    }

    private suspend fun CloudflareTunnelProvider.awaitStatus(predicate: (TunnelStatus) -> Boolean): TunnelStatus =
        withTimeout(AWAIT_TIMEOUT_MS) { status.first(predicate) }

    /**
     * Creates a fake cloudflared script that records its argv (one argument per line) to
     * [argsFile], then blocks like the real long-running binary. The argv is written to a
     * temp file and atomically moved into place so readers never observe a partial write.
     */
    private fun fakeBinaryRecordingArgs(argsFile: File): String {
        val script = File.createTempFile("fake-cloudflared", ".sh")
        script.deleteOnExit()
        val target = argsFile.absolutePath
        script.writeText(
            "#!/bin/sh\n" +
                "printf '%s\\n' \"\$@\" > \"$target.tmp\"\n" +
                "mv \"$target.tmp\" \"$target\"\n" +
                "sleep 60\n",
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun newArgsFile(): File = File.createTempFile("cloudflared-argv", ".txt").also { it.deleteOnExit() }

    private suspend fun awaitRecordedArgs(argsFile: File): List<String> {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (argsFile.length() == 0L) {
                delay(ARGS_POLL_INTERVAL_MS)
            }
        }
        return argsFile.readLines()
    }

    @Nested
    @DisplayName("token-mode helpers")
    inner class Helpers {
        @Test
        fun `logMessageOf returns message for json line`() {
            assertEquals(
                CloudflareTunnelProvider.MSG_UPDATED_CONFIG,
                CloudflareTunnelProvider.logMessageOf(configLineValid),
            )
        }

        @Test
        fun `logMessageOf returns null for non-json line`() {
            assertEquals(null, CloudflareTunnelProvider.logMessageOf(nonJsonLine))
        }

        @Test
        fun `ingressRoutesOf extracts only hostname entries in order`() {
            val routes = CloudflareTunnelProvider.ingressRoutesOf(innerConfigMulti)
            assertEquals(listOf("a.example.com", "b.example.com"), routes.map { it.hostname })
        }

        @Test
        fun `ingressRoutesOf returns empty for malformed json`() {
            assertTrue(CloudflareTunnelProvider.ingressRoutesOf("{not json").isEmpty())
        }

        @Test
        fun `isServiceValid accepts localhost and loopback ip`() {
            assertTrue(CloudflareTunnelProvider.isServiceValid("http://localhost:8080", 8080))
            assertTrue(CloudflareTunnelProvider.isServiceValid("http://127.0.0.1:8080", 8080))
        }

        @Test
        fun `isServiceValid accepts trailing slash`() {
            assertTrue(CloudflareTunnelProvider.isServiceValid("http://localhost:8080/", 8080))
        }

        @Test
        fun `isServiceValid rejects wrong port`() {
            assertFalse(CloudflareTunnelProvider.isServiceValid("http://localhost:9999", 8080))
        }

        @Test
        fun `isServiceValid rejects https and non-loopback host`() {
            assertFalse(CloudflareTunnelProvider.isServiceValid("https://localhost:8080", 8080))
            assertFalse(CloudflareTunnelProvider.isServiceValid("http://192.168.1.5:8080", 8080))
        }

        @Test
        fun `isServiceValid rejects service with no port`() {
            assertFalse(CloudflareTunnelProvider.isServiceValid("http://localhost", 8080))
        }

        @Test
        fun `isServiceValid accepts uppercase scheme and host`() {
            assertTrue(CloudflareTunnelProvider.isServiceValid("HTTP://LOCALHOST:8080", 8080))
            assertTrue(CloudflareTunnelProvider.isServiceValid("http://Localhost:8080", 8080))
        }

        @Test
        fun `isServiceValid rejects userinfo query and fragment`() {
            assertFalse(CloudflareTunnelProvider.isServiceValid("http://user@localhost:8080", 8080))
            assertFalse(CloudflareTunnelProvider.isServiceValid("http://localhost:8080?x=1", 8080))
            assertFalse(CloudflareTunnelProvider.isServiceValid("http://localhost:8080#frag", 8080))
        }
    }

    @Nested
    @DisplayName("token mode")
    inner class TokenMode {
        @Test
        fun `empty token sets error`() =
            runTest {
                every { mockBinaryResolver.resolve() } returns "/bin/true"
                val provider = createProvider()

                provider.start(8080, ServerConfig(cloudflareTunnelMode = CloudflareTunnelMode.TOKEN))

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertEquals(
                    "Cloudflare tunnel token is required",
                    (status as TunnelStatus.Error).message,
                )
            }

        @Test
        fun `valid config sets Connected with valid endpoint`() =
            runBlocking {
                every { mockBinaryResolver.resolve() } returns fakeBinaryEmitting(registeredLine, configLineValid)
                val provider = createProvider()

                provider.start(8080, tokenConfig())
                val status = provider.awaitStatus { it.connectedWithEndpoints() }

                assertEquals(
                    listOf(TunnelEndpoint("https://pixel8.example.com", valid = true)),
                    (status as TunnelStatus.Connected).endpoints,
                )
                provider.stop()
            }

        @Test
        fun `multiple hostnames all valid`() =
            runBlocking {
                every { mockBinaryResolver.resolve() } returns fakeBinaryEmitting(configLineMulti)
                val provider = createProvider()

                provider.start(8080, tokenConfig())
                val status = provider.awaitStatus { it.connectedWithEndpoints() }

                assertEquals(
                    listOf(
                        TunnelEndpoint("https://a.example.com", valid = true),
                        TunnelEndpoint("https://b.example.com", valid = true),
                    ),
                    (status as TunnelStatus.Connected).endpoints,
                )
                provider.stop()
            }

        @Test
        fun `service mismatch flags endpoint invalid without stopping`() =
            runBlocking {
                every { mockBinaryResolver.resolve() } returns fakeBinaryEmitting(configLineMismatch)
                val provider = createProvider()

                provider.start(8080, tokenConfig())
                val status = provider.awaitStatus { it.connectedWithEndpoints() }

                // Connected (NOT Error) with the misconfigured route flagged invalid.
                assertEquals(
                    listOf(TunnelEndpoint("https://pixel8.example.com", valid = false)),
                    (status as TunnelStatus.Connected).endpoints,
                )
                provider.stop()
            }

        @Test
        fun `registered with no config goes connected with no routes`() =
            runBlocking {
                every { mockBinaryResolver.resolve() } returns fakeBinaryEmitting(registeredLine)
                val provider = createProvider()

                provider.start(8080, tokenConfig())
                val status = provider.awaitStatus { it is TunnelStatus.Connected }

                assertEquals(emptyList<TunnelEndpoint>(), (status as TunnelStatus.Connected).endpoints)
                provider.stop()
            }

        @Test
        fun `re-validation flags endpoint invalid on later push`() =
            runBlocking {
                every { mockBinaryResolver.resolve() } returns
                    fakeBinaryEmitting(configLineValid, configLineMismatch)
                val provider = createProvider()

                provider.start(8080, tokenConfig())
                // Wait for the second (mismatch) push to flip the endpoint to invalid.
                val status =
                    provider.awaitStatus {
                        it is TunnelStatus.Connected && it.endpoints.firstOrNull()?.valid == false
                    }

                assertEquals(
                    listOf(TunnelEndpoint("https://pixel8.example.com", valid = false)),
                    (status as TunnelStatus.Connected).endpoints,
                )
                provider.stop()
            }

        @Test
        fun `non-json lines are ignored`() =
            runBlocking {
                every { mockBinaryResolver.resolve() } returns
                    fakeBinaryEmitting(nonJsonLine, registeredLine, configLineValid)
                val provider = createProvider()

                provider.start(8080, tokenConfig())
                val status = provider.awaitStatus { it.connectedWithEndpoints() }

                assertEquals(
                    listOf(TunnelEndpoint("https://pixel8.example.com", valid = true)),
                    (status as TunnelStatus.Connected).endpoints,
                )
                provider.stop()
            }
    }

    @Nested
    @DisplayName("parseCommandLineArguments")
    inner class ParseCommandLineArguments {
        @Test
        fun `empty or blank string returns empty list`() {
            assertEquals(emptyList<String>(), CloudflareTunnelProvider.parseCommandLineArguments(""))
            assertEquals(emptyList<String>(), CloudflareTunnelProvider.parseCommandLineArguments("   \t  "))
        }

        @Test
        fun `single argument returns single token`() {
            assertEquals(
                listOf("--edge"),
                CloudflareTunnelProvider.parseCommandLineArguments("--edge"),
            )
        }

        @Test
        fun `multiple arguments separated by whitespace`() {
            assertEquals(
                listOf("--edge", "region1.v2.argotunnel.com:7844", "--protocol", "http2"),
                CloudflareTunnelProvider.parseCommandLineArguments(
                    "  --edge   region1.v2.argotunnel.com:7844 \t --protocol http2  ",
                ),
            )
        }

        @Test
        fun `double quotes preserve internal spaces and flags`() {
            assertEquals(
                listOf("--edge", "region1.v2.argotunnel.com:7844", "--custom-flag=hello world"),
                CloudflareTunnelProvider.parseCommandLineArguments(
                    "--edge region1.v2.argotunnel.com:7844 --custom-flag=\"hello world\"",
                ),
            )
        }

        @Test
        fun `single quotes preserve internal spaces and flags`() {
            assertEquals(
                listOf("--edge", "region1.v2.argotunnel.com:7844", "--custom-flag=hello world"),
                CloudflareTunnelProvider.parseCommandLineArguments(
                    "--edge region1.v2.argotunnel.com:7844 --custom-flag='hello world'",
                ),
            )
        }

        @Test
        fun `escaped characters are preserved`() {
            assertEquals(
                listOf("--custom=hello\"world"),
                CloudflareTunnelProvider.parseCommandLineArguments("--custom=hello\\\"world"),
            )
        }
    }

    @Nested
    @DisplayName("extra args wiring")
    inner class ExtraArgsWiring {
        @Test
        fun `free mode appends extra args after fixed args`() =
            runBlocking {
                val argsFile = newArgsFile()
                every { mockBinaryResolver.resolve() } returns fakeBinaryRecordingArgs(argsFile)
                val provider = createProvider()

                provider.start(
                    8080,
                    ServerConfig(cloudflareTunnelExtraArgs = "--edge region1.v2.argotunnel.com:7844 --protocol http2"),
                )
                val argv = awaitRecordedArgs(argsFile)

                assertEquals(
                    listOf(
                        "tunnel",
                        "--url",
                        "http://localhost:8080",
                        "--output",
                        "json",
                        "--edge",
                        "region1.v2.argotunnel.com:7844",
                        "--protocol",
                        "http2",
                    ),
                    argv,
                )
                provider.stop()
            }

        @Test
        fun `token mode appends extra args after fixed args`() =
            runBlocking {
                val argsFile = newArgsFile()
                every { mockBinaryResolver.resolve() } returns fakeBinaryRecordingArgs(argsFile)
                val provider = createProvider()

                provider.start(
                    8080,
                    tokenConfig().copy(
                        cloudflareTunnelExtraArgs = "--edge region1.v2.argotunnel.com:7844 --protocol http2",
                    ),
                )
                val argv = awaitRecordedArgs(argsFile)

                assertEquals(
                    listOf(
                        "tunnel",
                        "--output",
                        "json",
                        "run",
                        "--token",
                        "fake-token",
                        "--edge",
                        "region1.v2.argotunnel.com:7844",
                        "--protocol",
                        "http2",
                    ),
                    argv,
                )
                provider.stop()
            }

        @Test
        fun `free mode with blank extra args launches fixed args only`() =
            runBlocking {
                val argsFile = newArgsFile()
                every { mockBinaryResolver.resolve() } returns fakeBinaryRecordingArgs(argsFile)
                val provider = createProvider()

                provider.start(8080, ServerConfig(cloudflareTunnelExtraArgs = "   "))
                val argv = awaitRecordedArgs(argsFile)

                assertEquals(
                    listOf("tunnel", "--url", "http://localhost:8080", "--output", "json"),
                    argv,
                )
                provider.stop()
            }

        @Test
        fun `token mode with blank extra args launches fixed args only`() =
            runBlocking {
                val argsFile = newArgsFile()
                every { mockBinaryResolver.resolve() } returns fakeBinaryRecordingArgs(argsFile)
                val provider = createProvider()

                provider.start(8080, tokenConfig().copy(cloudflareTunnelExtraArgs = "   "))
                val argv = awaitRecordedArgs(argsFile)

                assertEquals(
                    listOf("tunnel", "--output", "json", "run", "--token", "fake-token"),
                    argv,
                )
                provider.stop()
            }

        @Test
        fun `quoted extra arg arrives as a single argv element`() =
            runBlocking {
                val argsFile = newArgsFile()
                every { mockBinaryResolver.resolve() } returns fakeBinaryRecordingArgs(argsFile)
                val provider = createProvider()

                provider.start(
                    8080,
                    ServerConfig(cloudflareTunnelExtraArgs = "--custom-flag=\"hello world\""),
                )
                val argv = awaitRecordedArgs(argsFile)

                assertEquals(
                    listOf(
                        "tunnel",
                        "--url",
                        "http://localhost:8080",
                        "--output",
                        "json",
                        "--custom-flag=hello world",
                    ),
                    argv,
                )
                provider.stop()
            }
    }

    companion object {
        private const val AWAIT_TIMEOUT_MS = 10_000L
        private const val ARGS_POLL_INTERVAL_MS = 50L

        /** True for a Connected status that already carries at least one endpoint (route applied). */
        private fun TunnelStatus.connectedWithEndpoints() = this is TunnelStatus.Connected && endpoints.isNotEmpty()
    }
}

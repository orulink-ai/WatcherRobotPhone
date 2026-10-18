package com.danielealbano.androidremotecontrolmcp.services.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GithubReleaseCheckerImpl")
class GithubReleaseCheckerImplTest {
    private fun checkerWith(handler: () -> HttpClient): GithubReleaseCheckerImpl =
        GithubReleaseCheckerImpl(UnconfinedTestDispatcher()).apply { clientProvider = handler }

    private fun jsonClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): () -> HttpClient =
        {
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            }
        }

    @Test
    fun `returns tag and url on success`() =
        runTest {
            val checker =
                checkerWith(
                    jsonClient(
                        """{"tag_name":"v1.11.0","html_url":"https://github.com/x/y/releases/tag/v1.11.0"}""",
                    ),
                )

            val result = checker.fetchLatestRelease()

            assertEquals("v1.11.0", result?.tagName)
            assertEquals("https://github.com/x/y/releases/tag/v1.11.0", result?.htmlUrl)
        }

    @Test
    fun `ignores unknown json fields`() =
        runTest {
            val checker =
                checkerWith(
                    jsonClient(
                        """{"tag_name":"v1.11.0","html_url":"https://x/rel","name":"Release","draft":false}""",
                    ),
                )
            assertEquals("v1.11.0", checker.fetchLatestRelease()?.tagName)
        }

    @Test
    fun `returns null on non-2xx`() =
        runTest {
            val checker = checkerWith(jsonClient("""{"message":"Not Found"}""", HttpStatusCode.NotFound))
            assertNull(checker.fetchLatestRelease())
        }

    @Test
    fun `returns null on malformed body`() =
        runTest {
            val checker = checkerWith(jsonClient("this is not json"))
            assertNull(checker.fetchLatestRelease())
        }

    @Test
    fun `returns null when tag_name is missing`() =
        runTest {
            val checker = checkerWith(jsonClient("""{"html_url":"https://x/rel"}"""))
            assertNull(checker.fetchLatestRelease())
        }

    @Test
    fun `returns null when tag_name is blank`() =
        runTest {
            val checker = checkerWith(jsonClient("""{"tag_name":"   ","html_url":"https://x/rel"}"""))
            assertNull(checker.fetchLatestRelease())
        }

    @Test
    fun `sends the User-Agent and Accept headers GitHub requires`() =
        runTest {
            var userAgent: String? = null
            var accept: String? = null
            val checker =
                checkerWith {
                    HttpClient(MockEngine) {
                        engine {
                            addHandler { request ->
                                userAgent = request.headers[HttpHeaders.UserAgent]
                                accept = request.headers[HttpHeaders.Accept]
                                respond(
                                    """{"tag_name":"v1.11.0","html_url":"https://x/rel"}""",
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        }
                    }
                }

            checker.fetchLatestRelease()

            // GitHub returns 403 to requests without a User-Agent — a regression dropping it must fail here.
            assertNotNull(userAgent)
            assertEquals("android-remote-control-mcp", userAgent)
            assertTrue(accept?.contains("application/vnd.github") == true)
        }

    @Test
    fun `returns null when html_url is missing`() =
        runTest {
            val checker = checkerWith(jsonClient("""{"tag_name":"v1.11.0"}"""))
            assertNull(checker.fetchLatestRelease())
        }

    @Test
    fun `rejects a non-http(s) html_url scheme`() =
        runTest {
            val checker =
                checkerWith(jsonClient("""{"tag_name":"v1.11.0","html_url":"javascript:alert(1)"}"""))
            assertNull(checker.fetchLatestRelease())
        }

    @Test
    fun `returns null when the request throws`() =
        runTest {
            val checker =
                checkerWith {
                    HttpClient(MockEngine) {
                        engine { addHandler { throw IOException("offline") } }
                    }
                }
            assertNull(checker.fetchLatestRelease())
        }
}

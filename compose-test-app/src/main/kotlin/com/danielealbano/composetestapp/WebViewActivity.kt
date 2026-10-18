package com.danielealbano.composetestapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Minimal WebView activity for E2E testing of accessibility tree freshness
 * with non-Compose virtual accessibility nodes.
 *
 * Displays a simple HTML page with "Number: 0" that can be updated via intent:
 *   adb shell am start --activity-single-top \
 *     -n com.danielealbano.composetestapp/.WebViewActivity --ei number 42
 *
 * The number is updated via evaluateJavascript, which modifies the DOM without
 * reloading the page. WebView virtual accessibility nodes may return stale data
 * if not refreshed during tree traversal.
 *
 * When launched with the string extra `content=heavy`, it instead loads a large,
 * content-heavy page (hundreds of articles with headings, paragraphs, links, list
 * items and images) used to exercise WebView node collapsing in E2E tests.
 *
 * This activity is `singleTop`, so a relaunch reuses the same instance and fires
 * [onNewIntent] rather than [onCreate]. Both route through [applyIntent] so the
 * displayed page is always determined by the latest intent — a simple-page launch
 * after a heavy-page launch reliably shows the simple page again.
 */
class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate called")
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
        }
        setContentView(webView)
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent called, extras=${intent.extras}")
        applyIntent(intent)
    }

    /**
     * Routes an intent: a `number` extra updates the counter in place via JavaScript (no reload);
     * otherwise the page is (re)loaded — the heavy page when `content=heavy`, the simple counter
     * page otherwise.
     */
    private fun applyIntent(intent: Intent?) {
        when {
            intent?.hasExtra(EXTRA_NUMBER) == true -> updateNumber(intent.getIntExtra(EXTRA_NUMBER, 0))
            intent?.getStringExtra(EXTRA_CONTENT) == CONTENT_HEAVY -> loadHeavyPage()
            else -> loadSimplePage()
        }
    }

    private fun loadSimplePage() {
        Log.i(TAG, "loading simple counter page")
        webView?.loadData(SIMPLE_HTML, "text/html", "UTF-8")
    }

    private fun loadHeavyPage() {
        Log.i(TAG, "loading heavy page")
        webView?.loadDataWithBaseURL(null, buildHeavyHtml(), "text/html", "UTF-8", null)
    }

    private fun updateNumber(newNumber: Int) {
        Log.i(TAG, "updating WebView to number=$newNumber")
        webView?.evaluateJavascript(
            "document.getElementById('counter').textContent = 'Number: $newNumber';",
        ) { result ->
            Log.i(TAG, "evaluateJavascript result=$result")
        }
    }

    /**
     * Builds a large HTML document with [ARTICLE_COUNT] articles, each containing a heading,
     * two paragraphs, a link, an inline image and a two-item list. This produces well over a
     * thousand WebView accessibility nodes, exercising the node-collapsing path.
     */
    private fun buildHeavyHtml(): String {
        val body = StringBuilder()
        body.append("<h1>Daily Digest</h1>")
        for (i in 1..ARTICLE_COUNT) {
            body.append("<article>")
            body.append("<h2>Story number $i headline</h2>")
            body.append("<p>First paragraph describing the details of story number $i in full.</p>")
            body.append("<p>Second paragraph with additional context for story number $i.</p>")
            body.append("<img src=\"$PIXEL_DATA_URI\" alt=\"Illustration for story $i\" width=\"32\" height=\"32\">")
            body.append("<a href=\"https://example.invalid/story/$i\">Read full story number $i</a>")
            body.append("<ul><li>Highlight A of story $i</li><li>Highlight B of story $i</li></ul>")
            body.append("</article>")
        }
        return "<html><body>$body</body></html>"
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WebViewTestApp"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_CONTENT = "content"
        private const val CONTENT_HEAVY = "heavy"
        private const val ARTICLE_COUNT = 200

        private val SIMPLE_HTML =
            """
            <html>
            <body style="display:flex;justify-content:center;align-items:center;height:100vh;margin:0;">
                <span id="counter" style="font-size:48px;">Number: 0</span>
            </body>
            </html>
            """.trimIndent()

        /** 1x1 transparent PNG so each article has a real <img> accessibility node, offline. */
        private const val PIXEL_DATA_URI =
            "data:image/png;base64," +
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }
}

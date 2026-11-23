package com.vitrinevideo.app.bridge

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Serves bundled mockup HTML/CSS/JS/WebP/MP3 assets securely over https://appassets.vitrinevideo.local/
 * with HTTP 206 Partial Content Range support so <audio> guide seeking (-10s / slider / +10s) works smoothly.
 */
class AssetWebViewClient(
    private val context: Context,
    private val onPageReady: () -> Unit
) : WebViewClient() {

    companion object {
        const val HOST = "appassets.vitrinevideo.local"
        const val ENTRY_URL = "https://$HOST/mockup/vitrine-video-maquette.html"
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        // Block external navigation inside the app WebView
        return uri.host != HOST
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val uri: Uri = request?.url ?: return null
        if (uri.scheme != "https" || uri.host != HOST) return null

        val rawPath = uri.path?.trimStart('/') ?: return null
        if (rawPath.contains("..")) return null
        val assetPath = rawPath.ifEmpty { "mockup/vitrine-video-maquette.html" }

        val mime = guessMimeType(assetPath)
        val rangeHeader = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value

        return try {
            if (!rangeHeader.isNullOrBlank() && assetPath.endsWith(".mp3", ignoreCase = true)) {
                buildRangeResponse(assetPath, mime, rangeHeader)
            } else {
                val stream: InputStream = context.assets.open(assetPath)
                val headers = mutableMapOf<String, String>()
                if (assetPath.endsWith(".mp3", ignoreCase = true)) {
                    headers["Accept-Ranges"] = "bytes"
                }
                WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream)
            }
        } catch (_: Throwable) {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream("Asset not found: $assetPath".toByteArray())
            )
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageReady()
    }

    private fun buildRangeResponse(assetPath: String, mime: String, rangeHeader: String): WebResourceResponse {
        val bytes = context.assets.open(assetPath).use { input ->
            val buf = ByteArrayOutputStream()
            input.copyTo(buf, 16 * 1024)
            buf.toByteArray()
        }
        val total = bytes.size
        val match = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader.trim())
        val start = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val reqEnd = match?.groupValues?.getOrNull(2)?.toIntOrNull()
        val clampedStart = start.coerceIn(0, (total - 1).coerceAtLeast(0))
        val clampedEnd = (reqEnd ?: (total - 1)).coerceIn(clampedStart, (total - 1).coerceAtLeast(0))
        val length = (clampedEnd - clampedStart + 1).coerceAtLeast(0)
        val slice = bytes.copyOfRange(clampedStart, clampedStart + length)
        val headers = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $clampedStart-$clampedEnd/$total",
            "Content-Length" to length.toString()
        )
        return WebResourceResponse(mime, null, 206, "Partial Content", headers, ByteArrayInputStream(slice))
    }

    private fun guessMimeType(path: String): String {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".html") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

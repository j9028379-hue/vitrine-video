package com.vitrinevideo.app.bridge

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale

internal interface AssetDataSource {
    fun openStream(assetPath: String): InputStream
    fun getUncompressedLength(assetPath: String): Long?
}

internal class AndroidAssetDataSource(
    private val assets: AssetManager
) : AssetDataSource {
    override fun openStream(assetPath: String): InputStream = assets.open(assetPath)

    override fun getUncompressedLength(assetPath: String): Long? {
        return try {
            assets.openFd(assetPath).use { afd ->
                afd.length.takeIf { it >= 0L }
            }
        } catch (_: FileNotFoundException) {
            // Compressed inside APK: fallback to reading exact size when needed
            null
        } catch (_: Throwable) {
            null
        }
    }
}

internal data class ParsedByteRange(
    val start: Long,
    val end: Long,
    val valid: Boolean
)

internal data class AssetHttpPayload(
    val statusCode: Int,
    val reasonPhrase: String,
    val mimeType: String,
    val encoding: String?,
    val headers: Map<String, String>,
    val body: InputStream
)

internal class AssetResponseBuilder(
    private val dataSource: AssetDataSource
) {
    fun build(
        assetPath: String,
        method: String = "GET",
        rangeHeader: String? = null
    ): AssetHttpPayload {
        val mime = guessMimeType(assetPath)
        val encoding = textEncodingForMime(mime)
        val isHead = method.equals("HEAD", ignoreCase = true)
        val isAudio = assetPath.endsWith(".mp3", ignoreCase = true)

        if (!rangeHeader.isNullOrBlank() && isAudio) {
            return buildRangePayload(assetPath, mime, encoding, rangeHeader, isHead)
        }

        val knownLength = if (isAudio) resolveExactLength(assetPath) else dataSource.getUncompressedLength(assetPath)
        val headers = LinkedHashMap<String, String>()
        if (isAudio) {
            headers["Accept-Ranges"] = "bytes"
            headers["Cache-Control"] = "no-transform"
        }
        if (knownLength != null && knownLength >= 0L) {
            headers["Content-Length"] = knownLength.toString()
        }

        val stream = if (isHead) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            dataSource.openStream(assetPath)
        }

        return AssetHttpPayload(
            statusCode = 200,
            reasonPhrase = "OK",
            mimeType = mime,
            encoding = encoding,
            headers = headers,
            body = stream
        )
    }

    private fun buildRangePayload(
        assetPath: String,
        mime: String,
        encoding: String?,
        rangeHeader: String,
        isHead: Boolean
    ): AssetHttpPayload {
        val total = resolveExactLength(assetPath)
        if (total <= 0L) {
            return AssetHttpPayload(
                statusCode = 416,
                reasonPhrase = "Range Not Satisfiable",
                mimeType = "text/plain",
                encoding = "UTF-8",
                headers = mapOf("Content-Range" to "bytes */0"),
                body = ByteArrayInputStream(ByteArray(0))
            )
        }

        val parsed = parseByteRange(rangeHeader, total)
        if (!parsed.valid) {
            return AssetHttpPayload(
                statusCode = 416,
                reasonPhrase = "Range Not Satisfiable",
                mimeType = "text/plain",
                encoding = "UTF-8",
                headers = mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes */$total"
                ),
                body = ByteArrayInputStream(ByteArray(0))
            )
        }

        val length = (parsed.end - parsed.start + 1L).coerceAtLeast(0L)
        val headers = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-transform",
            "Content-Range" to "bytes ${parsed.start}-${parsed.end}/$total",
            "Content-Length" to length.toString()
        )

        val stream: InputStream = if (isHead || length == 0L) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            val raw = dataSource.openStream(assetPath)
            try {
                skipFully(raw, parsed.start)
                BoundedInputStream(raw, length)
            } catch (t: Throwable) {
                raw.close()
                throw t
            }
        }

        return AssetHttpPayload(
            statusCode = 206,
            reasonPhrase = "Partial Content",
            mimeType = mime,
            encoding = encoding,
            headers = headers,
            body = stream
        )
    }

    private fun resolveExactLength(assetPath: String): Long {
        dataSource.getUncompressedLength(assetPath)?.let { return it }
        return dataSource.openStream(assetPath).use { input ->
            var count = 0L
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                count += read
            }
            count
        }
    }

    companion object {
        fun parseByteRange(rangeHeader: String, totalLength: Long): ParsedByteRange {
            if (totalLength <= 0L) return ParsedByteRange(0L, -1L, false)
            val trimmed = rangeHeader.trim()
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) {
                return ParsedByteRange(0L, -1L, false)
            }
            val firstSpec = trimmed.substring(6).split(',').firstOrNull()?.trim().orEmpty()
            val match = Regex("^(\\d*)-(\\d*)$").find(firstSpec)
                ?: return ParsedByteRange(0L, -1L, false)
            val startRaw = match.groupValues[1]
            val endRaw = match.groupValues[2]

            if (startRaw.isEmpty() && endRaw.isEmpty()) {
                return ParsedByteRange(0L, -1L, false)
            }

            if (startRaw.isEmpty()) {
                // Suffix range: bytes=-N (last N bytes)
                val suffix = endRaw.toLongOrNull() ?: return ParsedByteRange(0L, -1L, false)
                if (suffix <= 0L) return ParsedByteRange(0L, -1L, false)
                val start = (totalLength - suffix).coerceAtLeast(0L)
                return ParsedByteRange(start, totalLength - 1L, true)
            }

            val start = startRaw.toLongOrNull() ?: return ParsedByteRange(0L, -1L, false)
            if (start < 0L || start >= totalLength) {
                return ParsedByteRange(0L, -1L, false)
            }

            val end = if (endRaw.isEmpty()) {
                totalLength - 1L
            } else {
                val reqEnd = endRaw.toLongOrNull() ?: return ParsedByteRange(0L, -1L, false)
                if (reqEnd < start) return ParsedByteRange(0L, -1L, false)
                reqEnd.coerceAtMost(totalLength - 1L)
            }

            return ParsedByteRange(start, end, true)
        }

        private fun skipFully(stream: InputStream, bytesToSkip: Long) {
            var remaining = bytesToSkip
            val discard = ByteArray(8192)
            while (remaining > 0L) {
                val skipped = stream.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                    continue
                }
                val toRead = remaining.coerceAtMost(discard.size.toLong()).toInt()
                val read = stream.read(discard, 0, toRead)
                if (read < 0) break
                remaining -= read
            }
        }

        fun guessMimeType(path: String): String {
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

        fun textEncodingForMime(mime: String): String? {
            return when (mime) {
                "text/html",
                "text/css",
                "text/plain",
                "application/javascript",
                "application/json",
                "image/svg+xml" -> "UTF-8"
                else -> null
            }
        }
    }
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private var remaining: Long
) : FilterInputStream(delegate) {
    override fun read(): Int {
        if (remaining <= 0L) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val maxRead = len.toLong().coerceAtMost(remaining).toInt()
        val count = delegate.read(b, off, maxRead)
        if (count > 0) remaining -= count
        return count
    }

    override fun available(): Int {
        return delegate.available().toLong().coerceAtMost(remaining).toInt()
    }
}

/**
 * Serves bundled mockup HTML/CSS/JS/WebP/MP3 assets securely over https://appassets.vitrinevideo.local/
 * with exact Content-Length on 200 OK, binary encoding=null for media, and RFC-compliant HTTP 206 Range support.
 */
class AssetWebViewClient(
    private val context: Context,
    private val onPageReady: () -> Unit
) : WebViewClient() {

    private val responseBuilder by lazy {
        AssetResponseBuilder(AndroidAssetDataSource(context.assets))
    }

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

        val rangeHeader = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
        val method = request.method ?: "GET"

        return try {
            val payload = responseBuilder.build(assetPath, method, rangeHeader)
            WebResourceResponse(
                payload.mimeType,
                payload.encoding,
                payload.statusCode,
                payload.reasonPhrase,
                payload.headers,
                payload.body
            )
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
}

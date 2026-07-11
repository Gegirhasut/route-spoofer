package com.routespoofer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.IOException

/**
 * Holds the route file the app was opened with, until the web layer picks it up.
 *
 * Route Spoofer is registered as a handler for `.json` route files (the VIEW / SEND
 * intent filters in AndroidManifest.xml), so an exported route can be re-imported
 * straight from a file manager, Downloads, Drive or a share sheet. The activity reads
 * the file here, natively — the WebView cannot open a `content://` URI itself — and JS
 * drains it via `FakeGps.consumePendingImport()` on launch and on every resume.
 */
object RouteImport {
    private const val TAG = "RouteImport"

    /** A route export is a few KB; anything this big is not one, so never read it into RAM. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    @Volatile
    private var pending: String? = null

    /** Read the route file [intent] points at (if any) and park it for the web layer. */
    fun capture(
        context: Context,
        intent: Intent?,
    ) {
        val uri = routeUri(intent) ?: return
        val text = readText(context, uri)
        if (!text.isNullOrBlank()) pending = text
    }

    /** Hand the parked file to the caller, exactly once. */
    fun consume(): String? {
        val text = pending
        pending = null
        return text
    }

    private fun routeUri(intent: Intent?): Uri? =
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> sharedUri(intent)
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun sharedUri(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    /**
     * Read [uri] as UTF-8 text, or null if it can't be read or is too large to be a route
     * export. Every failure here is a bad/withdrawn URI from another app, so it is logged
     * and swallowed: the import simply doesn't happen.
     */
    private fun readText(
        context: Context,
        uri: Uri,
    ): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(MAX_BYTES)
                var read = 0
                while (read < MAX_BYTES) {
                    val n = stream.read(buf, read, MAX_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                // Filled the cap: the file is larger than any export. Refuse it rather than
                // hand the parser a truncated, half-valid document.
                if (read >= MAX_BYTES) null else String(buf, 0, read, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not read route file $uri", e)
            null
        } catch (e: SecurityException) {
            // No read grant for the URI (a stale share, or a provider that revoked it).
            Log.w(TAG, "no permission to read route file $uri", e)
            null
        } catch (e: IllegalArgumentException) {
            // Malformed / unknown-provider URI handed to us by the sending app.
            Log.w(TAG, "unusable route file uri $uri", e)
            null
        }
}

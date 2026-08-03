package com.voiceid.app.media

import android.content.Context
import java.io.File

/**
 * File-based local cache for downloaded/recorded media blobs, keyed by messageId — the
 * Android equivalent of src/lib/MediaCache.ts (IndexedDB in the web client). Ensures the
 * sender always has an instant local copy of their own message (BACKEND_README.md §7.1 step 5)
 * and recipients don't re-download media they've already cached (§7.3 step 1).
 *
 * ROOT CAUSE FIX ("Could not load media: Media API error (404)" on a friend's older message):
 * this used to live under context.cacheDir, which Android is free to wipe at any time under
 * storage pressure — unlike the web client's IndexedDB-backed MediaCache.ts, which persists
 * normally. Because the backend deletes each media object from B2 as soon as the recipient's
 * FIRST successful download acks it (api/ack.ts — by design, one-time-view media), once the
 * local cache entry for an already-viewed message was evicted there was no way to fetch it
 * again: the download-auth call still succeeds, but the actual object is gone, so the GET to
 * B2 404s. Moving this to context.filesDir (private, persistent app storage — cleared only by
 * uninstall or an explicit "Clear storage") makes the local copy behave the same way the web
 * client's IndexedDB copy does, so it's this cache that keeps working, not a fresh download.
 */
class MediaCache(context: Context) {
    private val dir = File(context.filesDir, "media_cache").apply { mkdirs() }

    fun has(messageId: String): Boolean = fileFor(messageId).exists()

    fun get(messageId: String): File? = fileFor(messageId).takeIf { it.exists() }

    fun put(messageId: String, bytes: ByteArray): File {
        val file = fileFor(messageId)
        file.writeBytes(bytes)
        return file
    }

    fun putFile(messageId: String, source: File): File {
        val dest = fileFor(messageId)
        source.copyTo(dest, overwrite = true)
        return dest
    }

    fun remove(messageId: String) {
        fileFor(messageId).delete()
    }

    private fun fileFor(messageId: String) = File(dir, messageId)
}

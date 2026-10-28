package com.labserver.lablink.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches the pictures in replies and keeps them: on disk (cache/media, at most [DISK_MAX]) so a chat opens without fetching
 * again, and decoded in memory for scrolling. A bot's own pictures (`media:<path>`) come from the bot-manager through the tunnel;
 * http(s) pictures on the home domain go through the tunnel too, others straight to the internet; data URLs are decoded in place.
 */
class MediaLoader(
    context: Context,
    private val prefs: Prefs,
    private val tunnel: TunnelController,
    /** Debug builds only: reach this address directly (an emulator talking to a manager forwarded to the Mac). */
    private val direct: (String) -> Boolean = { false },
) {
    private val dir = File(context.cacheDir, "media").apply { mkdirs() }
    private val locks = HashMap<String, Mutex>()
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** The picture decoded to at most [maxPx] on its long side, or null when it cannot be had. */
    suspend fun bitmap(src: String, botId: String, maxPx: Int): Bitmap? {
        val key = "${key(src, botId)}@$maxPx"
        mem.get(key)?.let { return it }
        val f = file(src, botId) ?: return null
        return withContext(Dispatchers.IO) { decode(f, maxPx) }?.also { mem.put(key, it) }
    }

    /** The picture's bytes on disk (fetched first if needed), for the viewer, sharing and saving. */
    suspend fun file(src: String, botId: String): File? {
        val key = key(src, botId)
        val f = File(dir, key)
        if (f.isFile && f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f }
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            if (f.isFile && f.length() > 0) return@withLock f
            withContext(Dispatchers.IO) {
                val bytes = try { fetch(src, botId) } catch (e: Exception) { android.util.Log.w("LabLink", "picture failed: ${e.message}"); null }
                if (bytes == null || bytes.isEmpty()) return@withContext null
                val tmp = File(dir, "$key.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { tmp.delete(); return@withContext null }
                trim()
                f
            }
        }
    }

    private suspend fun fetch(src: String, botId: String): ByteArray? {
        if (src.startsWith("data:image/")) return Base64.decode(src.substringAfter("base64,", ""), Base64.DEFAULT)
        val (url, viaTunnel, auth) = when {
            src.startsWith("media:") -> {
                // the default bot runs in its own profile on the server (…/p/<profile>/v1); a sandbox bot is its id
                val bot = prefs.profileOf(botId)
                Triple(
                    "${prefs.effectiveManagerUrl}/media?bot=${enc(bot)}&path=${enc(src.removePrefix("media:"))}",
                    true, "Bearer ${prefs.managerKey}",
                )
            }
            else -> {
                val host = Regex("^https?://([^/:]+)").find(src)?.groupValues?.get(1).orEmpty()
                Triple(src, host == prefs.domain || host.endsWith("." + prefs.domain), null)
            }
        }
        val builder = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        if (viaTunnel && !direct(url)) {
            val port = tunnel.awaitPort()
            if (port == 0) return null
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        }
        val req = Request.Builder().url(url).apply { if (auth != null) header("Authorization", auth) }.build()
        builder.build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type").orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image/")) return null
            return resp.body.bytes().takeIf { it.size <= MAX_BYTES }
        }
    }

    private fun decode(f: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Oldest pictures go first once the cache grows past [DISK_MAX]. */
    private fun trim() {
        val files = dir.listFiles { x -> x.isFile && !x.name.endsWith(".tmp") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (x in files) {
            if (total <= DISK_MAX) break
            total -= x.length()
            x.delete()
        }
    }

    private fun key(src: String, botId: String): String {
        // a server path means a different file for each bot; a URL is the same picture whoever showed it
        val id = if (src.startsWith("media:")) "$botId|$src" else src
        return MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val DISK_MAX = 200L * 1024 * 1024
        private const val MAX_BYTES = 25 * 1024 * 1024
    }
}

package com.labserver.lablink.net

import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.Listing
import com.labserver.lablink.data.TrashedBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

sealed interface ManagerResult<out T> {
    data class Ok<T>(val value: T) : ManagerResult<T>
    data class Fail(val kind: Kind, val detail: String = "") : ManagerResult<Nothing>

    enum class Kind { NotConnected, NoKey, BadUrl, Auth, LimitReached, TrashFull, NotFound, Invalid, Unreachable, Other }
}

/** Where the manager is and how to reach it. [proxyPort] is the tunnel's loopback proxy (0 = tunnel down). */
class ManagerEndpoint(
    val baseUrl: () -> String,
    val key: () -> String,
    val proxyPort: () -> Int,
    /** Debug builds only: talk to the manager directly (an emulator reaching the Mac). */
    val direct: () -> Boolean = { false },
)

/** The bot-manager's API (see deploy/bot-manager): lists, creates, edits, trashes, restores and purges sandbox bots. */
class ManagerClient(private val ep: ManagerEndpoint) {
    /** True when a call can be made right now (the tunnel is up, or a debug build talks to the manager directly). */
    val available: Boolean get() = ep.proxyPort() != 0 || ep.direct()

    suspend fun list(): ManagerResult<Listing> = call("GET", "/bots", null) { parseListing(it) }

    suspend fun create(name: String, role: String, prompt: String, color: Long): ManagerResult<Bot> =
        call("POST", "/bots", spec(name, role, prompt, color)) { parseBot(JSONObject(it)) }

    suspend fun update(id: String, name: String, role: String, prompt: String, color: Long): ManagerResult<Bot> =
        call("PATCH", "/bots/$id", spec(name, role, prompt, color)) { parseBot(JSONObject(it)) }

    suspend fun trash(id: String): ManagerResult<Unit> = call("DELETE", "/bots/$id", null) { }

    suspend fun restore(id: String): ManagerResult<Bot> = call("POST", "/bots/$id/restore", JSONObject()) { parseBot(JSONObject(it)) }

    suspend fun purge(id: String): ManagerResult<Unit> = call("DELETE", "/bots/$id?purge=1", null) { }

    private fun spec(name: String, role: String, prompt: String, color: Long) =
        JSONObject().put("name", name).put("role", role).put("systemPrompt", prompt).put("color", color)

    private suspend fun <T> call(method: String, path: String, body: JSONObject?, parse: (String) -> T): ManagerResult<T> =
        withContext(Dispatchers.IO) {
            val port = ep.proxyPort()
            val direct = ep.direct()
            when {
                port == 0 && !direct -> return@withContext ManagerResult.Fail(ManagerResult.Kind.NotConnected)
                ep.key().isBlank() -> return@withContext ManagerResult.Fail(ManagerResult.Kind.NoKey)
            }
            val request = try {
                Request.Builder().url(ep.baseUrl() + path)
                    .header("Authorization", "Bearer ${ep.key()}")
                    .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext ManagerResult.Fail(ManagerResult.Kind.BadUrl, ep.baseUrl())
            }
            val client = OkHttpClient.Builder().apply {
                if (!direct) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            }.connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body.string()
                    if (resp.isSuccessful) {
                        try {
                            ManagerResult.Ok(parse(text))
                        } catch (e: Exception) {
                            ManagerResult.Fail(ManagerResult.Kind.Other, "bad reply")
                        }
                    } else {
                        fail(resp.code, text)
                    }
                }
            } catch (e: IOException) {
                ManagerResult.Fail(ManagerResult.Kind.Unreachable, e.message ?: "")
            }
        }

    companion object {
        fun fail(code: Int, text: String): ManagerResult.Fail {
            val err = try {
                JSONObject(text).optJSONObject("error")
            } catch (_: Exception) {
                null
            }
            val message = err?.optString("message").orEmpty()
            val kind = when {
                code == 401 || code == 403 -> ManagerResult.Kind.Auth
                code == 404 -> ManagerResult.Kind.NotFound
                code == 409 && err?.optString("code") == "limit_reached" -> ManagerResult.Kind.LimitReached
                code == 409 && err?.optString("code") == "trash_full" -> ManagerResult.Kind.TrashFull
                code == 400 -> ManagerResult.Kind.Invalid
                code in 502..504 -> ManagerResult.Kind.Unreachable
                else -> ManagerResult.Kind.Other
            }
            return ManagerResult.Fail(kind, message.ifBlank { "HTTP $code" })
        }

        fun parseBot(o: JSONObject) = Bot(
            id = o.getString("id"),
            name = o.getString("name"),
            role = o.optString("role"),
            systemPrompt = o.optString("systemPrompt"),
            color = o.optLong("color", 0xFF4B3FD8),
        )

        fun parseListing(text: String): Listing {
            val o = JSONObject(text)
            val bots = o.getJSONArray("bots")
            val trash = o.getJSONArray("trash")
            val running = mutableSetOf<String>()
            val list = (0 until bots.length()).map { i ->
                val b = bots.getJSONObject(i)
                if (b.optString("sandbox") == "running") running += b.getString("id")
                parseBot(b)
            }
            val trashed = (0 until trash.length()).map { i ->
                val t = trash.getJSONObject(i)
                TrashedBot(parseBot(t), t.optLong("trashedAt"), if (t.has("expiresAt")) t.optLong("expiresAt") else null)
            }
            return Listing(list, running, trashed, o.optInt("limit", 5), o.optInt("trashLimit", 20), o.optInt("trashDays", 7))
        }
    }
}

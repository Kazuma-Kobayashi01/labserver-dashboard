package com.labserver.lablink.net

import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.Catalog
import com.labserver.lablink.data.ModelInfo
import com.labserver.lablink.data.ProviderInfo
import com.labserver.lablink.data.Listing
import com.labserver.lablink.data.TrashedBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
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

    suspend fun models(): ManagerResult<Catalog> = call("GET", "/models", null) { parseCatalog(it) }

    suspend fun create(bot: Bot): ManagerResult<Bot> = call("POST", "/bots", spec(bot)) { parseBot(JSONObject(it)) }

    suspend fun update(bot: Bot): ManagerResult<Bot> = call("PATCH", "/bots/${bot.id}", spec(bot)) { parseBot(JSONObject(it)) }

    suspend fun trash(id: String): ManagerResult<Unit> = call("DELETE", "/bots/$id", null) { }

    suspend fun restore(id: String): ManagerResult<Bot> = call("POST", "/bots/$id/restore", JSONObject()) { parseBot(JSONObject(it)) }

    /** Sends [file] to the bot's sandbox; the result is the path the sandbox sees it at. */
    suspend fun upload(botId: String, displayName: String, file: File): ManagerResult<String> = withContext(Dispatchers.IO) {
        val port = ep.proxyPort()
        val direct = ep.direct()
        when {
            port == 0 && !direct -> return@withContext ManagerResult.Fail(ManagerResult.Kind.NotConnected)
            ep.key().isBlank() -> return@withContext ManagerResult.Fail(ManagerResult.Kind.NoKey)
        }
        val request = try {
            Request.Builder().url(ep.baseUrl() + "/bots/$botId/files")
                .header("Authorization", "Bearer ${ep.key()}")
                .header("X-Filename", URLEncoder.encode(displayName, "UTF-8").replace("+", "%20"))
                .post(file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext ManagerResult.Fail(ManagerResult.Kind.BadUrl, ep.baseUrl())
        }
        val client = OkHttpClient.Builder().apply {
            if (!direct) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        }.connectTimeout(15, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body.string()
                if (resp.isSuccessful) ManagerResult.Ok(JSONObject(text).getString("path")) else fail(resp.code, text)
            }
        } catch (e: IOException) {
            ManagerResult.Fail(ManagerResult.Kind.Unreachable, e.message ?: "")
        }
    }

    /** Answers a guarded action the bot of [profile] is waiting on: once, session, always or deny. */
    suspend fun answerApproval(profile: String, runId: String, requestId: String, choice: String): ManagerResult<Unit> =
        call("POST", "/bots/$profile/runs/$runId/approval", JSONObject().put("choice", choice).apply { if (requestId.isNotEmpty()) put("request_id", requestId) }) { }

    suspend fun purge(id: String): ManagerResult<Unit> = call("DELETE", "/bots/$id?purge=1", null) { }

    private fun spec(b: Bot) = JSONObject().put("name", b.name).put("role", b.role).put("systemPrompt", b.systemPrompt)
        .put("color", b.color).put("modelProvider", b.modelProvider).put("modelId", b.modelId).put("reasoning", b.reasoning)
        .put("sandboxed", b.sandboxed)

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
            modelProvider = o.optString("modelProvider"),
            modelId = o.optString("modelId"),
            reasoning = o.optString("reasoning"),
            sandboxed = o.optBoolean("sandboxed", true),
        )

        fun parseCatalog(text: String): Catalog {
            val providers = JSONObject(text).optJSONArray("providers") ?: return Catalog()
            return Catalog((0 until providers.length()).map { i ->
                val p = providers.getJSONObject(i)
                val models = p.getJSONArray("models")
                ProviderInfo(p.getString("id"), p.optString("name", p.getString("id")), (0 until models.length()).map { j ->
                    val mo = models.getJSONObject(j)
                    val efforts = mo.optJSONArray("efforts")
                    ModelInfo(mo.getString("id"), mo.optString("name", mo.getString("id")),
                        (0 until (efforts?.length() ?: 0)).map { efforts!!.getString(it) })
                })
            })
        }

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

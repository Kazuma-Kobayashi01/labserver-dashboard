package com.labserver.lablink.net

import com.labserver.lablink.data.isSandboxId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class ManagerClientTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var reply = "{}"
    private val seen = mutableListOf<String>()
    private var key = "k".repeat(30)
    private var connected = true

    private val client get() = ManagerClient(ManagerEndpoint(
        baseUrl = { "http://127.0.0.1:${server.address.port}/m/v1" }, key = { key }, proxyPort = { if (connected) 1 else 0 }, direct = { true },
    ))

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            seen += "${ex.requestMethod} ${ex.requestURI} auth=${ex.requestHeaders.getFirst("Authorization")} body=${ex.requestBody.readBytes().decodeToString()}"
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val listing = """{"bots":[{"id":"b0123456789","name":"Ann","role":"r","color":4283215584,"systemPrompt":"p","createdAt":1,"sandbox":"running"},
        {"id":"babcdefabcd","name":"Bo","role":"","color":4278190080,"systemPrompt":"","createdAt":2,"sandbox":"idle"}],
        "trash":[{"id":"b1111111111","name":"Old","role":"","color":4283215584,"systemPrompt":"","createdAt":0,"trashedAt":100,"expiresAt":700}],
        "limit":5,"trashLimit":20,"trashDays":7}"""

    @Test
    fun listParsesBotsRunningAndTrash() = runBlocking {
        reply = listing
        val r = client.list() as ManagerResult.Ok
        assertEquals(listOf("Ann", "Bo"), r.value.bots.map { it.name })
        assertEquals(setOf("b0123456789"), r.value.running)
        assertEquals(4283215584L, r.value.bots[0].color)
        assertEquals("p", r.value.bots[0].systemPrompt)
        assertEquals(700L, r.value.trash.single().expiresAt)
        assertEquals(Triple(5, 20, 7), Triple(r.value.limit, r.value.trashLimit, r.value.trashDays))
        assertTrue(seen.single().startsWith("GET /m/v1/bots auth=Bearer $key"))
    }

    @Test
    fun createSendsTheSpecAndReturnsTheBot() = runBlocking {
        status = 201
        reply = """{"id":"b0123456789","name":"Ann","role":"r","color":1,"systemPrompt":"p"}"""
        val r = client.create(com.labserver.lablink.data.Bot(id = "x", name = "Ann", role = "r", systemPrompt = "p", color = 1)) as ManagerResult.Ok
        assertEquals("b0123456789", r.value.id)
        assertTrue(seen.single().startsWith("POST /m/v1/bots "))
        assertTrue(seen.single().contains("\"name\":\"Ann\""))
        assertTrue(seen.single().contains("\"systemPrompt\":\"p\""))
    }

    @Test
    fun trashAndPurgeUseDelete() = runBlocking {
        client.trash("b0123456789")
        client.purge("b0123456789")
        assertTrue(seen[0].startsWith("DELETE /m/v1/bots/b0123456789 "))
        assertTrue(seen[1].startsWith("DELETE /m/v1/bots/b0123456789?purge=1 "))
    }

    @Test
    fun errorsAreMapped() = runBlocking {
        fun kind(code: Int, body: String): ManagerResult.Kind {
            status = code; reply = body
            return (runBlocking { client.list() } as ManagerResult.Fail).kind
        }
        assertEquals(ManagerResult.Kind.Auth, kind(401, """{"error":{"code":"unauthorized","message":"x"}}"""))
        assertEquals(ManagerResult.Kind.LimitReached, kind(409, """{"error":{"code":"limit_reached","message":"full"}}"""))
        assertEquals(ManagerResult.Kind.TrashFull, kind(409, """{"error":{"code":"trash_full","message":"x"}}"""))
        assertEquals(ManagerResult.Kind.NotFound, kind(404, """{"error":{"code":"not_found","message":"x"}}"""))
        assertEquals(ManagerResult.Kind.Invalid, kind(400, """{"error":{"code":"bad_request","message":"name is required"}}"""))
        assertEquals(ManagerResult.Kind.Unreachable, kind(502, "<html>"))
        assertEquals(ManagerResult.Kind.Other, kind(500, "oops"))
        status = 409; reply = """{"error":{"code":"limit_reached","message":"at most 5 bots"}}"""
        assertEquals("at most 5 bots", (client.list() as ManagerResult.Fail).detail)
    }

    @Test
    fun noCallIsMadeWithoutTunnelOrKey() = runBlocking {
        connected = false
        key = "k".repeat(30)
        val offline = ManagerClient(ManagerEndpoint({ "http://127.0.0.1:${server.address.port}/m/v1" }, { key }, { 0 }))
        assertEquals(ManagerResult.Kind.NotConnected, (offline.list() as ManagerResult.Fail).kind)
        key = ""
        assertEquals(ManagerResult.Kind.NoKey, (client.list() as ManagerResult.Fail).kind)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun modelsParseIntoProvidersModelsAndLevels() = runBlocking {
        reply = """{"generated":1,"providers":[{"id":"deepseek","name":"DeepSeek","models":[{"id":"deepseek-flash","name":"DeepSeek Flash","efforts":["none","high"]}]},
            {"id":"openai-codex","name":"ChatGPT","models":[{"id":"gpt-5.6-sol","name":"GPT-5.6 Sol","efforts":[]}]}]}"""
        val c = (client.models() as ManagerResult.Ok).value
        assertEquals(listOf("DeepSeek", "ChatGPT"), c.providers.map { it.name })
        assertEquals(listOf("none", "high"), c.find("deepseek", "deepseek-flash")!!.efforts)
        assertEquals("GPT-5.6 Sol", c.find("openai-codex", "gpt-5.6-sol")!!.name)
        assertEquals(null, c.find("deepseek", "nope"))
        assertEquals("ChatGPT", c.providerName("openai-codex"))
        assertTrue(seen.single().startsWith("GET /m/v1/models "))
    }

    @Test
    fun createAndUpdateCarryTheBotsDefaultModel() = runBlocking {
        status = 201
        reply = """{"id":"b0123456789","name":"A","modelProvider":"deepseek","modelId":"deepseek-flash","reasoning":"high"}"""
        val bot = com.labserver.lablink.data.Bot(id = "x", name = "A", modelProvider = "deepseek", modelId = "deepseek-flash", reasoning = "high")
        val r = (client.create(bot) as ManagerResult.Ok).value
        assertEquals(Triple("deepseek", "deepseek-flash", "high"), Triple(r.modelProvider, r.modelId, r.reasoning))
        assertTrue(seen.single().contains("\"modelProvider\":\"deepseek\"") && seen.single().contains("\"reasoning\":\"high\""))
    }

    @Test
    fun uploadSendsTheFileWithItsEncodedNameAndReturnsTheSandboxPath() = runBlocking {
        status = 201
        reply = """{"name":"报告 v2.pdf","size":5,"path":"/root/.hermes/attachments/报告 v2.pdf"}"""
        val f = java.io.File.createTempFile("upload", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)); deleteOnExit() }
        val r = client.upload("b0123456789", "报告 v2.pdf", f) as ManagerResult.Ok
        assertEquals("/root/.hermes/attachments/报告 v2.pdf", r.value)
        assertTrue(seen.single().startsWith("POST /m/v1/bots/b0123456789/files auth=Bearer $key"))
    }

    @Test
    fun uploadErrorsAreMapped() = runBlocking {
        val f = java.io.File.createTempFile("upload", ".bin").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }
        status = 413; reply = """{"error":{"code":"too_large","message":"files are limited to 25 MB"}}"""
        assertEquals(ManagerResult.Kind.Other, (client.upload("b0123456789", "x", f) as ManagerResult.Fail).kind)
        status = 401; reply = "{}"
        assertEquals(ManagerResult.Kind.Auth, (client.upload("b0123456789", "x", f) as ManagerResult.Fail).kind)
        connected = false
        val offline = ManagerClient(ManagerEndpoint({ "http://127.0.0.1:${server.address.port}/m/v1" }, { key }, { 0 }))
        assertEquals(ManagerResult.Kind.NotConnected, (offline.upload("b0123456789", "x", f) as ManagerResult.Fail).kind)
    }

    @Test
    fun sandboxIdsAreRecognised() {
        assertTrue(isSandboxId("b0123456789"))
        assertFalse(isSandboxId("lab"))
        assertFalse(isSandboxId("b012345678"))
        assertFalse(isSandboxId("B0123456789"))
        assertFalse(isSandboxId("b0123456789/../x"))
    }
}

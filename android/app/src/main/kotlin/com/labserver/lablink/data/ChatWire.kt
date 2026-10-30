package com.labserver.lablink.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * A conversation as the apps share it through the bot-manager (phone and Mac): its title and times, and the messages with
 * their thinking and commands. Attachments travel as their name and kind only (`kind` in lower case); the file itself
 * stays on the device that sent it.
 */
object ChatWire {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(c: Conversation, messages: List<ChatMessage>): JSONObject {
        val conv = JSONObject().put("id", c.id).put("title", c.title).put("created", c.created).put("updated", c.updated)
            .put("pinned", c.pinned).put("renamed", c.renamed)
        val list = JSONArray(json.encodeToString(messages))
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            val atts = m.optJSONArray("attachments") ?: continue
            for (j in 0 until atts.length()) {
                val a = atts.getJSONObject(j)
                a.put("kind", a.optString("kind").lowercase())
                a.remove("local")
            }
        }
        return JSONObject().put("conversation", conv).put("messages", list)
    }

    /** The shared form back into this app's; attachments sent from another device have no local file here. */
    fun decode(o: JSONObject): Pair<Conversation, List<ChatMessage>> {
        val conv = conversation(o.getJSONObject("conversation"))
        val list = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            // another app may write times with a fraction
            if (m.has("time")) m.put("time", m.optDouble("time").toLong())
            val atts = m.optJSONArray("attachments") ?: continue
            for (j in 0 until atts.length()) {
                val a = atts.getJSONObject(j)
                a.put("kind", a.optString("kind").replaceFirstChar { it.uppercase() })
                if (!a.has("local")) a.put("local", "")
                if (!a.has("id")) a.put("id", java.util.UUID.randomUUID().toString())
            }
        }
        return conv to json.decodeFromString<List<ChatMessage>>(list.toString())
    }

    fun conversation(c: JSONObject) = Conversation(
        id = c.getString("id"), title = c.optString("title"), created = c.optLong("created"), updated = c.optLong("updated"),
        pinned = c.optBoolean("pinned"), renamed = c.optBoolean("renamed"),
    )
}

/** What the server holds of one chat: its times, or that it was deleted. */
data class RemoteChat(val id: String, val updated: Long, val deleted: Boolean)

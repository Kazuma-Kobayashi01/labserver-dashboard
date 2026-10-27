package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One thing that has been sent, as listed in the library. [botId] is the bot it was last sent to (where its sandbox copy lives). */
@Serializable
data class LibraryItem(val attachment: Attachment, val botId: String, val sentAt: Long)

enum class LibraryFilter { All, Images, Files }

fun List<LibraryItem>.filtered(f: LibraryFilter): List<LibraryItem> = when (f) {
    LibraryFilter.All -> this
    LibraryFilter.Images -> filter { it.attachment.kind == AttachmentKind.Image }
    LibraryFilter.Files -> filter { it.attachment.kind != AttachmentKind.Image }
}

/**
 * The library after [sent] went to [botId] at [now]: new things are added, things already there move to the front with the
 * bot they went to this time. The stored file is what identifies a thing, so sending the same one again never stores a second copy.
 */
fun mergeSent(library: List<LibraryItem>, sent: List<Attachment>, botId: String, now: Long): List<LibraryItem> {
    if (sent.isEmpty()) return library
    val locals = sent.map { it.local }.toSet()
    val fresh = sent.distinctBy { it.local }.map { LibraryItem(it, botId, now) }
    return fresh + library.filterNot { it.attachment.local in locals }
}

/** The library as a small JSON file, newest first. */
class LibraryStore(dir: File) {
    private val file = File(dir, "library.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<LibraryItem> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun save(items: List<LibraryItem>) {
        try {
            file.writeText(json.encodeToString(items))
        } catch (_: Exception) {
        }
    }
}

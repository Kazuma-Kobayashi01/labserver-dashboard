package com.labserver.lablink.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.util.UUID

/** Why a file could not be attached; the screen turns these into words. */
enum class AttachProblem { TooLarge, Unreadable, TooManyImages, NoSandbox, UploadFailed }

class AttachException(val problem: AttachProblem, message: String = problem.name) : IOException(message)

/**
 * The attached files, kept in the app's private storage. Pictures are shrunk to [Limits.IMAGE_EDGE] pixels and re-encoded as
 * JPEG on the way in (that is what the model gets, and what keeps the chat light); everything else is copied as it is.
 */
class AttachmentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val dir = File(context.applicationContext.filesDir, "attachments").apply { mkdirs() }

    fun file(a: Attachment) = File(dir, a.local)
    fun read(a: Attachment): ByteArray? = try { file(a).readBytes() } catch (_: IOException) { null }
    fun delete(a: Attachment) { file(a).delete() }
    fun delete(list: List<Attachment>) = list.forEach(::delete)

    /** Copies (and for pictures, shrinks) what [uri] points at. Blocking: call it off the main thread. */
    fun import(uri: Uri): Attachment {
        val (name, declared) = describe(uri)
        val mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val head = try {
            resolver.openInputStream(uri)?.use { it.readNBytes(8192) } ?: throw AttachException(AttachProblem.Unreadable)
        } catch (e: SecurityException) { throw AttachException(AttachProblem.Unreadable) }
        val size = if (declared >= 0) declared else head.size.toLong()
        val kind = classify(name, mime, size, looksLikeText(head, truncated = size > head.size))
        val id = UUID.randomUUID().toString()
        return when (kind) {
            AttachmentKind.Image -> importImage(uri, id, name) ?: copyAs(uri, id, name, mime, AttachmentKind.File)
            else -> copyAs(uri, id, name, mime, kind)
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") return (uri.lastPathSegment ?: "file") to (uri.path?.let { File(it).length() } ?: -1L)
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
                return name to (if (c.isNull(1)) -1L else c.getLong(1))
            }
        }
        return (uri.lastPathSegment ?: "file") to -1L
    }

    private fun copyAs(uri: Uri, id: String, name: String, mime: String, kind: AttachmentKind): Attachment {
        val out = File(dir, id)
        var total = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > Limits.MAX_FILE_BYTES) throw AttachException(AttachProblem.TooLarge)
                        o.write(buf, 0, n)
                    }
                }
            } ?: throw AttachException(AttachProblem.Unreadable)
        } catch (e: IOException) {
            out.delete()
            throw if (e is AttachException) e else AttachException(AttachProblem.Unreadable)
        }
        if (total == 0L) { out.delete(); throw AttachException(AttachProblem.Unreadable) }
        // a text file that turned out too big for inlining is an ordinary file now
        val finalKind = if (kind == AttachmentKind.Text && total > Limits.MAX_TEXT_BYTES) AttachmentKind.File else kind
        return Attachment(id, name, mime, total, finalKind, id)
    }

    /** Returns null when the picture cannot be decoded (the caller then sends it as a plain file). */
    private fun importImage(uri: Uri, id: String, name: String): Attachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Limits.IMAGE_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val turn = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        var bmp = decoded
        val scale = minOf(1f, Limits.IMAGE_EDGE.toFloat() / maxOf(bmp.width, bmp.height))
        if (scale < 1f || turn != 0) {
            val m = Matrix().apply { postRotate(turn.toFloat()); postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        // JPEG has no transparency: a see-through PNG would turn black, so it is put on white
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) } }
        val file = File(dir, "$id.jpg")
        file.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return Attachment(id, name, "image/jpeg", file.length(), AttachmentKind.Image, "$id.jpg")
    }

    private fun exifRotation(input: java.io.InputStream): Int = try {
        when (android.media.ExifInterface(input).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) { 0 }

    /** A small bitmap for the chat, decoded from the stored picture. */
    fun thumbnail(a: Attachment, edge: Int): Bitmap? {
        val f = file(a)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A file Uri the camera app can write to, inside this app's cache (shared through the FileProvider). */
    fun newCameraFile(context: Context): Pair<File, Uri> {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        val f = File(folder, "camera-${System.currentTimeMillis()}.jpg")
        return f to androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }
}

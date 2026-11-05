package io.github.nielstron.brushkin.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AtomicFile
import io.github.nielstron.brushkin.engine.Ora
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

const val WHATSAPP_MIN_STICKERS = 3
const val WHATSAPP_MAX_STICKERS = 30

data class Sticker(val file: String, val emoji: String)
data class StickerPack(val id: String, val name: String, val stickers: List<Sticker>, val version: String)

/** Private, durable storage: WhatsApp reads imported packs again after Brushkin closes. */
class StickerStore(context: Context) {
    private val root = File(context.filesDir, "stickers")
    fun packs(): List<StickerPack> = root.listFiles().orEmpty().filter { File(it, "pack.json").exists() }
        .sortedBy { it.name }.map { read(it) }
    fun pack(id: String): StickerPack = read(directory(id))
    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid sticker pack" }
        return File(root, id)
    }
    private fun read(dir: File): StickerPack {
        val json = JSONObject(AtomicFile(File(dir, "pack.json")).openRead().bufferedReader().use { it.readText() })
        val items = json.getJSONArray("stickers")
        return StickerPack(dir.name, json.getString("name"), List(items.length()) { i ->
            items.getJSONObject(i).let { Sticker(it.getString("file"), it.getString("emoji")) }
        }, json.getString("version"))
    }
    private fun write(pack: StickerPack) {
        val items = JSONArray()
        pack.stickers.forEach { items.put(JSONObject().put("file", it.file).put("emoji", it.emoji)) }
        val json = JSONObject().put("name", pack.name).put("version", pack.version).put("stickers", items)
        val atomic = AtomicFile(File(directory(pack.id), "pack.json"))
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) }
        catch (t: Throwable) { atomic.failWrite(out); throw t }
    }
    fun add(source: Bitmap, packId: String?, name: String, emoji: String): StickerPack {
        require(name.isNotBlank() && name.length <= 128) { "Enter a pack name (up to 128 characters)" }
        require(emoji.isNotBlank()) { "Choose an emoji" }
        val old = packId?.let { pack(it) } ?: StickerPack(UUID.randomUUID().toString(), name.trim(), emptyList(), "0")
        val bitmap = stickerBitmap(source)
        try {
            val bytes = encodeSticker(bitmap, 100_000)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = "$hash.webp"
            if (old.stickers.any { it.file == file }) return old
            require(old.stickers.size < WHATSAPP_MAX_STICKERS) { "This pack is full; create a new pack" }
            val dir = directory(old.id).apply { mkdirs() }
            File(dir, file).writeBytes(bytes)
            if (old.stickers.isEmpty()) {
                val tray = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                try { File(dir, "tray.webp").writeBytes(encodeSticker(tray, 50_000)) } finally { tray.recycle() }
            }
            val next = old.copy(stickers = old.stickers + Sticker(file, emoji), version = UUID.randomUUID().toString())
            write(next)
            return next
        } finally { bitmap.recycle() }
    }
    fun remove(id: String, file: String): StickerPack {
        val old = pack(id)
        require(old.stickers.any { it.file == file })
        val next = old.copy(stickers = old.stickers.filterNot { it.file == file }, version = UUID.randomUUID().toString())
        write(next)
        File(directory(id), file).delete()
        return next
    }
    fun asset(id: String, name: String): File {
        val p = pack(id)
        require(name == "tray.webp" || p.stickers.any { it.file == name }) { "Unknown sticker" }
        return File(directory(id), name)
    }
}

/** Crop transparent margins and fit without distortion, leaving transparent padding for chat backgrounds. */
fun stickerBitmap(source: Bitmap): Bitmap {
    val row = IntArray(source.width)
    var left = source.width; var top = source.height; var right = -1; var bottom = -1
    for (y in 0 until source.height) {
        source.getPixels(row, 0, source.width, 0, y, source.width, 1)
        for (x in row.indices) if ((row[x] ushr 24) != 0) {
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
    }
    require(right >= left) { "The image is empty; draw or select something first" }
    val src = Rect(left, top, right + 1, bottom + 1)
    val scale = 480f / maxOf(src.width(), src.height())
    val w = src.width() * scale; val h = src.height() * scale
    return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawBitmap(source, src, RectF((512-w)/2, (512-h)/2, (512+w)/2, (512+h)/2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}

fun encodeSticker(bitmap: Bitmap, maxBytes: Int): ByteArray {
    val lossless = Ora.encode(bitmap, "webp")
    if (lossless.size <= maxBytes) return lossless
    for (quality in 95 downTo 5 step 5) {
        val bytes = Ora.encode(bitmap, "webp", quality, false)
        if (bytes.size <= maxBytes) return bytes
    }
    error("This image is too detailed for a sticker; simplify it and try again")
}

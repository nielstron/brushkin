package io.github.nielstron.brushkin.export

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/** WhatsApp's documented sticker-pack protocol; only its sticker-read permission can access it. */
class StickerProvider : ContentProvider() {
    private val store get() = StickerStore(requireNotNull(context))
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val p = uri.pathSegments
        return when {
            p == listOf("metadata") || (p.size == 2 && p[0] == "metadata") -> {
                val packs = if (p.size == 1) store.packs() else listOf(store.pack(p[1]))
                MatrixCursor(arrayOf("sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                    "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email", "sticker_pack_publisher_website",
                    "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website", "image_data_version",
                    "whatsapp_will_not_cache_stickers", "animated_sticker_pack")).apply {
                    packs.forEach { pack -> addRow(arrayOf<Any>(pack.id, pack.name, "Brushkin", "tray.webp",
                        "https://play.google.com/store/apps/details?id=${requireNotNull(context).packageName}", "", "", "", "", "", pack.version, 0, 0)) }
                }
            }
            p.size == 2 && p[0] == "stickers" -> MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")).apply {
                store.pack(p[1]).stickers.forEach { addRow(arrayOf(it.file, it.emoji, "Custom sticker")) }
            }
            else -> throw IllegalArgumentException("Unknown sticker URI")
        }.also { it.setNotificationUri(requireNotNull(context).contentResolver, uri) }
    }
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        require(mode == "r") { "Stickers are read-only" }
        val p = uri.pathSegments
        require(p.size == 3 && p[0] == "stickers_asset")
        val file = store.asset(p[1], p[2])
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
    }
    override fun getType(uri: Uri): String = when (uri.pathSegments.firstOrNull()) {
        "metadata" -> "vnd.android.cursor.${if (uri.pathSegments.size == 1) "dir" else "item"}/vnd.${uri.authority}.metadata"
        "stickers" -> "vnd.android.cursor.dir/vnd.${uri.authority}.stickers"
        "stickers_asset" -> "image/webp"
        else -> throw IllegalArgumentException("Unknown sticker URI")
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

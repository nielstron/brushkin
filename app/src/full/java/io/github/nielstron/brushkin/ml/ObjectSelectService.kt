package io.github.nielstron.brushkin.ml

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs ML Kit subject segmentation in its own process (":ml"). The segmentation model is native
 * code supplied by Google Play services; if it crashes on some device or emulator, only this
 * process dies and the editor reports the failure instead of losing the user's work.
 */
class ObjectSelectService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val segmenter by lazy { ObjectSelect() }

    override fun onCreate() {
        super.onCreate()
        // ML Kit only auto-initializes in the main process.
        com.google.mlkit.common.MlKit.initialize(this)
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what != MSG_SELECT) return@Handler false
        val reply = msg.replyTo
        val data = msg.data
        val input = File(data.getString(KEY_INPUT)!!)
        val output = File(data.getString(KEY_OUTPUT)!!)
        val x = data.getInt(KEY_X); val y = data.getInt(KEY_Y)
        scope.launch {
            val res = Bundle()
            try {
                segmenter.ensureModule(this@ObjectSelectService)
                val bmp = BitmapFactory.decodeFile(input.path)
                val mask = segmenter.select(bmp, x, y)
                if (mask != null) {
                    output.writeBytes(mask.bytes)
                    res.putInt(KEY_W, mask.width); res.putInt(KEY_H, mask.height)
                }
                res.putBoolean(KEY_FOUND, mask != null)
            } catch (t: Throwable) {
                android.util.Log.w("Brushkin", "object selection failed", t)
                res.putString(KEY_ERROR, t.message ?: t.javaClass.simpleName)
            }
            reply.send(Message.obtain(null, MSG_RESULT).apply { this.data = res })
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() { scope.cancel(); segmenter.close(); super.onDestroy() }

    companion object {
        const val MSG_SELECT = 1
        const val MSG_RESULT = 2
        const val KEY_INPUT = "in"; const val KEY_OUTPUT = "out"; const val KEY_X = "x"; const val KEY_Y = "y"
        const val KEY_W = "w"; const val KEY_H = "h"; const val KEY_FOUND = "found"; const val KEY_ERROR = "error"
    }
}

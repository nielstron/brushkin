package io.github.nielstron.brushkin.ml

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

class ObjectSelectUnavailable(msg: String) : Exception(msg)

/** Talks to [ObjectSelectService]; survives the service process crashing. */
class ObjectSelectClient(private val context: Context) {
    private val lock = Mutex()
    private var service: Messenger? = null
    private var connected: CompletableDeferred<Messenger>? = null
    private var pending: CompletableDeferred<android.os.Bundle>? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val m = Messenger(binder)
            service = m
            connected?.complete(m)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            // The :ml process died (e.g. a native crash in the model).
            service = null
            pending?.completeExceptionally(ObjectSelectUnavailable("Object selection crashed on this device"))
        }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name) ; unbind() }
    }

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == ObjectSelectService.MSG_RESULT) pending?.complete(msg.data)
        true
    })

    private suspend fun connect(): Messenger = withContext(Dispatchers.Main) {
        service?.let { return@withContext it }
        val d = CompletableDeferred<Messenger>()
        connected = d
        if (!context.bindService(Intent(context, ObjectSelectService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            throw ObjectSelectUnavailable("Object selection service unavailable")
        }
        withTimeout(10_000) { d.await() }
    }

    private fun unbind() { runCatching { context.unbindService(conn) }; service = null }

    suspend fun select(image: Bitmap, x: Int, y: Int): ObjectSelect.Mask? = lock.withLock {
        val dir = File(context.cacheDir, "ml").apply { mkdirs() }
        val input = File(dir, "in.png"); val output = File(dir, "out.bin")
        withContext(Dispatchers.IO) { input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val m = connect()
        val reply = CompletableDeferred<android.os.Bundle>()
        pending = reply
        withContext(Dispatchers.Main) {
            m.send(Message.obtain(null, ObjectSelectService.MSG_SELECT).apply {
                replyTo = replies
                data = android.os.Bundle().apply {
                    putString(ObjectSelectService.KEY_INPUT, input.path); putString(ObjectSelectService.KEY_OUTPUT, output.path)
                    putInt(ObjectSelectService.KEY_X, x); putInt(ObjectSelectService.KEY_Y, y)
                }
            })
        }
        val res = try { withTimeout(60_000) { reply.await() } } finally { pending = null }
        res.getString(ObjectSelectService.KEY_ERROR)?.let { throw Exception(it) }
        if (!res.getBoolean(ObjectSelectService.KEY_FOUND)) return@withLock null
        val bytes = withContext(Dispatchers.IO) { output.readBytes() }
        ObjectSelect.Mask(bytes, res.getInt(ObjectSelectService.KEY_W), res.getInt(ObjectSelectService.KEY_H))
    }

    fun close() = unbind()
}

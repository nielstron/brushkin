package io.github.nielstron.brushkin

class BrushkinApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        io.github.nielstron.brushkin.engine.NativeGegl.prepare(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        io.github.nielstron.brushkin.engine.NativeGegl.trimMemory(level)
    }
}

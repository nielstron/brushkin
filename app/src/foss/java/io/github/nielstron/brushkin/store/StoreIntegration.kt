package io.github.nielstron.brushkin.store

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.nielstron.brushkin.ui.Cmd

/** No advertising, billing, or Google SDK in the free-software distribution. */
class StoreIntegration(activity: ComponentActivity) {
    val commands: List<Cmd> = emptyList()
    fun start() {}
    fun resume() {}
    fun close() {}
    @Composable fun Banner(modifier: Modifier, covered: Boolean, onHeightChanged: (Int) -> Unit) {}
    @Composable fun PurchaseDialog() {}
}

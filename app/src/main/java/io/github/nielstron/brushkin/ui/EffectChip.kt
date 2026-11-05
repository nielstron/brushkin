package io.github.nielstron.brushkin.ui

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun EffectChip(selected: Boolean, preferences: SharedPreferences, onSelected: (Boolean) -> Unit) {
    var warning by remember { mutableStateOf(false) }
    var dontRemind by remember { mutableStateOf(false) }
    Chip("Effect", selected) {
        if (selected) onSelected(false)
        else if (preferences.getBoolean("hide-live-effect-warning", false)) onSelected(true)
        else warning = true
    }
    if (warning) AlertDialog(
        onDismissRequest = { warning = false },
        title = { Text("Keep as a live effect?") },
        text = {
            Column {
                Text("Live effects stay editable, but recompute when you draw. They can be slow and use a lot of memory. Leave Effect off to merge the filter into the layer.")
                Row(Modifier.clickable { dontRemind = !dontRemind }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dontRemind, { dontRemind = it })
                    Text("Don’t remind me again")
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            preferences.edit().putBoolean("hide-live-effect-warning", dontRemind).apply()
            warning = false
            onSelected(true)
        }) { Text("Use effect") } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text("Cancel") } },
    )
}

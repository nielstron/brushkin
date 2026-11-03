package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ui {
    val ground = Color(0xFF111113)
    val panel = Color(0xFF1B1B1F)
    val panel2 = Color(0xFF242429)
    val border = Color(0xFF2E2E35)
    val text = Color(0xFFECECEE)
    val muted = Color(0xFFA0A0AB)
    val accent = Color(0xFFFFB547)
    val onAccent = Color(0xFF1A1206)
    val accentSoft = Color(0xFF33302A)
    val danger = Color(0xFFE5484D)
    val mono = FontFamily.Monospace
}

@Composable
fun BrushkinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ui.accent, onPrimary = Ui.onAccent, background = Ui.ground, surface = Ui.panel,
            surfaceContainer = Ui.panel, surfaceContainerHigh = Ui.panel2, surfaceContainerLow = Ui.panel,
            onSurface = Ui.text, onSurfaceVariant = Ui.muted, outline = Ui.border, secondaryContainer = Ui.accentSoft,
            onSecondaryContainer = Ui.accent,
        ),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Ui.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = modifier)
}

@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
    onChange: (Float) -> Unit, onDone: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ui.text.copy(alpha = 0.85f), modifier = Modifier.width(92.dp), fontSize = 14.sp)
        Slider(
            value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Ui.text, activeTrackColor = Ui.accent, inactiveTrackColor = Color(0xFF3A3A42)),
        )
        Text(display, color = Ui.text, fontFamily = Ui.mono, fontSize = 12.sp, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Ui.accentSoft else Ui.panel.copy(alpha = 0.94f))
            .border(1.dp, if (selected) Ui.accent else Ui.border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) Ui.accent else Ui.text, modifier = Modifier.size(16.dp))
        Text(text, color = if (selected) Ui.accent else Ui.text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A52)))
    }
}

@Composable
fun SheetColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
}

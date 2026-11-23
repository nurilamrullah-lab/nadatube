package com.nadatube.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nadatube.app.data.SettingsStore
import com.nadatube.app.ui.theme.Nada
import com.nadatube.app.ui.theme.ThemeMode

fun ThemeMode.icon(): ImageVector = when (this) {
    ThemeMode.LIGHT -> Icons.Rounded.LightMode
    ThemeMode.DARK -> Icons.Rounded.DarkMode
    ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
}

/** Header button that shows the active mode and opens the picker. */
@Composable
fun ThemeToggleButton(onClick: () -> Unit, mode: ThemeMode, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp).clip(CircleShape).background(Nada.IndigoSoft),
    ) { Icon(mode.icon(), "Pilih tema: ${mode.label}", tint = Nada.Indigo) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(settings: SettingsStore, onDismiss: () -> Unit) {
    val current by settings.themeMode.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nada.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Tampilan", style = MaterialTheme.typography.titleLarge)
            Text("Pilihan tersimpan dan dipakai lagi saat aplikasi dibuka.", style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
            Spacer(Modifier.height(14.dp))
            ThemeMode.entries.forEach { mode ->
                val selected = mode == current
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, if (selected) Nada.Indigo else Nada.Border, RoundedCornerShape(16.dp))
                        .background(if (selected) Nada.IndigoSoft else Nada.Canvas)
                        .clickable { settings.setThemeMode(mode) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(mode.icon(), null, tint = if (selected) Nada.Indigo else Nada.InkSoft)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.titleSmall, color = Nada.Ink)
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
                    }
                    if (selected) Icon(Icons.Rounded.Check, "Dipilih", tint = Nada.Indigo)
                }
            }
        }
    }
}

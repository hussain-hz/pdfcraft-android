package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

@StringRes
internal fun themeLabel(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.System -> R.string.home_theme_system
    ThemeChoice.Colored -> R.string.home_theme_colored
    ThemeChoice.Light -> R.string.home_theme_light
    ThemeChoice.DarkGray -> R.string.home_theme_gray
    ThemeChoice.Dark -> R.string.home_theme_dark
}

@Composable
internal fun languageLabel(language: String): String = when (language) {
    "ar" -> "العربية"
    "en" -> "English"
    "es" -> "Español"
    else -> stringResource(R.string.home_theme_system)
}

@Composable
internal fun PreferenceSummary(title: String, value: String, onClick: () -> Unit) {
    val c = pdfColors
    androidx.compose.material3.Surface(onClick = onClick, color = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(title, color = c.text, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = c.textSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, subtitle: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = pdfColors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(24.dp)).background(if (c.oled) Color.Black else if (c.dark) Color(0xFF383838) else c.surface)
                .padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                BarIcon(LucideIcons.X, stringResource(R.string.viewer_close), onDismiss)
            }
            Text(subtitle, color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            Column(Modifier.selectableGroup(), content = content)
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, preview: (@Composable () -> Unit)? = null) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (preview != null) {
            preview()
            Spacer(Modifier.width(16.dp))
        }
        Text(label, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(LucideIcons.Check, null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun AppearancePicker(selected: ThemeChoice, onDismiss: () -> Unit, onSelect: (ThemeChoice) -> Unit) {
    ChoiceDialog(stringResource(R.string.home_theme), stringResource(R.string.home_choose_theme), onDismiss) {
        listOf(ThemeChoice.System, ThemeChoice.Colored, ThemeChoice.Light, ThemeChoice.DarkGray, ThemeChoice.Dark).forEach { choice ->
            ChoiceRow(stringResource(themeLabel(choice)), choice == selected, { onSelect(choice) }) { ThemePreview(choice) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ThemePreview(choice: ThemeChoice) {
    val dark = choice == ThemeChoice.Dark || choice == ThemeChoice.DarkGray || (choice == ThemeChoice.System && isSystemInDarkTheme())
    val background = when {
        choice == ThemeChoice.DarkGray -> Color(0xFF292929)
        dark -> Color.Black
        else -> Color.White
    }
    val shape = RoundedCornerShape(7.dp)
    Column(Modifier.size(52.dp, 40.dp).clip(shape).background(background).border(1.dp, Color(0xFF999999), shape)) {
        Box(Modifier.fillMaxWidth().height(9.dp).background(if (choice == ThemeChoice.Colored) Color(0xFF405CC7) else Color(0x22888888)))
        Column(Modifier.fillMaxWidth().padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(if (dark) Color.LightGray else Color.Gray, RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(0.65f).height(3.dp).background(if (dark) Color.Gray else Color.LightGray, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
internal fun LanguagePicker(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ChoiceDialog(stringResource(R.string.home_language), stringResource(R.string.home_choose_language), onDismiss) {
        listOf("", "ar", "en", "es").forEach { language ->
            ChoiceRow(languageLabel(language), language == selected, { onSelect(language) })
            Spacer(Modifier.height(4.dp))
        }
    }
}

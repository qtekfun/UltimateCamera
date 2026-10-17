package com.qtekfun.ultimatecamera.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Settings design (D49): Material 3 list items grouped in rounded tonal cards under icon + title section headers, on
 * the dynamic dark scheme. Rows are whole touch targets (field use, gloves).
 */

/** Large screen title at the top of a settings page. */
@Composable
fun SettingsTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp).semantics { heading() }
    )
}

/** A section: icon + title in the accent colour, then its rows in one rounded card. */
@Composable
fun SettingsSection(title: String, @DrawableRes icon: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(start = 8.dp).semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(content = content)
        }
    }
}

private val itemColors
    @Composable get() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** A row with a switch; the whole row toggles and carries the on/off state for accessibility. */
@Composable
fun SettingsSwitchItem(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = itemColors
    ) { Text(title) }
}

/** A row that opens something (a screen, a system page, a picker), with an optional current value and a chevron. */
@Composable
fun SettingsNavItem(title: String, description: String?, onClick: () -> Unit, value: String? = null, enabled: Boolean = true) {
    ListItem(
        onClick = onClick,
        enabled = enabled,
        supportingContent = description?.let { { Text(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                value?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Icon(painterResource(R.drawable.ic_set_chevron), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        colors = itemColors
    ) { Text(title) }
}

/** A plain text row inside a section (hints, explanations). */
@Composable
fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

package com.homebase.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.homebase.tv.data.settings.TvName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.testSaved() }
    val s = saved ?: return

    var url by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
    var token by remember(s.apiToken) { mutableStateOf(s.apiToken) }
    var tvName by remember(s.tvName) { mutableStateOf(s.tvName) }

    Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Field("Backend URL", url, KeyboardType.Uri) { url = it }
        Field("API token", token, KeyboardType.Password, password = true) { token = it }
        Text("This TV", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvName.entries.forEach { t ->
                FilterChip(selected = t == tvName, onClick = { tvName = t }) { Text(t.label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.saveAndTest(url, token, tvName) }) { Text("Save & test connection") }
            Button(onClick = vm::testSaved) { Text("Test saved connection") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(statusHeadline(status), style = MaterialTheme.typography.titleMedium)
            Text(statusDetail(status), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun statusHeadline(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Idle -> ""
    ConnectionStatus.Testing -> "Checking backend…"
    is ConnectionStatus.Ok -> with(status.health) {
        when {
            !authenticated -> "Connected, but the API token was rejected"
            !database -> "Backend degraded: database unhealthy"
            this.status != "ok" -> "Backend degraded"
            else -> "Backend healthy"
        }
    }
    is ConnectionStatus.Failed -> "Can't reach backend"
}

/**
 * The process caches TimeZone.getDefault() and Android only clears it on a timezone-change broadcast,
 * so a zone applied without one (or before the process cached UTC) would never show up. Re-resolve it.
 */
internal fun deviceZone(): ZoneId {
    TimeZone.setDefault(null)
    return ZoneId.systemDefault()
}

internal fun statusDetail(status: ConnectionStatus, zone: ZoneId = deviceZone()): String = when (status) {
    ConnectionStatus.Idle, ConnectionStatus.Testing -> ""
    is ConnectionStatus.Ok -> with(status.health) {
        listOfNotNull(
            "Version $version",
            "Database ${if (database) "OK" else "unhealthy"}",
            "Token ${if (authenticated) "accepted" else "rejected"}",
            uptimeSeconds?.let { "Up ${uptimeText(it)}" },
            checkedText(status.checkedAt, zone),
        ).joinToString(" · ")
    }
    is ConnectionStatus.Failed -> "${status.message} · ${checkedText(status.checkedAt, zone)}"
}

internal fun uptimeText(seconds: Long): String {
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    val m = seconds % 3_600 / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${seconds}s"
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun checkedText(epochMillis: Long, zone: ZoneId) =
    "Checked " + clock.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Read-only until OK is pressed, so D-pad focus passing through doesn't pop the keyboard.
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            readOnly = !editing,
            singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 18.sp),
            cursorBrush = SolidColor(colors.onSurface),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                editing = false
                keyboard?.hide()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) editing = false
                }
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    when {
                        // Single-line fields otherwise swallow Up/Down as cursor moves, trapping D-pad focus.
                        e.key == Key.DirectionDown || e.key == Key.DirectionUp -> {
                            if (e.type == KeyEventType.KeyDown) {
                                focusManager.moveFocus(if (e.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                            }
                            true
                        }
                        !editing && ok && e.type == KeyEventType.KeyUp -> {
                            editing = true
                            keyboard?.show()
                            true
                        }
                        else -> false
                    }
                }
                .border(2.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}

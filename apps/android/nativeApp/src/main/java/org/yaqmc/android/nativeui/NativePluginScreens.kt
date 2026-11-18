package org.yaqmc.android.nativeui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import org.yaqmc.android.R

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

@UnstableApi
@Composable
internal fun NativePluginManager(model: NativeViewModel) {
    val controller = model.plugins
    val state by controller.state.collectAsStateWithLifecycle()
    var enabling by remember { mutableStateOf<JSONObject?>(null) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var updating by remember { mutableStateOf<JSONObject?>(null) }
    var shortcuts by remember { mutableStateOf(false) }
    val contributions by controller.contributions.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::select) }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(controller::selectDirectory) }
    LaunchedEffect(Unit) { controller.refresh() }
    LazyColumn(Modifier.fillMaxSize().testTag("plugins-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.testTag("plugin-import")) { Text(stringResource(R.string.native_plugin_add)) }
            OutlinedButton(onClick = { model.navigate(NativeRoute("plugin-store")) }) { Text(stringResource(R.string.native_plugin_store)) }
            TextButton(onClick = controller::refresh, enabled = !state.busy) { Text(stringResource(R.string.native_refresh)) }
            TextButton(onClick = { controller.checkUpdates() }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_check_updates)) }
            if (state.resources.optBoolean("developerMode")) TextButton(onClick = { directoryPicker.launch(null) }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_add_directory)) }
            if (contributions.optJSONArray("shortcuts").objects().isNotEmpty()) TextButton(onClick = { shortcuts = true }) { Text(stringResource(R.string.native_plugin_shortcuts)) }
        } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.native_plugin_safe_mode)) },
                supportingContent = { Text(stringResource(R.string.native_plugin_safe_mode_summary)) },
                trailingContent = { Switch(state.resources.optBoolean("safeMode"), null) },
                modifier = Modifier.fillMaxWidth().testTag("plugin-safe-mode").toggleable(state.resources.optBoolean("safeMode"), enabled = !state.busy, role = Role.Switch, onValueChange = controller::safeMode))
        }
        if (state.loaded && state.records.isEmpty()) item { Text(stringResource(R.string.native_plugin_empty)) }
        items(state.records, key = { it.text("id") }) { record ->
            val id = record.text("id")
            OutlinedCard(Modifier.fillMaxWidth().testTag("plugin-card-$id")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.text("name", id), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_plugin_status, record.text("version"), stringResource(pluginStatus(record.text("status")))), style = MaterialTheme.typography.bodySmall)
                    record.text("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    record.text("statusReason").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.optBoolean("enabled") && record.text("status") != "failed") {
                            OutlinedButton(onClick = { controller.setEnabled(id, false, emptySet()) }, enabled = !state.busy, modifier = Modifier.testTag("plugin-disable-$id")) { Text(stringResource(R.string.native_plugin_disable)) }
                        } else {
                            Button(onClick = { enabling = record }, enabled = !state.busy && record.optBoolean("compatible") && !state.resources.optBoolean("safeMode"), modifier = Modifier.testTag("plugin-enable-$id")) { Text(stringResource(if (record.text("status") == "failed") R.string.native_plugin_retry else R.string.native_plugin_enable)) }
                        }
                        if (record.optJSONObject("settingsSchema") != null) TextButton(onClick = { controller.settings(id); editing = record }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_settings)) }
                        TextButton(onClick = { updating = record; controller.updateInfo(id) }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_updates)) }
                        if (record.optBoolean("enabled")) TextButton(onClick = { enabling = record }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_review)) }
                        TextButton(onClick = { removing = record }, enabled = !state.busy, modifier = Modifier.testTag("plugin-remove-$id")) { Text(stringResource(R.string.native_plugin_remove)) }
                        if (state.resources.optBoolean("developerMode") && record.text("unpackedPath").isNotBlank()) TextButton(onClick = { controller.reload(id) }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_reload)) }
                    }
                }
            }
        }
        if (state.updates.isNotEmpty()) item { Text(stringResource(R.string.native_plugin_updates), style = MaterialTheme.typography.titleMedium) }
        items(state.updates, key = { it.text("operationId") }) { operation -> NativePluginUpdate(operation, state.busy, controller) }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.native_plugin_developer_mode)) }, trailingContent = { Switch(state.resources.optBoolean("developerMode"), null) },
                modifier = Modifier.fillMaxWidth().toggleable(state.resources.optBoolean("developerMode"), enabled = !state.busy, role = Role.Switch, onValueChange = controller::developerMode))
        }
    }
    if (shortcuts) NativePluginShortcutsDialog(contributions.optJSONArray("shortcuts").objects(), controller) { shortcuts = false }
    state.inspection?.let { inspection ->
        val manifest = inspection.optJSONObject("manifest") ?: JSONObject()
        PluginPermissionReview(manifest.text("name"), inspection.optJSONArray("permissions").strings(), emptySet(), state.busy,
            stringResource(if (state.records.any { it.text("id") == manifest.text("id") }) R.string.native_plugin_update else R.string.native_plugin_install), controller::dismissInspection, controller::install)
    }
    enabling?.let { record ->
        PluginPermissionReview(record.text("name"), record.optJSONArray("permissions").strings(), record.optJSONArray("grantedPermissions").strings().toSet(), state.busy,
            stringResource(if (record.optBoolean("enabled")) R.string.native_plugin_save else R.string.native_plugin_enable), { enabling = null }) { grants ->
            controller.setEnabled(record.text("id"), true, grants); enabling = null
        }
    }
    removing?.let { record ->
        var removeData by remember(record.text("id")) { mutableStateOf(false) }
        NativeAlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.native_plugin_remove_title, record.text("name"))) },
            text = { Row(Modifier.fillMaxWidth().toggleable(removeData, role = Role.Checkbox, onValueChange = { removeData = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(removeData, null); Text(stringResource(R.string.native_plugin_remove_data))
            } },
            confirmButton = { TextButton(onClick = { controller.uninstall(record.text("id"), removeData); removing = null }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.native_cancel)) } })
    }
    editing?.let { record -> PluginSettingsDialog(record, state, { editing = null }) { values -> controller.saveSettings(record.text("id"), values) { editing = null } } }
    DisposableEffect(editing?.text("id")) {
        val id = editing?.text("id")
        if (id != null) controller.editing(id, true)
        onDispose { if (id != null) controller.editing(id, false) }
    }
    updating?.let { record -> PluginUpdatePolicyDialog(record.text("id"), state, controller) { updating = null } }
}

@UnstableApi
@Composable
private fun NativePluginShortcutsDialog(commands: List<JSONObject>, controller: NativePlugins, close: () -> Unit) {
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    NativeAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.native_plugin_shortcuts)) }, text = {
        LazyColumn { items(commands, key = { "${it.text("pluginId")}/${it.text("id")}" }) { command ->
            ListItem(headlineContent = { Text(command.text("label")) }, supportingContent = { Text(command.text("accelerator").ifBlank { stringResource(R.string.native_plugin_shortcut_disabled) }) }, modifier = Modifier.clickable { selected = command })
        } }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_cancel)) } })
    selected?.let { command ->
        var value by remember(command) { mutableStateOf(command.text("accelerator")) }
        NativeAlertDialog(onDismissRequest = { selected = null }, title = { Text(command.text("label")) }, text = {
            OutlinedTextField(value, { value = it }, singleLine = true, label = { Text(stringResource(R.string.native_plugin_shortcut_binding)) }, placeholder = { Text("control+K") })
        }, confirmButton = { TextButton(onClick = { controller.shortcut(command.text("pluginId"), command.text("id"), value.trim().takeIf(String::isNotEmpty)); selected = null }) { Text(stringResource(R.string.native_plugin_save)) } }, dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.native_cancel)) } })
    }
}

@UnstableApi
@Composable
private fun PluginUpdatePolicyDialog(id: String, state: NativePluginState, controller: NativePlugins, close: () -> Unit) {
    val info = state.updateInfo?.takeIf { it.text("pluginId") == id }
    val policy = info?.optJSONObject("policy")
    var channel by remember(id, policy?.toString()) { mutableStateOf(policy?.text("channel", "stable") ?: "stable") }
    var source by remember(id, policy?.toString()) { mutableStateOf(policy?.text("sourceId").orEmpty()) }
    var automatic by remember(id, policy?.toString()) { mutableStateOf(policy?.optBoolean("automatic") == true) }
    var confirmed by remember(source) { mutableStateOf(false) }
    val sourceChanged = source.isNotBlank() && (source != policy?.text("sourceId") || policy?.optBoolean("bound") != true)
    NativeAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.native_plugin_updates)) }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy || info == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            info?.let {
                val sources = it.optJSONArray("sources").objects()
                if (sources.isEmpty()) item { Text(stringResource(R.string.native_plugin_no_update_source)) }
                else {
                    item { Text(stringResource(R.string.native_plugin_update_source), style = MaterialTheme.typography.titleSmall) }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources.forEach { candidate -> FilterChip(source == candidate.text("id"), onClick = { source = candidate.text("id") }, label = { Text(candidate.text("name")) }) }
                    } }
                    item { PreferenceChoices(stringResource(R.string.native_plugin_update_channel), channel, listOf("stable" to stringResource(R.string.native_plugin_channel_stable), "beta" to stringResource(R.string.native_plugin_channel_beta), "nightly" to stringResource(R.string.native_plugin_channel_nightly))) { channel = it } }
                    item { PreferenceSwitch(stringResource(R.string.native_plugin_automatic_updates), automatic) { automatic = it } }
                    if (sourceChanged) item { Row(Modifier.fillMaxWidth().toggleable(confirmed, role = Role.Checkbox, onValueChange = { confirmed = it }), verticalAlignment = Alignment.CenterVertically) { Checkbox(confirmed, null); Text(stringResource(R.string.native_plugin_confirm_source)) } }
                }
                item { TextButton(onClick = { controller.checkUpdates(id) }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_check_updates)) } }
                items(it.optJSONArray("available").objects(), key = { release -> release.text("releaseId") }) { release ->
                    TextButton(onClick = { controller.prepareUpdate(JSONObject().put("pluginId", id).put("releaseId", release.text("releaseId"))); close() }, enabled = !state.busy) { Text(release.text("version")) }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = info != null && !state.busy && (!sourceChanged || confirmed), onClick = {
        controller.updatePolicy(JSONObject().put("pluginId", id).put("sourceId", source.takeIf(String::isNotBlank) ?: JSONObject.NULL).put("channel", channel).put("automatic", automatic).put("confirmSource", confirmed), close)
    }) { Text(stringResource(R.string.native_plugin_save)) } }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_cancel)) } })
}

private fun pluginStatus(status: String): Int = when (status) {
    "active" -> R.string.native_plugin_active; "enabling", "disabling" -> R.string.native_plugin_starting
    "failed" -> R.string.native_plugin_failed; "incompatible" -> R.string.native_plugin_incompatible; else -> R.string.native_plugin_disabled
}

@Composable
private fun PluginPermissionReview(name: String, permissions: List<String>, granted: Set<String>, busy: Boolean, confirm: String, close: () -> Unit, accept: (Set<String>) -> Unit) {
    var selected by remember(name, permissions, granted) { mutableStateOf(granted) }
    NativeAlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(name) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.native_plugin_source_unverified), style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.native_plugin_review), style = MaterialTheme.typography.titleSmall) }
            if (permissions.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().toggleable(selected.size == permissions.size, enabled = !busy, role = Role.Checkbox, onValueChange = { selected = if (it) permissions.toSet() else emptySet() }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.size == permissions.size, null); Text(stringResource(R.string.native_plugin_permission_all))
                }
            }
            items(permissions, key = { it }) { permission ->
                Row(Modifier.fillMaxWidth().toggleable(permission in selected, enabled = !busy, role = Role.Checkbox, onValueChange = { selected = if (it) selected + permission else selected - permission }).testTag("plugin-permission-$permission"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(permission in selected, null); Text(pluginPermissionLabel(permission), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { accept(selected) }, enabled = !busy, modifier = Modifier.testTag("plugin-review-confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.native_cancel)) } })
}

@UnstableApi
@Composable
private fun PluginSettingsDialog(record: JSONObject, state: NativePluginState, close: () -> Unit, save: (JSONObject) -> Unit) {
    val fields = record.optJSONObject("settingsSchema")?.optJSONArray("fields").objects()
    val values = remember(record.text("id"), state.settings?.toString()) { mutableStateMapOf<String, Any>() }
    val changed = remember(record.text("id"), state.settings?.toString()) { mutableStateMapOf<String, Boolean>() }
    NativeAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.native_plugin_settings)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(fields, key = { it.text("id") }) { field ->
                val id = field.text("id")
                val type = field.text("type", "text")
                val value = values[id] ?: state.settings?.opt(id)?.takeUnless { it === JSONObject.NULL } ?: field.opt("default")?.takeUnless { it === JSONObject.NULL }
                val label = field.text("label", id)
                val secret = type == "password" || field.optBoolean("secret")
                fun edit(next: Any) { values[id] = next; changed[id] = true }
                when (type) {
                    "boolean" -> Row(Modifier.fillMaxWidth().toggleable(value == true, role = Role.Switch, onValueChange = ::edit), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value == true, null) }
                    "select", "multiselect" -> Column {
                        Text(label)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            field.optJSONArray("options").strings().forEach { option ->
                                val selections = (value as? JSONArray).strings().toSet()
                                FilterChip(if (type == "select") value == option else option in selections,
                                    onClick = { edit(if (type == "select") option else JSONArray((if (option in selections) selections - option else selections + option).toList())) }, label = { Text(option) })
                            }
                        }
                    }
                    "slider" -> Column { Text(label); Slider((value as? Number)?.toFloat() ?: field.optDouble("min", 0.0).toFloat(), onValueChange = { edit(it.toDouble()) }, valueRange = field.optDouble("min", 0.0).toFloat()..field.optDouble("max", 100.0).toFloat()) }
                    else -> OutlinedTextField(if (secret && changed[id] != true) "" else value?.toString().orEmpty(), onValueChange = ::edit,
                        label = { Text(label) }, singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().testTag("plugin-setting-$id"))
                }
                field.text("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(enabled = !state.busy, onClick = {
        val patch = JSONObject()
        fields.forEach { field ->
            val id = field.text("id")
            if (changed[id] == true) {
                val value = values[id]
                patch.put(id, if (field.text("type") in setOf("number", "slider")) value.toString().toDoubleOrNull() ?: value else value)
            }
        }
        save(patch)
    }) { Text(stringResource(R.string.native_plugin_save)) } }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_cancel)) } })
}

@UnstableApi
@Composable
internal fun NativePluginPage(model: NativeViewModel, route: NativeRoute) {
    val contributions by model.plugins.contributions.collectAsStateWithLifecycle()
    val page = contributions.optJSONArray("pages").objects().find { it.text("pluginId") == route.pluginId && it.text("id") == route.id }
    DisposableEffect(route.pluginId, route.id) {
        model.plugins.page(route.pluginId, route.id, true)
        onDispose { model.plugins.page(route.pluginId, route.id, false) }
    }
    fun action(id: String) { model.plugins.action(route.pluginId, JSONObject().put("id", id).put("slot", "page").put("pageId", route.id)) }
    LazyColumn(Modifier.fillMaxSize().testTag("plugin-page"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == null) item { Text(stringResource(R.string.native_plugin_page_unavailable)) }
        else {
            item { Text(page.text("title"), style = MaterialTheme.typography.headlineSmall) }
            item { Text(stringResource(R.string.native_plugin_by, page.text("pluginName")), style = MaterialTheme.typography.bodySmall) }
            items(page.optJSONArray("blocks").objects()) { block ->
                when (block.text("type")) {
                    "heading" -> Text(block.text("text"), style = MaterialTheme.typography.titleLarge)
                    "text", "notice" -> Text(block.text("text"), color = when {
                        block.text("level") == "error" -> MaterialTheme.colorScheme.error
                        block.text("tone") == "secondary" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onBackground
                    })
                    "divider" -> HorizontalDivider()
                    "stats" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) { block.optJSONArray("items").objects().forEach { Column { Text(it.text("value"), style = MaterialTheme.typography.titleLarge); Text(it.text("label")) } } }
                    "list" -> Column { block.optJSONArray("items").objects().forEach { item -> ListItem(headlineContent = { Text(item.text("title")) }, supportingContent = { Text(item.text("subtitle")) }, modifier = Modifier.fillMaxWidth().clickable(enabled = item.text("actionId").isNotBlank()) { action(item.text("actionId")) }) } }
                    "actions" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { block.optJSONArray("items").objects().forEach { item ->
                        val modifier = Modifier.testTag("plugin-page-action-${item.text("id")}")
                        if (item.text("variant") == "secondary") OutlinedButton(onClick = { action(item.text("id")) }, modifier = modifier) { Text(item.text("label")) }
                        else Button(onClick = { action(item.text("id")) }, modifier = modifier) { Text(item.text("label")) }
                    } }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun NativePluginUpdate(operation: JSONObject, busy: Boolean, controller: NativePlugins) {
    val preview = operation.optJSONObject("preview")
    val permissions = preview?.optJSONArray("permissions").strings()
    var grants by remember(operation.text("operationId"), preview?.toString()) { mutableStateOf(preview?.optJSONArray("grantedPermissions").strings().toSet()) }
    val changes = preview?.optJSONArray("confirmations").strings() + preview?.optJSONArray("sessionChanges").strings()
    var confirmed by remember(operation.text("operationId"), changes) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(preview?.text("name") ?: operation.text("pluginId"), style = MaterialTheme.typography.titleMedium)
        Text(pluginUpdateLabel(operation.text("phase")))
        preview?.let { Text(stringResource(R.string.native_plugin_release, it.text("version"), it.text("sha256")), style = MaterialTheme.typography.bodySmall) }
        changes.forEach { Text(pluginUpdateLabel(it), style = MaterialTheme.typography.bodySmall) }
        operation.text("error").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val terminal = operation.text("phase") in setOf("completed", "unchanged", "cancelled", "failed")
        if (!terminal && preview != null) {
            permissions.forEach { permission -> Row(Modifier.fillMaxWidth().toggleable(permission in grants, enabled = !busy, role = Role.Checkbox, onValueChange = { grants = if (it) grants + permission else grants - permission }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(permission in grants, null); Text(pluginPermissionLabel(permission), style = MaterialTheme.typography.bodySmall)
            } }
            if (changes.isNotEmpty()) Row(Modifier.fillMaxWidth().toggleable(confirmed, enabled = !busy, role = Role.Checkbox, onValueChange = { confirmed = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(confirmed, null); Text(stringResource(R.string.native_plugin_confirm_changes))
            }
        }
        if (!terminal) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) {
                val request = JSONObject().put("operationId", operation.text("operationId")).put("confirm", changes.isEmpty() || confirmed).put("grant", JSONArray(grants.toList()))
                val enabled = !busy && operation.text("phase") in setOf("prepared", "waiting-idle") && (changes.isEmpty() || confirmed)
                Button(enabled = enabled, onClick = { controller.applyUpdate(request) }) { Text(stringResource(R.string.native_plugin_apply_update)) }
                TextButton(enabled = enabled, onClick = { controller.applyUpdate(JSONObject(request.toString()).put("stopUsing", true)) }) { Text(stringResource(R.string.native_plugin_stop_and_update)) }
            }
            TextButton(enabled = !busy, onClick = { controller.cancelUpdate(operation.text("operationId")) }) { Text(stringResource(R.string.native_cancel)) }
        }
    } }
}

@UnstableApi
@Composable
internal fun NativePluginStore(model: NativeViewModel) {
    val state by model.plugins.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var releases by remember { mutableStateOf<JSONObject?>(null) }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("best-match") }
    var includePrereleases by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.plugins.searchStore("") }
    LazyColumn(Modifier.fillMaxSize().testTag("plugin-store"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.native_plugin_search_hint)) }, modifier = Modifier.fillMaxWidth()) }
        item { PreferenceChoices(stringResource(R.string.native_plugin_search_filter), filter, listOf("all" to stringResource(R.string.native_plugin_filter_all), "name" to stringResource(R.string.native_plugin_filter_name), "description" to stringResource(R.string.native_plugin_filter_description), "author" to stringResource(R.string.native_plugin_filter_author), "repository" to stringResource(R.string.native_plugin_filter_repository))) { filter = it } }
        item { PreferenceChoices(stringResource(R.string.native_plugin_search_sort), sort, listOf("best-match" to stringResource(R.string.native_plugin_sort_match), "stars" to stringResource(R.string.native_plugin_sort_stars), "updated" to stringResource(R.string.native_plugin_sort_updated))) { sort = it } }
        item { Button(onClick = { model.plugins.searchStore(query, filter, sort) }, enabled = !state.busy) { Text(stringResource(R.string.native_search)) } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(state.store, key = { it.text("fullName") }) { repository ->
            ListItem(headlineContent = { Text(repository.text("fullName")) }, supportingContent = { Text(repository.text("description")) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.plugins.storeReleases(JSONObject().put("owner", repository.text("owner")).put("repo", repository.text("repo")).put("includePrereleases", includePrereleases)) { releases = it } })
        }
        if (state.storePage.optBoolean("hasMore")) item { TextButton(onClick = { model.plugins.searchStore(query, filter, sort, state.storePage.optInt("page", 1) + 1) }, enabled = !state.busy) { Text(stringResource(R.string.native_plugin_load_more)) } }
        item { PreferenceSwitch(stringResource(R.string.native_plugin_include_prereleases), includePrereleases) { includePrereleases = it; releases = null } }
        if (state.storePage.optBoolean("stale")) item { Text(stringResource(R.string.native_stale)) }
        releases?.let { page -> items(page.optJSONArray("releases").objects(), key = { it.optLong("id") }) { release ->
            Column { Text(release.text("name", release.text("tagName")), style = MaterialTheme.typography.titleMedium)
                release.optJSONArray("assets").objects().forEach { asset ->
                    TextButton(enabled = !state.busy, onClick = {
                        model.plugins.prepareUpdate(JSONObject().put("github", JSONObject().put("owner", page.text("owner")).put("repo", page.text("repo")).put("releaseId", release.optLong("id")).put("assetId", asset.optLong("id"))))
                        model.back()
                    }) { Text(asset.text("name")) }
                }
            }
        } }
        releases?.takeIf { it.optBoolean("hasMore") }?.let { page -> item { TextButton(enabled = !state.busy, onClick = {
            model.plugins.storeReleases(JSONObject().put("owner", page.text("owner")).put("repo", page.text("repo")).put("includePrereleases", includePrereleases).put("page", page.optInt("page", 1) + 1)) { next ->
                val combined = page.optJSONArray("releases").objects() + next.optJSONArray("releases").objects()
                releases = JSONObject(next.toString()).put("releases", JSONArray(combined.distinctBy { it.optLong("id") }))
            }
        }) { Text(stringResource(R.string.native_plugin_load_more)) } } }
    }
}

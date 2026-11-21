package org.yaqmc.android.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.yaqmc.android.BuildConfig
import androidx.media3.common.util.UnstableApi
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaqmc.android.R
import org.yaqmc.android.DIAGNOSTIC_DESCRIPTION_MAX_BYTES
import org.yaqmc.android.YaqmcApplication
import org.yaqmc.android.androidSupportDetails
import org.yaqmc.android.androidSupportText

internal enum class NativeLogDetail(val label: Int) {
    INFO(R.string.native_diag_info),
    DEBUG(R.string.native_diag_debug),
    TRACE(R.string.native_diag_trace),
    WARN(R.string.native_diag_warn),
    ERROR(R.string.native_diag_error);

    val key: String get() = name.lowercase()
}

internal data class NativeAbnormalExit(val reason: String, val timeMs: Long)

internal data class NativeDiagnosticsState(
    val logLevel: String = "info", val recentErrors: List<String> = emptyList(),
    val lastExit: NativeAbnormalExit? = null,
    val loading: Boolean = false, val busy: Boolean = false, val error: Boolean = false,
)
internal data class NativeDiagnosticUploadState(
    val preparing: Boolean = false, val progress: Int? = null,
    val receipt: String? = null, val error: Int? = null, val canRetry: Boolean = false,
) {
    val busy: Boolean get() = preparing || progress != null
}
internal data class NativeUpdateState(
    val loading: Boolean = false, val checked: Boolean = false,
    val availableVersion: String = "", val releaseUrl: String = "",
    val notes: String = "", val error: Boolean = false,
)

/** Only fixed HTTPS project links and the validated update-checker URL may be opened. */
internal fun openProjectUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal const val FEEDBACK_URL = "https://ltzu.top/yaqmc/feedback"
internal const val GITHUB_ISSUES = "https://github.com/YAQMC/YAQMC-Android/issues/new"

internal fun copyFeedbackReport(context: Context, report: String, openWebsite: Boolean): Int {
    try {
        val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
        val clip = ClipData.newPlainText(context.getString(R.string.native_issue_preview), report)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
    } catch (_: Exception) {
        return R.string.native_issue_copy_failed
    }
    if (!openWebsite) return R.string.native_issue_copied
    return try {
        openProjectUrl(context, FEEDBACK_URL)
        R.string.native_issue_paste_on_site
    } catch (_: Exception) {
        R.string.native_issue_browser_unavailable
    }
}

@UnstableApi
@Composable
internal fun NativeDiagnostics(model: NativeViewModel) {
    val state by model.diagnostics.collectAsStateWithLifecycle()
    val upload by model.diagnosticUpload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmUpload by rememberSaveable { mutableStateOf(false) }
    val idle = !state.busy && !state.loading && !upload.busy
    LaunchedEffect(Unit) { model.loadDiagnostics() }
    LazyColumn(Modifier.fillMaxSize().testTag("diagnostics-list"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error) item { Column {
            Text(stringResource(R.string.native_load_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = model::loadDiagnostics, enabled = idle) { Text(stringResource(R.string.native_refresh)) }
        } }
        item { Button(onClick = { model.navigate(NativeRoute("issue-report")) }) {
            Text(stringResource(R.string.native_diag_report_issue))
        } }
        item { DiagnosticUploadControls(upload, idle, onOpen = { confirmUpload = true }, onRetry = model::retryDiagnosticBundle) }
        item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.native_diag_log_level), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_diag_log_hint), style = MaterialTheme.typography.bodyMedium)
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeLogDetail.entries.forEach { detail ->
                FilterChip(state.logLevel == detail.key, onClick = { model.setDiagnosticsLevel(detail.key) },
                    label = { Text(stringResource(detail.label)) }, enabled = idle,
                    modifier = Modifier.testTag("diagnostics-level-" + detail.key))
            }
        } }
        item { OutlinedButton(onClick = { model.shareDiagnosticBundle(context) }, enabled = idle) {
            Text(stringResource(R.string.native_diag_export))
        } }
        item { OutlinedButton(onClick = { confirmClear = true }, enabled = idle) {
            Text(stringResource(R.string.native_diag_clear_logs))
        } }
        item { Text(stringResource(R.string.native_diag_recent_errors), style = MaterialTheme.typography.titleMedium) }
        state.lastExit?.let { exit -> item {
            val reason = when (exit.reason) {
                "native-crash" -> R.string.native_diag_exit_native_crash
                "anr" -> R.string.native_diag_exit_anr
                "initialization-failure" -> R.string.native_diag_exit_startup
                else -> R.string.native_diag_exit_crash
            }
            val locale = LocalConfiguration.current.locales[0]
            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(exit.timeMs))
            Text(stringResource(R.string.native_diag_last_exit, stringResource(reason), time),
                style = MaterialTheme.typography.bodyMedium)
        } }
        if (state.recentErrors.isEmpty() && state.lastExit == null && !state.loading && !state.error) {
            item { Text(stringResource(R.string.native_diag_no_errors)) }
        }
        items(state.recentErrors.size) { index ->
            Text(state.recentErrors[index], style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmClear) Confirm(stringResource(R.string.native_diag_confirm_clear_logs),
        close = { confirmClear = false }) { model.clearDiagnosticLogs() }
    if (confirmUpload) ConfirmDiagnosticUpload("", close = { confirmUpload = false }, submit = model::submitDiagnosticBundle)
}

@UnstableApi
@Composable
internal fun NativeAbout(model: NativeViewModel) {
    val update by model.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.checkForUpdates() }
    LazyColumn(Modifier.fillMaxSize().testTag("about-list"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.app_name) + " · " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium) }
        item { Text(stringResource(R.string.native_about_build, BuildConfig.YAQMC_BUILD_COMMIT.take(12))) }
        item { Text(stringResource(R.string.native_about_platform, Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull() ?: "-")) }
        if (update.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (update.checked) item {
            val result = when {
                update.error -> stringResource(R.string.native_update_failed)
                update.availableVersion.isNotBlank() -> stringResource(R.string.native_update_available, update.availableVersion)
                else -> stringResource(R.string.native_update_latest)
            }
            Text(result, modifier = Modifier.testTag("update-status"))
        }
        item { OutlinedButton(onClick = model::checkForUpdates, enabled = !update.loading) {
            Text(stringResource(R.string.native_update_check))
        } }
        if (update.releaseUrl.isNotBlank()) item {
            Button(onClick = { model.openProjectLink(context, update.releaseUrl) }) {
                Text(stringResource(R.string.native_update_open_release))
            }
            if (update.notes.isNotBlank()) Text(update.notes, style = MaterialTheme.typography.bodySmall)
        }
        item { TextButton(onClick = {
            model.openProjectLink(context, "https://github.com/YAQMC/YAQMC-Android")
        }) { Text(stringResource(R.string.native_about_repository)) } }
    }
}


internal data class NativeIssuePreview(
    val title: String, val body: String, val url: String, val tooLong: Boolean,
) {
    val text: String get() = "$title\n\n$body"
}
internal data class NativeIssueState(
    val draftKey: String = "", val preview: NativeIssuePreview? = null,
    val busy: Boolean = false, val error: Boolean = false,
)

@UnstableApi
@Composable
private fun FeedbackLogLevel(model: NativeViewModel) {
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val selected = NativeLogDetail.entries.firstOrNull { it.key == diagnostics.logLevel } ?: NativeLogDetail.INFO
    val idle = !diagnostics.loading && !diagnostics.busy
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = idle, modifier = Modifier.testTag("feedback-log-level")) {
            Text(stringResource(R.string.native_issue_log_detail, stringResource(selected.label)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NativeLogDetail.entries.forEach { detail ->
                DropdownMenuItem(text = { Text(stringResource(detail.label)) }, onClick = {
                    expanded = false
                    model.setDiagnosticsLevel(detail.key)
                }, modifier = Modifier.testTag("feedback-level-${detail.key}"))
            }
        }
    }
}

@UnstableApi
@Composable
internal fun NativeIssueReport(model: NativeViewModel) {
    val state by model.issueState.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val upload by model.diagnosticUpload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.loadDiagnostics() }
    var category by rememberSaveable { mutableStateOf("bug") }
    var summary by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var confirmUpload by rememberSaveable { mutableStateOf(false) }
    val key = listOf(category, summary, description).joinToString("\u0000")
    val preview = state.preview?.takeIf { state.draftKey == key }
    val categoryLabels = listOf("bug" to R.string.native_issue_bug,
        "playback" to R.string.native_issue_playback, "provider" to R.string.native_issue_provider,
        "lyrics" to R.string.native_issue_lyrics, "ui" to R.string.native_issue_ui,
        "other" to R.string.native_issue_other)
    LazyColumn(Modifier.fillMaxSize().testTag("issue-report-list"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick = { model.openProjectLink(context, FEEDBACK_URL) }) {
            Text(stringResource(R.string.native_issue_site_direct))
        } }
        item { FeedbackLogLevel(model) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categoryLabels.forEach { (value, label) ->
                FilterChip(selected = category == value, onClick = { category = value },
                    label = { Text(stringResource(label)) })
            }
        } }
        item { OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(stringResource(R.string.native_issue_summary)) }) }
        item { OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(),
            minLines = 4, maxLines = 8, label = { Text(stringResource(R.string.native_issue_description)) }) }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error) item { Text(stringResource(R.string.native_issue_failed),
            color = MaterialTheme.colorScheme.error) }
        item { OutlinedButton(
            onClick = { model.previewIssue(category, summary, description) },
            enabled = summary.isNotBlank() && !state.busy) {
            Text(stringResource(R.string.native_issue_preview))
        } }
        item { DiagnosticUploadControls(upload, !diagnostics.busy && !diagnostics.loading && !upload.busy,
            onOpen = { confirmUpload = true }, onRetry = model::retryDiagnosticBundle) }
        if (preview != null) {
            item { Text(preview.title, style = MaterialTheme.typography.titleMedium) }
            item { Text(preview.body, style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.native_issue_site_hint), style = MaterialTheme.typography.bodyMedium) }
            item { Button(onClick = { model.copyIssueReport(context, openWebsite = true) }, enabled = !state.busy) {
                Text(stringResource(R.string.native_issue_open_site))
            } }
            item { OutlinedButton(onClick = { model.copyIssueReport(context) }, enabled = !state.busy) {
                Text(stringResource(R.string.native_issue_copy))
            } }
            item { TextButton(onClick = { model.openIssuePreview(context) }, enabled = !state.busy) {
                Text(stringResource(if (preview.tooLong) R.string.native_issue_copy_github else R.string.native_issue_open))
            } }
        }
    }
    if (confirmUpload) {
        val initial = listOf(summary.trim(), description.trim()).filter(String::isNotEmpty).joinToString("\n\n")
        ConfirmDiagnosticUpload(initial, close = { confirmUpload = false }, submit = model::submitDiagnosticBundle)
    }
}

@Composable
private fun DiagnosticUploadControls(state: NativeDiagnosticUploadState, enabled: Boolean, onOpen: () -> Unit, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpen, enabled = enabled, modifier = Modifier.testTag("diagnostics-submit")) {
            Text(stringResource(R.string.native_diag_upload))
        }
        if (state.preparing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.native_diag_upload_preparing))
        }
        state.progress?.let { progress ->
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(if (progress == 100) stringResource(R.string.native_diag_upload_waiting)
                else stringResource(R.string.native_diag_upload_sending, progress))
        }
        state.error?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            if (state.canRetry) TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.testTag("diagnostics-submit-retry")) {
                Text(stringResource(R.string.native_retry))
            }
        }
        state.receipt?.let { receipt ->
            SelectionContainer {
                Text(stringResource(R.string.native_diag_upload_received, receipt), modifier = Modifier.testTag("diagnostics-receipt"))
            }
        }
    }
}

@Composable
private fun ConfirmDiagnosticUpload(initialDescription: String, close: () -> Unit, submit: (String) -> Unit) {
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    val tooLong = description.trim().toByteArray(Charsets.UTF_8).size > DIAGNOSTIC_DESCRIPTION_MAX_BYTES
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.native_diag_upload)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.native_diag_upload_consent))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics-submit-description"),
                    label = { Text(stringResource(R.string.native_diag_upload_description)) },
                    minLines = 2,
                    maxLines = 5,
                    isError = tooLong,
                    supportingText = { if (tooLong) Text(stringResource(R.string.native_diag_upload_description_long)) },
                )
            }
        },
        confirmButton = {
            Button(onClick = { submit(description); close() }, enabled = !tooLong, modifier = Modifier.testTag("diagnostics-submit-confirm")) {
                Text(stringResource(R.string.native_diag_upload_confirm))
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_cancel)) } },
    )
}

@Composable
internal fun NativeStartupFailure(app: YaqmcApplication, retry: () -> Unit) {
    val base = LocalContext.current
    val context = remember(base) {
        val preferences = NativePreferenceStore(app.getSharedPreferences("native-ui", Context.MODE_PRIVATE)).state.value
        base.withNativeLanguage(preferences)
    }
    CompositionLocalProvider(LocalContext provides context) {
        val report by produceState<String?>(null, app) {
            val details = withContext(Dispatchers.IO) { androidSupportDetails(app) }
            value = "YAQMC ${BuildConfig.VERSION_NAME} · ${BuildConfig.YAQMC_BUILD_COMMIT}\n\n${androidSupportText(details)}"
        }
        var status by remember { mutableStateOf<Int?>(null) }
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.native_startup_failure_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.native_startup_failure_hint))
                    report?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = retry) { Text(stringResource(R.string.native_retry)) }
                    OutlinedButton(onClick = {
                        report?.let { status = copyFeedbackReport(context, it, openWebsite = true) }
                    }, enabled = report != null) { Text(stringResource(R.string.native_issue_open_site)) }
                    TextButton(onClick = {
                        report?.let { status = copyFeedbackReport(context, it, openWebsite = false) }
                    }, enabled = report != null) { Text(stringResource(R.string.native_issue_copy)) }
                    status?.let { Text(stringResource(it)) }
                }
            }
        }
    }
}

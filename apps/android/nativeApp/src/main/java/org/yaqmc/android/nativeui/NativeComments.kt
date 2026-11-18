package org.yaqmc.android.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.yaqmc.android.R

/** Read-only comment pages use the provider/profile and opaque cursor supplied by Core. */
@UnstableApi
@Composable
internal fun NativeComments(model: NativeViewModel, route: NativeRoute) {
    var sort by remember(route) { mutableStateOf("hot") }
    var parent by remember(route) { mutableStateOf<JSONObject?>(null) }
    var page by remember(route, sort, parent) { mutableStateOf<JSONObject?>(null) }
    var rows by remember(route, sort, parent) { mutableStateOf(emptyList<JSONObject>()) }
    var cursor by remember(route, sort, parent) { mutableStateOf<String?>(null) }
    var revision by remember(route, sort, parent) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<NativeText?>(null) }
    var activeRequest by remember { mutableStateOf("") }
    LaunchedEffect(route, sort, parent, revision) {
        val requestId = UUID.randomUUID().toString()
        activeRequest = requestId
        val scope = JSONObject().put("providerId", route.providerId).put("profileId", route.profileId).put("requestId", requestId)
        loading = true; error = null
        try {
            val request = JSONObject().put("songId", route.id).put("sort", sort).put("limit", 20).put("cursor", cursor ?: JSONObject.NULL).put("parentId", parent?.text("id") ?: JSONObject.NULL)
            val result = model.core.objectResult("provider_comments", JSONObject(scope.toString()).put("request", request))
            rows = (if (cursor == null) emptyList() else rows).plus(result.optJSONArray("items").objects()).distinctBy { it.text("id") }
            page = result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { model.core.invoke("provider_comments_cancel", scope) } }
            throw cancelled
        } catch (_: Exception) { error = NativeText(R.string.native_comments_failed) }
        finally { if (activeRequest == requestId) loading = false }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("comments-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(route.query, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (parent != null) TextButton(onClick = { parent = null }) { Text(stringResource(R.string.native_comments_back)) }
                else listOf("hot" to R.string.native_comments_hot, "new" to R.string.native_comments_new).filter { (id, _) ->
                    val sorts = page?.optJSONArray("supportedSorts")
                    sorts == null || (0 until sorts.length()).any { sorts.optString(it) == id }
                }.forEach { (id, label) -> FilterChip(sort == id, onClick = { sort = id }, enabled = !loading, label = { Text(stringResource(label)) }) }
            }
            parent?.let { Text(it.text("content"), style = MaterialTheme.typography.bodyMedium) }
        }
        items(rows, key = { it.text("id") }) { comment ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(comment.text("author"), style = MaterialTheme.typography.titleSmall)
                    Text(comment.text("content"))
                    comment.optJSONObject("replyTo")?.let { Text("${it.text("author")}: ${it.text("content")}", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.native_comments_likes, comment.optLong("likedCount")), style = MaterialTheme.typography.labelMedium)
                        if (parent == null && page?.optBoolean("repliesSupported") == true && comment.optLong("replyCount") > 0) {
                            TextButton(onClick = { parent = comment }) { Text(stringResource(R.string.native_comments_replies, comment.optLong("replyCount"))) }
                        }
                    }
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Text(it.resolve(), color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text(stringResource(R.string.native_retry)) } } }
        if (!loading && error == null && rows.isEmpty()) item { Text(stringResource(R.string.native_comments_empty)) }
        page?.text("nextCursor")?.takeIf(String::isNotBlank)?.let { next -> item {
            TextButton(enabled = !loading, onClick = { cursor = next; revision++ }) { Text(stringResource(R.string.native_plugin_load_more)) }
        } }
    }
}

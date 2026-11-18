package org.yaqmc.android.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

@UnstableApi
class NativePluginHostUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(NativeDevicePreferences(ComponentActivity::class.java)).around(compose)
    private lateinit var model: NativeViewModel
    @After fun close() { compose.runOnIdle { if (::model.isInitialized) { model.plugins.close(); model.viewModelScope.cancel() } } }

    @Test fun commentPaginationRepliesSortAndCancellationKeepTheProviderScope() {
        val core = CommentCore()
        compose.runOnUiThread {
            model = NativeViewModel(compose.activity.application, core)
            model.setLanguage("zh-CN")
            model.navigate(NativeRoute("comments", "track-one", query = "Fixture song", providerId = "fixture.source", profileId = "profile-one"))
        }
        compose.setContent { NativeApp(model, {}, { _, _ -> }, {}) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("First comment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("加载更多").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Second comment").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("2 条回复")[0].performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Reply body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("全部评论").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("最新").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Latest comment").fetchSemanticsNodes().isNotEmpty() }
        val reads = core.calls.filter { it.first == "provider_comments" }.map { it.second }
        assertTrue(reads.all { it.text("providerId") == "fixture.source" && it.text("profileId") == "profile-one" })
        assertTrue(reads.any { it.getJSONObject("request").text("cursor") == "opaque#two" })
        assertTrue(reads.any { it.getJSONObject("request").text("parentId") == "root-one" })
        compose.runOnIdle { model.navigate(NativeRoute("comments", "slow", providerId = "fixture.source", profileId = "profile-one")) }
        compose.waitUntil(5000) { core.calls.any { it.first == "provider_comments" && it.second.getJSONObject("request").text("songId") == "slow" } }
        val pending = core.calls.last { it.first == "provider_comments" }.second
        compose.runOnIdle { model.navigate(NativeRoute("settings")) }
        compose.waitUntil(5000) { core.calls.any { it.first == "provider_comments_cancel" && it.second.text("requestId") == pending.text("requestId") } }
        assertTrue(core.calls.none { it.first.startsWith("provider_") && it.first.contains("favorite") })
    }

    private class CommentCore : NativeCoreApi {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override suspend fun invoke(method: String, params: JSONObject, host: Boolean): Any? {
            calls += method to JSONObject(params.toString())
            return when (method) {
                "provider_list" -> JSONArray("""[{"providerId":"fixture.source","available":true,"capabilities":{"account":false,"comments":true}}]""")
                "plugin_list", "plugin_update_status" -> JSONArray()
                "provider_account_snapshot" -> JSONObject("""{"state":"guest","revision":1,"capabilities":{}}""")
                "provider_comments" -> {
                    val request = params.getJSONObject("request")
                    if (request.text("songId") == "slow") awaitCancellation()
                    val reply = request.text("parentId").isNotEmpty()
                    val second = request.text("cursor").isNotEmpty()
                    val latest = request.text("sort") == "new"
                    val content = when { reply -> "Reply body"; second -> "Second comment"; latest -> "Latest comment"; else -> "First comment" }
                    JSONObject().put("items", JSONArray().put(JSONObject().put("id", if (second) "root-two" else "root-one").put("author", "Listener").put("content", content).put("likedCount", 3).put("replyCount", if (reply) 0 else 2)))
                        .put("supportedSorts", JSONArray(listOf("hot", "new"))).put("repliesSupported", true).put("nextCursor", if (reply || second || latest) JSONObject.NULL else "opaque#two")
                }
                else -> JSONObject()
            }
        }
    }
}

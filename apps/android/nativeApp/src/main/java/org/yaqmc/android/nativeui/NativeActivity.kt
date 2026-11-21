package org.yaqmc.android.nativeui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.collect
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.yaqmc.android.OAuthActivity
import org.yaqmc.android.YaqmcApplication
import org.yaqmc.android.core.DeepLinkInbox
import org.yaqmc.android.core.DeepLinkManager
import org.yaqmc.android.core.MobileLoginOwner
import org.yaqmc.android.core.MobileLoginPolicy
import org.yaqmc.android.media.PlaybackService
import org.yaqmc.android.R

@UnstableApi
class NativeActivity : ComponentActivity() {
    private val model: NativeViewModel by viewModels()
    private val deepLinks = DeepLinkManager()
    private var links: AutoCloseable? = null
    private var shareJob: Job? = null
    private var startupFailed = false
    private val oauth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.finishOAuth(if (it.resultCode == Activity.RESULT_OK) it.data?.dataString else null,
            if (it.resultCode == Activity.RESULT_OK) it.data?.getStringExtra(OAuthActivity.EXTRA_BROWSER_SESSION_COOKIES) else null)
    }
    private val pluginFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.plugins.nativeFiles.picked(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as? YaqmcApplication
        if (app?.startupDiagnostics?.coreFailure != null) {
            startupFailed = true
            setContent {
                NativeStartupFailure(app) {
                    app.initializeCore()
                    recreate()
                }
            }
            return
        }
        deepLinks.accept(intent)
        lifecycleScope.launch {
            model.plugins.state.collect {
                model.plugins.nativeFiles.openPicker = { pickerIntent ->
                    check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "files.picker-unavailable" }
                    pluginFiles.launch(pickerIntent)
                }
            }
        }
        links = DeepLinkInbox.subscribe { target ->
            lifecycleScope.launch {
                model.navigate(NativeRoute("song", target.entityId, providerId = target.providerId, profileId = target.profileId))
            }
        }
        setContent {
            NativeApp(model, login = ::login, share = ::share, reportDrawn = {
                app?.startupDiagnostics?.firstPageDrawn()
                reportFullyDrawn()
            }, startupDiagnostics = app?.startupDiagnostics)
        }
    }

    override fun onStart() {
        super.onStart()
        if (startupFailed) return
        PlaybackService.ensureStarted(this)
        model.observe()
    }
    override fun onResume() { super.onResume(); if (!startupFailed) MobileLoginOwner.lifecycle("foreground") }
    override fun onPause() { if (!startupFailed) MobileLoginOwner.lifecycle("background"); super.onPause() }
    override fun onStop() { if (!startupFailed) model.stopObserving(); super.onStop() }
    override fun onDestroy() { shareJob?.cancel(); links?.close(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (!startupFailed) deepLinks.accept(intent) }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        (!startupFailed && model.plugins.key(event)) || super.onKeyDown(keyCode, event)
    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean =
        (!startupFailed && model.plugins.key(event)) || super.onKeyShortcut(keyCode, event)

    private fun login(method: String) {
        model.startLogin(method, resources.configuration.smallestScreenWidthDp,
            oauth = { prepared ->
                val hosts = prepared.optJSONArray("navigationAllowlist")
                val prefix = prepared.optJSONObject("callbackMatcher")?.text("urlPrefix").orEmpty()
                if (hosts == null || hosts.length() == 0 || prefix.isBlank()) throw CoreFailure("native.login.config", "")
                oauth.launch(Intent(this, OAuthActivity::class.java).apply {
                    putExtra(OAuthActivity.EXTRA_LANGUAGE, application.withNativeLanguage(model.preferences.value).resources.configuration.locales[0].toLanguageTag())
                    putExtra(OAuthActivity.EXTRA_URL, prepared.text("url"))
                    putExtra(OAuthActivity.EXTRA_MOBILE_URL, prepared.text("mobileUrl"))
                    putStringArrayListExtra(OAuthActivity.EXTRA_NAVIGATION_ALLOWLIST, ArrayList((0 until hosts.length()).map { hosts.getString(it) }))
                    putExtra(OAuthActivity.EXTRA_EXTERNAL_NAVIGATION_RULES, prepared.optJSONArray("externalNavigationRules")?.toString() ?: "[]")
                    putExtra(OAuthActivity.EXTRA_CALLBACK_PREFIX, prefix)
                    prepared.optJSONObject("browserSession")?.let { putExtra(OAuthActivity.EXTRA_BROWSER_SESSION, it.toString()) }
                })
            },
            mobile = { authorization ->
                val uri = Uri.Builder().scheme("qqmusic").authority("qq.com").appendPath("ui").appendPath("openUrl")
                    .appendQueryParameter("p", JSONObject().put("url", authorization).toString()).build()
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(MobileLoginPolicy.QQ_MUSIC_PACKAGE))
                }.onFailure { model.notify(R.string.native_qq_unavailable) }
            },
        )
    }

    private fun share(track: Track, kind: SongShareKind) {
        if (shareJob?.isActive == true) return
        shareJob = model.action {
            val providers = model.plugins.state.value.providers.filter { it.optBoolean("available") }.map { it.text("providerId") }.toSet() + "qqmusic"
            val payload = NativeSongShare.resolve(track, kind, model.core, providers)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, payload.title)
                putExtra(Intent.EXTRA_TEXT, payload.text)
            }, model.localizedString(R.string.native_share_song)))
        }
    }
}

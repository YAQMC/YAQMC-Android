package org.yaqmc.android

import android.app.Application
import android.content.Context
import coil3.SingletonImageLoader
import org.yaqmc.android.core.CoreManager
import org.yaqmc.android.nativeui.newNativeImageLoader
import org.json.JSONObject

class YaqmcApplication : Application(), SingletonImageLoader.Factory {
    internal val startupDiagnostics = AndroidStartupDiagnostics()

    override fun newImageLoader(context: Context) = newNativeImageLoader(context)

    override fun onCreate() {
        super.onCreate()
        startupDiagnostics.installCrashRecorder(this)
        initializeCore()
    }

    internal fun initializeCore() {
        startupDiagnostics.startCore()
        val buildJson = JSONObject()
            .put("platform", "android")
            .put("appId", packageName)
            .put("version", BuildConfig.VERSION_NAME)
            .put("releaseChannel", "android")
            .put("buildCommit", BuildConfig.YAQMC_BUILD_COMMIT)
            .put("buildType", BuildConfig.BUILD_TYPE)
            .toString()
        try {
            CoreManager.initialize(context = this, buildJson = buildJson)
            startupDiagnostics.finishCore(null)
        } catch (error: Exception) {
            startupDiagnostics.finishCore(error)
        } catch (error: LinkageError) {
            startupDiagnostics.finishCore(error)
        }
    }
}

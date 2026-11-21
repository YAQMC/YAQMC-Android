package org.yaqmc.android.nativeui

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import coil3.SingletonImageLoader

/** Only the offline UI suite uses this runner; production and live tests keep YaqmcApplication. */
class NativeOfflineTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        SingletonImageLoader.setSafe(::newNativeImageLoader)
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}

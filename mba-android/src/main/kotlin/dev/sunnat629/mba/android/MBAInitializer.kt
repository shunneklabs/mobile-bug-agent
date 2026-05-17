package dev.sunnat629.mba.android

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer

/**
 * AndroidX Startup entry point.
 *
 * Developers can include this library and MBAAndroid.install() will run automatically
 * at app startup (if they keep the provider enabled).
 */
class MBAInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        MBAAndroid.install(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(WorkManagerInitializer::class.java)
}

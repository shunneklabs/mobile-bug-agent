package dev.sunnat629.mba.sample

import android.app.Application
import dev.sunnat629.mba.core.MBA

/**
 * Application class — initializes the MBA SDK as early as possible.
 *
 * In a real app, you'd call MBA.init() with your actual config.
 * For this sample, we only install the crash handler (Phase 1).
 */
class MBASampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Phase 1: Install crash handler.
        // This is all you need to start capturing crashes to disk.
        MBA.install(crashDir = filesDir.resolve("mba-crashes").absolutePath)

        // Phase 2 (configure) would be called here with your LLM key + backend.
        // Skipped in sample to keep it runnable without API keys.
    }
}

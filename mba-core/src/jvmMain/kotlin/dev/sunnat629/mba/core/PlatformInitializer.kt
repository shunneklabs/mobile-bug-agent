package dev.sunnat629.mba.core

public actual object PlatformInitializer {
    actual fun installCrashHandler(onCrash: (String, Throwable) -> Unit) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            onCrash(thread.name, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

package dev.sunnat629.mba.sample

object CrashScenarios {
    fun npe() {
        val s: String? = null
        // Force NPE
        s!!.length
    }

    fun illegalState() {
        error("IllegalState: simulated")
    }

    fun oom() {
        // Allocate until OOM (dangerous; keep small-ish)
        val list = ArrayList<ByteArray>()
        while (true) {
            list.add(ByteArray(5_000_000))
        }
    }

    fun nonFatal() {
        throw RuntimeException("Non-fatal simulated")
    }
}

package dev.sunnat629.mba.sample

object StageNpeCrasher {
    fun trigger() {
        // Demo guard: avoid crashing the app while still exercising the scenario.
        val stageName: String? = null
        if (stageName == null) return

        stageName.length
    }
}

package dev.sunnat629.mba.sample

object StageNpeCrasher {
    fun trigger() {
        val stageName: String? = null
        stageName!!.length
    }
}

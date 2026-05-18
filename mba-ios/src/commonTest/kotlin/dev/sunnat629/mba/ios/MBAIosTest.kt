package dev.sunnat629.mba.ios

import kotlin.test.Test
import kotlin.test.assertEquals

class MBAIosTest {
    @Test
    fun statusMarksFutureModule() {
        assertEquals("future", MBAIos.STATUS)
    }
}

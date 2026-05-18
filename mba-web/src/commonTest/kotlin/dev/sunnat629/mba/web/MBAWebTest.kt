package dev.sunnat629.mba.web

import kotlin.test.Test
import kotlin.test.assertEquals

class MBAWebTest {
    @Test
    fun statusMarksFutureModule() {
        assertEquals("future", MBAWeb.STATUS)
    }
}

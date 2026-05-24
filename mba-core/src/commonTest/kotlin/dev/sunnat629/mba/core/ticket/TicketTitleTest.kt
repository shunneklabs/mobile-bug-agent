package dev.sunnat629.mba.core.ticket

import kotlin.test.Test
import kotlin.test.assertEquals

class TicketTitleTest {
    @Test
    fun withMbaTicketPrefixAddsPrefixOnce() {
        assertEquals("[MBA] Checkout crash", "Checkout crash".withMbaTicketPrefix())
        assertEquals("[MBA] Checkout crash", "[MBA] Checkout crash".withMbaTicketPrefix())
        assertEquals("[MBA] Checkout crash", "  Checkout crash  ".withMbaTicketPrefix())
    }
}

package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.core.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeverityRouterTest {
    @Test
    fun `returns notify only for all severities when autofix is disabled`() {
        val router = SeverityRouter(autoFixEnabled = false)

        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.CRITICAL))
        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.HIGH))
        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.MEDIUM))
        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.LOW))
    }

    @Test
    fun `returns notify only for critical severity when autofix is enabled`() {
        val router = SeverityRouter(autoFixEnabled = true)

        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.CRITICAL))
    }

    @Test
    fun `returns notify only for high severity when autofix is enabled`() {
        val router = SeverityRouter(autoFixEnabled = true)

        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.HIGH))
    }

    @Test
    fun `returns notify only for medium severity when autofix is enabled`() {
        val router = SeverityRouter(autoFixEnabled = true)

        assertEquals(RoutingDecision.NotifyOnly, router.route(Severity.MEDIUM))
    }

    @Test
    fun `returns autofix for low severity when autofix is enabled`() {
        val router = SeverityRouter(autoFixEnabled = true)

        assertEquals(RoutingDecision.AutoFix, router.route(Severity.LOW))
    }

    @Test
    fun `per-crash autofix path allows medium and above but blocks low noise`() {
        val router = SeverityRouter(autoFixEnabled = false)

        assertTrue(router.shouldAutoFix(Severity.CRITICAL))
        assertTrue(router.shouldAutoFix(Severity.HIGH))
        assertTrue(router.shouldAutoFix(Severity.MEDIUM))
        assertFalse(router.shouldAutoFix(Severity.LOW))
    }
}

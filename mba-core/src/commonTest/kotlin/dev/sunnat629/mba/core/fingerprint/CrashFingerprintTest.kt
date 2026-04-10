package dev.sunnat629.mba.core.fingerprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CrashFingerprintTest {

    private val sampleTrace = """
        java.lang.NullPointerException: Attempt to invoke virtual method
        at com.example.app.CheckoutViewModel.processPayment(CheckoutViewModel.kt:87)
        at com.example.app.CheckoutViewModel.onConfirmClick(CheckoutViewModel.kt:45)
        at com.example.app.CheckoutFragment.onClick(CheckoutFragment.kt:112)
        at android.view.View.performClick(View.java:7448)
        at android.view.View.onKeyUp(View.java:14590)
        at android.widget.TextView.onKeyUp(TextView.java:10005)
    """.trimIndent()

    @Test
    fun sameCrashProducesSameFingerprint() {
        val fp1 = CrashFingerprint.compute("java.lang.NullPointerException", sampleTrace)
        val fp2 = CrashFingerprint.compute("java.lang.NullPointerException", sampleTrace)
        assertEquals(fp1, fp2)
    }

    @Test
    fun differentExceptionTypesProduceDifferentFingerprints() {
        val fp1 = CrashFingerprint.compute("java.lang.NullPointerException", sampleTrace)
        val fp2 = CrashFingerprint.compute("java.lang.IllegalStateException", sampleTrace)
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun differentTraceProducesDifferentFingerprint() {
        val otherTrace = """
            java.lang.IllegalArgumentException: Invalid argument
            at com.example.app.LoginViewModel.validate(LoginViewModel.kt:33)
            at com.example.app.LoginFragment.onSubmit(LoginFragment.kt:55)
        """.trimIndent()
        val fp1 = CrashFingerprint.compute("java.lang.IllegalArgumentException", sampleTrace)
        val fp2 = CrashFingerprint.compute("java.lang.IllegalArgumentException", otherTrace)
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun ignoreLineNumbersProducesSameFingerprint() {
        val traceWithDifferentLine = sampleTrace.replace(":87)", ":99)")
        val fp1 = CrashFingerprint.compute(
            "java.lang.NullPointerException", sampleTrace, ignoreLineNumbers = true
        )
        val fp2 = CrashFingerprint.compute(
            "java.lang.NullPointerException", traceWithDifferentLine, ignoreLineNumbers = true
        )
        assertEquals(fp1, fp2)
    }

    @Test
    fun ignoreLineNumbersDisabledProducesDifferentFingerprint() {
        val traceWithDifferentLine = sampleTrace.replace(":87)", ":99)")
        val fp1 = CrashFingerprint.compute(
            "java.lang.NullPointerException", sampleTrace, ignoreLineNumbers = false
        )
        val fp2 = CrashFingerprint.compute(
            "java.lang.NullPointerException", traceWithDifferentLine, ignoreLineNumbers = false
        )
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun topFramesLimitsConsideredFrames() {
        // Same first 2 frames but different 3rd frame
        val trace1 = """
            at com.example.A.foo(A.kt:1)
            at com.example.B.bar(B.kt:2)
            at com.example.C.baz(C.kt:3)
        """.trimIndent()
        val trace2 = """
            at com.example.A.foo(A.kt:1)
            at com.example.B.bar(B.kt:2)
            at com.example.D.qux(D.kt:4)
        """.trimIndent()

        // With topFrames=2, the 3rd frame is ignored → same fingerprint
        val fp1 = CrashFingerprint.compute("NPE", trace1, topFrames = 2)
        val fp2 = CrashFingerprint.compute("NPE", trace2, topFrames = 2)
        assertEquals(fp1, fp2)

        // With topFrames=3, all frames are considered → different fingerprint
        val fp3 = CrashFingerprint.compute("NPE", trace1, topFrames = 3)
        val fp4 = CrashFingerprint.compute("NPE", trace2, topFrames = 3)
        assertNotEquals(fp3, fp4)
    }

    @Test
    fun fingerprintIsSha256Hex() {
        val fp = CrashFingerprint.compute("NPE", sampleTrace)
        // SHA-256 hex = 64 characters, all hex
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

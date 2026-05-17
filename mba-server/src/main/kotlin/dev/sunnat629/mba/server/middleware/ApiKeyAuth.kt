package dev.sunnat629.mba.server.middleware

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import dev.sunnat629.mba.core.MBALog

private const val TAG = "ApiKeyAuth"

/**
 * Ktor plugin that validates the X-MBA-API-Key header for POST /report.
 * Requests without a valid key get 401 Unauthorized.
 */
fun Application.apiKeyAuth(expectedKey: String) {
    intercept(ApplicationCallPipeline.Call) {
        if (call.request.path() != "/report") return@intercept

        val apiKey = call.request.header("X-MBA-API-Key")
        if (expectedKey.isBlank()) {
            MBALog.w(TAG, "Rejected POST /report because MBA_SERVER_API_KEY is not configured")
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Report API key is not configured"))
            finish()
            return@intercept
        }

        if (apiKey != expectedKey) {
            MBALog.w(TAG, "Rejected POST /report due to missing or invalid X-MBA-API-Key")
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
            finish()
        }
    }
}

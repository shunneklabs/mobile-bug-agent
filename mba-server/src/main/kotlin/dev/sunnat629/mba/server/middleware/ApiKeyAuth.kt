package dev.sunnat629.mba.server.middleware

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Ktor plugin that validates the X-MBA-API-Key header on every request.
 * Requests without a valid key get 401 Unauthorized.
 */
fun Application.apiKeyAuth(expectedKey: String) {
    intercept(ApplicationCallPipeline.Call) {
        val apiKey = call.request.header("X-MBA-API-Key")
        if (apiKey != expectedKey) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
            finish()
        }
    }
}

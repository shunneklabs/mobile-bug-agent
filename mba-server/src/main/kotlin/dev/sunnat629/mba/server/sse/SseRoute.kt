package dev.sunnat629.mba.server.sse

import dev.sunnat629.mba.server.queue.CrashProcessingQueue
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** Heartbeat interval — keeps proxies/EventSource from idle-closing the stream. */
private const val HEARTBEAT_MS = 15_000L

fun Route.sseEvents(queue: CrashProcessingQueue) {
    get("/events") {
        // Hint upstream proxies (nginx etc.) not to buffer.
        call.response.header("Cache-Control", "no-cache")
        call.response.header("Connection", "keep-alive")
        call.response.header("X-Accel-Buffering", "no")

        // IMPORTANT: pass contentType explicitly — `respondTextWriter` would
        // otherwise default to `text/plain` and the browser would silently
        // refuse to treat it as an EventSource stream.
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            coroutineScope {
                // Heartbeat — every HEARTBEAT_MS emit a comment line so the
                // connection stays warm during long Gemini / Notion calls.
                val heartbeat = launch {
                    while (isActive) {
                        delay(HEARTBEAT_MS)
                        runCatching {
                            write(": ping\n\n")
                            flush()
                        }.onFailure { cancel() }
                    }
                }
                try {
                    queue.events.collect { event ->
                        val data = json.encodeToString(event)
                        write("data: $data\n\n")
                        flush()
                    }
                } finally {
                    heartbeat.cancel()
                }
            }
        }
    }
}

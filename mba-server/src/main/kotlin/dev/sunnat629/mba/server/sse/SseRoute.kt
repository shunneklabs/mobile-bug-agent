package dev.sunnat629.mba.server.sse

import dev.sunnat629.mba.server.queue.CrashProcessingQueue
import dev.sunnat629.mba.server.queue.SseEvent
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun Route.sseEvents(queue: CrashProcessingQueue) {
    get("/events") {
        call.response.header("Content-Type", "text/event-stream")
        call.response.header("Cache-Control", "no-cache")
        call.response.header("Connection", "keep-alive")
        call.respondTextWriter {
            queue.events.collect { event ->
                val data = json.encodeToString(event)
                write("data: $data\n\n")
                flush()
            }
        }
    }
}

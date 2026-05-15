package dev.sunnat629.mba.server

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.server.middleware.rateLimiter
import dev.sunnat629.mba.server.middleware.RateLimiter
import dev.sunnat629.mba.server.model.ServerStats
import dev.sunnat629.mba.server.model.ServerVersion
import dev.sunnat629.mba.server.queue.CrashJob
import dev.sunnat629.mba.server.sse.sseEvents
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger("MBAServer")

// ------------------------------------------------------------------ //
//  Environment Config — fail fast on startup, not at request time
// ------------------------------------------------------------------ //

private object EnvConfig {
    val geminiApiKey: String = requireEnv("GEMINI_API_KEY")
    val notionApiKey: String = requireEnv("NOTION_API_KEY")
    val notionDatabaseId: String = requireEnv("NOTION_DATABASE_ID")
    val serverApiKey: String = System.getenv("MBA_SERVER_API_KEY") ?: ""
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dedupCachePath: String = System.getenv("MBA_DEDUP_CACHE_PATH") ?: "data/dedup-cache.json"
    val dataDir: String = System.getenv("MBA_DATA_DIR") ?: "data"

    private fun requireEnv(name: String): String =
        System.getenv(name)
            ?: error("Required environment variable $name is not set. Server cannot start.")
}

fun main() {
    logger.info("Starting MBA Server on port ${EnvConfig.port}")
    embeddedServer(Netty, port = EnvConfig.port) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }

    install(CORS) {
        allowHost("localhost:3000", schemes = listOf("http"))
        allowHost("localhost:8080", schemes = listOf("http"))
        allowHost("localhost:63342", schemes = listOf("http"))
        anyHost()
    }

    // ---- Initialize dependency graph ---- //
    val serverModule = installServerModule(
        geminiApiKey = EnvConfig.geminiApiKey,
        notionApiKey = EnvConfig.notionApiKey,
        notionDatabaseId = EnvConfig.notionDatabaseId,
        serverApiKey = EnvConfig.serverApiKey,
        dedupCachePath = EnvConfig.dedupCachePath,
        dataDir = EnvConfig.dataDir,
    )

    // ---- Rate limiter (10 req/s) ---- //
    val rateLimiter = RateLimiter(maxRequests = 10)
    rateLimiter(rateLimiter)

    // ---- Background queue consumer ---- //
    serverModule.scope.launch {
        for (job in serverModule.queue.jobs) {
            processJob(serverModule, job)
        }
    }

    // ---- Clean up resources on shutdown ---- //
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Shutting down — saving state and closing resources...")
        serverModule.shutdown()
    }

    routing {
        staticResources("/booth-assets", "booth")

        get("/") {
            call.respondText("Mobile Bug Agent (MBA) Server is running!")
        }

        get("/booth") {
            val html = this::class.java.classLoader
                .getResource("booth/booth.html")
                ?.readText()
                ?: return@get call.respond(HttpStatusCode.NotFound, "booth.html not found")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/health") {
            call.respond(mapOf(
                "status" to "healthy",
                "dedupCacheSize" to serverModule.dedupCache.size().toString(),
            ))
        }

        get("/version") {
            call.respond(ServerVersion())
        }

        get("/stats") {
            val stats = serverModule.jobStore.getStats()
            call.respond(ServerStats(
                totalJobs = stats.total,
                queuedJobs = stats.queued,
                completedJobs = stats.completed,
                failedJobs = stats.failed,
                dedupCacheSize = serverModule.dedupCache.size(),
            ))
        }

        get("/booth/pending-decisions") {
            val pending = serverModule.jobStore.getAllJobs()
                .filter {
                    it.status.name == "QUEUED" ||
                        it.status.name == "ANALYZING" ||
                        it.status.name == "TICKET_CREATED"
                }
                .take(20)
                .map {
                    PendingDecisionJob(
                        jobId = it.id,
                        status = it.status.name,
                        updatedAt = it.updatedAt,
                        artifactUrl = it.artifactUrl,
                    )
                }
            call.respond(pending)
        }

        post("/booth/force-decision") {
            if (!call.isLocalOperatorCall()) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Operator action only"))
            }

            val request = call.receive<ForceDecisionRequest>()
            val normalizedDecision = request.decision.trim().lowercase()
            if (normalizedDecision !in setOf("notify", "autofix", "fallback")) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "decision must be notify|autofix|fallback"),
                )
            }

            val job = serverModule.jobStore.getJob(request.jobId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown job id"))

            serverModule.queue.publishBoothEvent(
                type = "operator_decision",
                message = "Operator chose ${normalizedDecision.uppercase()} for ${request.jobId.take(8)}",
                metadata = mapOf("decision" to normalizedDecision, "jobId" to request.jobId),
                jobId = request.jobId,
            )

            call.respond(
                BoothActionResponse(
                    ok = true,
                    message = "Decision accepted",
                    jobId = job.id,
                )
            )
        }

        post("/booth/reset") {
            if (!call.isLocalOperatorCall()) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Operator action only"))
            }

            val existingJobs = serverModule.jobStore.getAllJobs().size
            serverModule.jobStore.clearAll()

            serverModule.queue.publishBoothEvent(
                type = "dashboard_reset",
                message = "Dashboard reset by operator",
                level = "warning",
                metadata = mapOf("clearedJobs" to existingJobs.toString()),
            )

            call.respond(BoothActionResponse(ok = true, message = "Dashboard reset", clearedJobs = existingJobs))
        }

        // ---- Authenticated crash report endpoint ---- //
        post("/report") {
            val rawReport = call.receive<RawCrashReport>()
            logger.info("Received crash report: ${rawReport.id}")

            val jobId = UUID.randomUUID().toString()
            serverModule.queue.enqueue(jobId, rawReport)

            call.respond(HttpStatusCode.Accepted, mapOf(
                "jobId" to jobId,
                "status" to "queued",
            ))
        }

        // ---- Job status endpoint ---- //
        get("/jobs/{id}") {
            val jobId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing job id"))

            val job = serverModule.jobStore.getJob(jobId)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found: $jobId"))

            call.respond(job)
        }

        // ---- SSE event feed ---- //
        sseEvents(serverModule.queue)
    }
}

private fun ApplicationCall.isLocalOperatorCall(): Boolean {
    val remote = request.local.remoteHost.lowercase()
    return remote == "localhost" || remote == "127.0.0.1" || remote == "::1" || remote.endsWith(".local")
}

@Serializable
private data class ForceDecisionRequest(
    val jobId: String,
    val decision: String,
)

@Serializable
private data class BoothActionResponse(
    val ok: Boolean,
    val message: String,
    val jobId: String? = null,
    val clearedJobs: Int? = null,
)

@Serializable
private data class PendingDecisionJob(
    val jobId: String,
    val status: String,
    val updatedAt: Long,
    val artifactUrl: String?,
)

/**
 * Background job processor.
 * Runs on IO dispatcher, updates queue state as it goes.
 */
private suspend fun processJob(serverModule: ServerModule, job: CrashJob) {
    try {
        serverModule.queue.startProcessing(job.jobId)

        val result = withContext(Dispatchers.IO) {
            serverModule.analysisAgent.process(job.report)
        }

        when (result) {
            is CrashAnalysisResult.New -> {
                logger.info("Job ${job.jobId}: New crash analyzed. Creating Notion ticket...")
                val ticketResult = withContext(Dispatchers.IO) {
                    serverModule.notionBackend.createTicket(result.report)
                }
                if (ticketResult.success) {
                    logger.info("Job ${job.jobId}: Ticket created: ${ticketResult.url}")
                    serverModule.queue.complete(job.jobId, ticketResult.url ?: "notion://created")
                } else {
                    logger.error("Job ${job.jobId}: Failed to create ticket: ${ticketResult.errorMessage}")
                    serverModule.queue.fail(job.jobId, ticketResult.errorMessage ?: "Unknown error")
                }
                FileDedupPersistence.save(serverModule.dedupCache, EnvConfig.dedupCachePath)
            }

            is CrashAnalysisResult.Duplicate -> {
                logger.info("Job ${job.jobId}: Duplicate crash detected: ${result.report.fingerprint}")
                serverModule.queue.complete(job.jobId, "duplicate://${result.report.fingerprint}")
            }

            is CrashAnalysisResult.Fallback -> {
                logger.warn("Job ${job.jobId}: AI analysis failed, creating fallback ticket...", result.error)
                val ticketResult = withContext(Dispatchers.IO) {
                    serverModule.notionBackend.createTicket(result.report)
                }
                if (ticketResult.success) {
                    serverModule.queue.complete(job.jobId, ticketResult.url ?: "notion://fallback")
                } else {
                    serverModule.queue.fail(job.jobId, ticketResult.errorMessage ?: "Fallback ticket failed")
                }
                FileDedupPersistence.save(serverModule.dedupCache, EnvConfig.dedupCachePath)
            }
        }
    } catch (e: Exception) {
        logger.error("Job ${job.jobId}: Processing failed", e)
        serverModule.queue.fail(job.jobId, e.message ?: "Unknown error")
    }
}

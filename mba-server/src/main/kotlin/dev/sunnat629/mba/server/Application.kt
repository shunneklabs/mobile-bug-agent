package dev.sunnat629.mba.server

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.MBALog
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
import java.util.*

private const val TAG = "Server"

// ------------------------------------------------------------------ //
//  Environment Config — fail fast on startup, not at request time
// ------------------------------------------------------------------ //

private object EnvConfig {
    val geminiApiKey: String = requireEnv("GEMINI_API_KEY")

    /**
     * Notion creds are now **optional** — leaving them blank disables the
     * Notion ticket path entirely so the server can run in GitHub-only or
     * dry-run modes (clients still drive this per-crash via
     * `RawCrashReport.skipNotion`, but missing creds also force-skip).
     */
    val notionApiKey: String = System.getenv("NOTION_API_KEY") ?: ""
    val notionDatabaseId: String = System.getenv("NOTION_DATABASE_ID") ?: ""

    val serverApiKey: String = System.getenv("MBA_SERVER_API_KEY") ?: ""
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dedupCachePath: String = System.getenv("MBA_DEDUP_CACHE_PATH") ?: "data/dedup-cache.json"
    val dataDir: String = System.getenv("MBA_DATA_DIR") ?: "data"

    // ---- GitHub auto-fix path (optional) ---- //
    val githubToken: String = System.getenv("GITHUB_TOKEN") ?: ""
    val githubOwner: String = System.getenv("GITHUB_OWNER") ?: ""
    val githubRepo: String = System.getenv("GITHUB_REPO") ?: ""
    val githubBaseBranch: String = System.getenv("GITHUB_BASE_BRANCH")?.takeIf { it.isNotBlank() } ?: "main"

    private fun requireEnv(name: String): String =
        System.getenv(name)
            ?: error("Required environment variable $name is not set. Server cannot start.")
}

fun main() {
    // Enable Kermit-backed MBALog for the entire server runtime.
    MBALog.enabled = true
    MBALog.i(TAG, "Starting MBA Server on port ${EnvConfig.port}")
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
        githubToken = EnvConfig.githubToken,
        githubOwner = EnvConfig.githubOwner,
        githubRepo = EnvConfig.githubRepo,
        githubBaseBranch = EnvConfig.githubBaseBranch,
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
        MBALog.i(TAG, "Shutting down — saving state and closing resources...")
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
            MBALog.i(TAG, "Received crash report: ${rawReport.id}")

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
 *
 * Pipeline (per crash):
 *  1. Analyze (Gemini → ProcessedCrashReport) — surfaced as `progress` events.
 *  2. If `report.autoFix && severity ∈ {HIGH, CRITICAL}` and GitHub configured →
 *     create Issue + tracking branch via [GitHubAutoFixOpener].
 *  3. If `!report.skipNotion` and Notion configured → create Notion ticket.
 *  4. Pick the terminal SSE event:
 *      - GitHub PR opened (if we have one) → [CrashProcessingQueue.prOpened].
 *      - Otherwise Notion URL → [CrashProcessingQueue.complete].
 *      - Otherwise a synthetic `analysis://<fingerprint>` URL (dry-run).
 */
private suspend fun processJob(serverModule: ServerModule, job: CrashJob) {
    val queue = serverModule.queue
    val raw = job.report
    try {
        queue.startProcessing(job.jobId)
        queue.progress(job.jobId, "Sanitizing PII + computing fingerprint…")

        val result = withContext(Dispatchers.IO) {
            serverModule.analysisAgent.process(raw)
        }

        when (result) {
            is CrashAnalysisResult.Duplicate -> {
                queue.progress(
                    job.jobId,
                    "Duplicate crash (fingerprint=${result.report.fingerprint.take(8)}…) — skipping LLM",
                )
                MBALog.i(TAG, "Job ${job.jobId}: Duplicate: ${result.report.fingerprint}")
                queue.complete(job.jobId, "duplicate://${result.report.fingerprint}")
            }

            is CrashAnalysisResult.New -> {
                queue.progress(
                    job.jobId,
                    "Analysis complete — severity=${result.report.severity}, " +
                        "confidence=${"%.0f".format(result.report.confidence * 100)}%",
                )
                handlePostAnalysis(serverModule, job, raw, result.report, fallback = false)
                FileDedupPersistence.save(serverModule.dedupCache, EnvConfig.dedupCachePath)
            }

            is CrashAnalysisResult.Fallback -> {
                queue.progress(
                    job.jobId,
                    "AI analysis failed (${result.error.message}) — using fallback report",
                    level = "warning",
                )
                MBALog.w(TAG, "Job ${job.jobId}: AI analysis failed, using fallback… (${result.error.message})")
                handlePostAnalysis(serverModule, job, raw, result.report, fallback = true)
                FileDedupPersistence.save(serverModule.dedupCache, EnvConfig.dedupCachePath)
            }
        }
    } catch (e: Exception) {
        MBALog.e(TAG, "Job ${job.jobId}: Processing failed", e)
        queue.fail(job.jobId, e.message ?: "Unknown error")
    }
}

/**
 * Route a successful (or fallback) analysis through the configured backends.
 *
 * - `autoFix=true` + severity ∈ {HIGH, CRITICAL} + GitHub configured → Issue + branch.
 * - `skipNotion=false` + Notion configured → Notion ticket.
 *
 * Notion failures fall through to `queue.fail`, but only if no GitHub PR has
 * already been opened (so users always see at least one terminal event).
 */
private suspend fun handlePostAnalysis(
    serverModule: ServerModule,
    job: CrashJob,
    raw: dev.sunnat629.mba.core.model.RawCrashReport,
    processed: dev.sunnat629.mba.core.model.ProcessedCrashReport,
    fallback: Boolean,
) {
    val queue = serverModule.queue
    val severityOk = serverModule.severityRouter.shouldAutoFix(processed.severity)
    val wantGitHub = raw.autoFix && severityOk && serverModule.githubAutoFixOpener != null
    val wantNotion = !raw.skipNotion && serverModule.notionBackend != null

    if (raw.autoFix && !severityOk) {
        queue.progress(
            job.jobId,
            "autoFix=true ignored — severity ${processed.severity} below HIGH gate",
            level = "warning",
        )
    }
    if (raw.autoFix && serverModule.githubAutoFixOpener == null) {
        queue.progress(
            job.jobId,
            "autoFix=true requested but GitHub backend is not configured (set GITHUB_TOKEN/OWNER/REPO)",
            level = "warning",
        )
    }

    // ---- GitHub auto-fix path ---- //
    var prOpened = false
    if (wantGitHub) {
        queue.progress(job.jobId, "Opening GitHub issue…", stage = "github_pr")
        val ghResult = withContext(Dispatchers.IO) {
            serverModule.githubAutoFixOpener!!.openAutoFix(processed)
        }
        when (ghResult) {
            is dev.sunnat629.mba.github.AutoFixResult.Success -> {
                queue.progress(
                    job.jobId,
                    "Issue #${ghResult.issueNumber} opened — branch '${ghResult.branch}' ready for agent patch",
                    stage = "github_pr",
                )
                queue.prOpened(job.jobId, ghResult.issueUrl)
                prOpened = true
            }
            is dev.sunnat629.mba.github.AutoFixResult.IssueOnly -> {
                queue.progress(
                    job.jobId,
                    "Issue #${ghResult.issueNumber} opened but branch creation failed: ${ghResult.branchError}",
                    stage = "github_pr",
                    level = "warning",
                )
                queue.prOpened(job.jobId, ghResult.issueUrl)
                prOpened = true
            }
            is dev.sunnat629.mba.github.AutoFixResult.Failure -> {
                queue.progress(
                    job.jobId,
                    "GitHub auto-fix failed: ${ghResult.reason}",
                    stage = "github_pr",
                    level = "error",
                )
            }
        }
    }

    // ---- Notion path ---- //
    if (wantNotion) {
        val notionBackend = serverModule.notionBackend!!
        val notionStage = "notion_ticket"
        queue.progress(
            job.jobId,
            if (fallback) "Creating Notion fallback ticket…" else "Creating Notion ticket…",
            stage = notionStage,
        )
        val ticket = withContext(Dispatchers.IO) { notionBackend.createTicket(processed) }
        if (ticket.success) {
            MBALog.i(TAG, "Job ${job.jobId}: Notion ticket created: ${ticket.url}")
            queue.complete(job.jobId, ticket.url ?: "notion://created")
        } else {
            val msg = ticket.errorMessage ?: "Unknown Notion error"
            MBALog.e(TAG, "Job ${job.jobId}: Notion failed: $msg")
            if (prOpened) {
                // GitHub already produced a terminal `prOpened` event — don't override with FAILED.
                queue.progress(job.jobId, "Notion ticket failed: $msg", stage = notionStage, level = "error")
            } else {
                queue.fail(job.jobId, msg)
            }
        }
    } else if (!prOpened) {
        // Dry-run path: neither GitHub nor Notion ran. Emit a synthetic terminal
        // event so the booth doesn't sit at `analyzing` forever.
        val reason = when {
            raw.skipNotion && serverModule.notionBackend == null -> "skipNotion=true, Notion not configured"
            raw.skipNotion -> "skipNotion=true"
            serverModule.notionBackend == null -> "Notion not configured"
            else -> "no backend ran"
        }
        queue.progress(job.jobId, "Skipping ticket creation ($reason)")
        queue.complete(job.jobId, "analysis://${processed.fingerprint}")
    }
}

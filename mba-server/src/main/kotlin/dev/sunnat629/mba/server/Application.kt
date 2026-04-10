package dev.sunnat629.mba.server

import dev.sunnat629.mba.agent.AgentFactory
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.notion.NotionTicketBackend
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

private val logger = LoggerFactory.getLogger("MBAServer")

// ------------------------------------------------------------------ //
//  Environment Config — fail fast on startup, not at request time
// ------------------------------------------------------------------ //

private object EnvConfig {
    val geminiApiKey: String = requireEnv("GEMINI_API_KEY")
    val notionApiKey: String = requireEnv("NOTION_API_KEY")
    val notionDatabaseId: String = requireEnv("NOTION_DATABASE_ID")
    val serverApiKey: String = requireEnv("MBA_SERVER_API_KEY")
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dedupCachePath: String = System.getenv("MBA_DEDUP_CACHE_PATH") ?: "data/dedup-cache.json"

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

    // ---- Initialize dependencies ---- //
    val llmConfig = LLMConfig(
        provider = LLM.Provider.GEMINI,
        model = "gemini-2.0-flash",
        apiKey = EnvConfig.geminiApiKey,
    )

    val agentFactory = AgentFactory(llmConfig)
    val piiSanitizer = PIISanitizer()
    val dedupCache = LocalDedupCache(maxSize = 1000, ttl = 24.hours)
    val analysisAgent = CrashAnalysisAgent(agentFactory, piiSanitizer, dedupCache)
    val notionBackend = NotionTicketBackend(EnvConfig.notionApiKey, EnvConfig.notionDatabaseId)

    // ---- Restore dedup cache from disk ---- //
    FileDedupPersistence.restore(dedupCache, EnvConfig.dedupCachePath)

    // ---- Clean up resources on shutdown ---- //
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Shutting down — saving dedup cache and closing resources...")
        FileDedupPersistence.save(dedupCache, EnvConfig.dedupCachePath)
        agentFactory.close()
        notionBackend.close()
    }

    routing {
        get("/") {
            call.respondText("Mobile Bug Agent (MBA) Server is running!")
        }

        get("/health") {
            call.respond(mapOf(
                "status" to "healthy",
                "dedupCacheSize" to dedupCache.size(),
            ))
        }

        // ---- Authenticated crash report endpoint ---- //
        post("/report") {
            // Basic API key auth
            val apiKey = call.request.header("X-MBA-API-Key")
            if (apiKey != EnvConfig.serverApiKey) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
                return@post
            }

            try {
                val rawReport = call.receive<RawCrashReport>()
                logger.info("Received crash report: ${rawReport.id}")

                // Process on IO dispatcher — don't block Netty event loop
                val result = withContext(Dispatchers.IO) {
                    analysisAgent.process(rawReport)
                }

                when (result) {
                    is CrashAnalysisResult.New -> {
                        logger.info("New crash analyzed. Creating Notion ticket...")
                        val ticketResult = withContext(Dispatchers.IO) {
                            notionBackend.createTicket(result.report)
                        }
                        if (ticketResult.success) {
                            logger.info("Ticket created: ${ticketResult.url}")
                        } else {
                            logger.error("Failed to create ticket: ${ticketResult.errorMessage}")
                        }
                        // Persist cache after new crash processed
                        FileDedupPersistence.save(dedupCache, EnvConfig.dedupCachePath)
                        call.respond(ticketResult)
                    }

                    is CrashAnalysisResult.Duplicate -> {
                        logger.info("Duplicate crash detected: ${result.report.fingerprint}")
                        call.respond(mapOf(
                            "status" to "duplicate",
                            "fingerprint" to result.report.fingerprint,
                        ))
                    }

                    is CrashAnalysisResult.Fallback -> {
                        logger.warn("AI analysis failed, creating fallback ticket...", result.error)
                        val ticketResult = withContext(Dispatchers.IO) {
                            notionBackend.createTicket(result.report)
                        }
                        FileDedupPersistence.save(dedupCache, EnvConfig.dedupCachePath)
                        call.respond(ticketResult)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error processing crash report", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Internal server error")),
                )
            }
        }
    }
}

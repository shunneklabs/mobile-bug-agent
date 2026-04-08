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
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

private val logger = LoggerFactory.getLogger("MBAServer")

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
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

    // Initialize dependencies
    val geminiKey = System.getenv("GEMINI_API_KEY") ?: "REPLACE_WITH_GEMINI_API_KEY"
    val notionKey = System.getenv("NOTION_API_KEY") ?: "REPLACE_WITH_NOTION_API_KEY"
    val notionDbId = System.getenv("NOTION_DATABASE_ID") ?: "REPLACE_WITH_NOTION_DATABASE_ID"

    val llmConfig = LLMConfig(
        provider = LLM.Provider.GEMINI,
        model = "gemini-1.5-flash",
        apiKey = geminiKey
    )
    
    val agentFactory = AgentFactory(llmConfig)
    val piiSanitizer = PIISanitizer()
    val dedupCache = LocalDedupCache(maxSize = 1000, ttl = 24.hours)
    
    val analysisAgent = CrashAnalysisAgent(agentFactory, piiSanitizer, dedupCache)
    val notionBackend = NotionTicketBackend(notionKey, notionDbId)

    routing {
        get("/") {
            call.respondText("Mobile Bug Agent (MBA) Server is running!")
        }

        post("/report") {
            try {
                val rawReport = call.receive<RawCrashReport>()
                logger.info("Received crash report: ${rawReport.id}")

                // 1. Process with AI Agent
                val result = analysisAgent.process(rawReport)

                when (result) {
                    is CrashAnalysisResult.New -> {
                        logger.info("New crash analyzed. Creating Notion ticket...")
                        val ticketResult = notionBackend.createTicket(result.report)
                        if (ticketResult.success) {
                            logger.info("Ticket created: ${ticketResult.url}")
                            call.respond(ticketResult)
                        } else {
                            logger.error("Failed to create ticket: ${ticketResult.errorMessage}")
                            call.respond(ticketResult)
                        }
                    }
                    is CrashAnalysisResult.Duplicate -> {
                        logger.info("Duplicate crash detected: ${result.report.fingerprint}")
                        // Optionally update existing ticket count here
                        call.respond(mapOf("status" to "duplicate", "fingerprint" to result.report.fingerprint))
                    }
                    is CrashAnalysisResult.Fallback -> {
                        logger.warn("AI analysis failed. Creating fallback ticket...")
                        val ticketResult = notionBackend.createTicket(result.report)
                        call.respond(ticketResult)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error processing crash report", e)
                call.respond(io.ktor.http.HttpStatusCode.InternalServerError, "Error: ${e.message}")
            }
        }
    }
}

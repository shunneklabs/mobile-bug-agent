package dev.sunnat629.mba.server

import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.notion.NotionClient
import dev.sunnat629.mba.notion.NotionTicketBackend
import dev.sunnat629.mba.server.model.ReportRequest
import dev.sunnat629.mba.server.model.ReportResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun Application.mbaServerModule() {
    val config = ServerConfig.load()

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        )
    }

    // MVP: create dependencies in-process.
    val notionClient = NotionClient(config.notion)
    val ticketBackend = NotionTicketBackend(notion = notionClient, config = config.notion)

    val agent = CrashAnalysisAgent(
        piiSanitizer = PIISanitizer(),
        dedupCache = LocalDedupCache(),
    )

    routing {
        post("/report") {
            val req = call.receive<ReportRequest>()
            val raw: RawCrashReport = req.crash

            val result = agent.process(raw)
            val processed: ProcessedCrashReport = when (result) {
                is dev.sunnat629.mba.agent.CrashAnalysisResult.New -> result.report
                is dev.sunnat629.mba.agent.CrashAnalysisResult.Duplicate -> {
                    // MVP: still create a ticket for now (or could be no-op). Build minimal processed report.
                    val parsed = dev.sunnat629.mba.agent.tools.StackTraceParserTool.parse(raw.stackTrace)
                    val severity = dev.sunnat629.mba.agent.tools.SeverityClassifierTool.classify(parsed, raw.device)
                    val summary = dev.sunnat629.mba.agent.tools.SummaryGeneratorTool.generate(parsed, severity, raw)
                    ProcessedCrashReport(
                        raw = raw,
                        fingerprint = result.fingerprint,
                        severity = severity.severity,
                        confidence = severity.confidence,
                        title = summary.title,
                        description = summary.description,
                        stepsToReproduce = summary.stepsToReproduce,
                        possibleCause = summary.possibleCause,
                        crashFile = parsed.crashFile,
                        crashLine = parsed.crashLine,
                        crashMethod = parsed.crashMethod,
                        isAppCode = parsed.isAppCode,
                        sanitizedStackTrace = raw.stackTrace,
                    )
                }
            }

            val ticket = ticketBackend.createTicket(processed)

            call.respond(
                status = HttpStatusCode.OK,
                message = ReportResponse(
                    processed = processed,
                    ticket = ticket,
                )
            )
        }
    }
}

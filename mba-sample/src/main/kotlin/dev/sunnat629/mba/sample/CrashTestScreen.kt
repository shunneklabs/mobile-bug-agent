package dev.sunnat629.mba.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.notion.NotionTicketBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashTestScreen() {
    LaunchedEffect(Unit) {
        MBA.setScreen("CrashTestScreen")
        MBA.addBreadcrumb("Opened crash test screen")
    }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Catch a crash, log it, send to BOTH Notion DBs, update UI. */
    fun triggerAndSend(triggerName: String, emoji: String, block: () -> Unit) {
        if (isLoading) return
        isLoading = true
        statusMessage = "$emoji Catching & sending '$triggerName'..."

        val error: Throwable = try { block(); null } catch (e: Throwable) { e }
            ?: run {
                statusMessage = "$emoji No exception thrown"
                isLoading = false
                return
            }

        MBA.logError(
            if (error is Exception) error else RuntimeException(error),
            mapOf("trigger" to triggerName),
        )

        scope.launch(Dispatchers.IO) {
            val result = sendRealCrashToNotion(error, triggerName)
            isLoading = false
            statusMessage = result
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MBA Crash Lab \uD83D\uDD2C") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Config status
            val hasKey = BuildConfig.NOTION_API_KEY.isNotBlank()
            val hasTicketDb = BuildConfig.NOTION_TICKET_DB_ID.isNotBlank()
            val hasCrashDb = BuildConfig.NOTION_CRASH_DB_ID.isNotBlank()

            if (!hasKey || !hasTicketDb) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "\u26A0\uFE0F Config:\n" +
                            "  NOTION_TOKEN: ${if (hasKey) "\u2705" else "\u274c"}\n" +
                            "  NOTION_TICKET_DB_ID_OR_URL: ${if (hasTicketDb) "\u2705" else "\u274c"}\n" +
                            "  NOTION_CRASH_DB_ID_OR_URL: ${if (hasCrashDb) "\u2705" else "\u26A0\uFE0F optional"}\n\n" +
                            "Add to local.properties and REBUILD.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "\u2705 Config OK\n" +
                            "  Bug Tickets DB: ${BuildConfig.NOTION_TICKET_DB_ID.take(8)}...\n" +
                            "  Crash Reports DB: ${if (hasCrashDb) "${BuildConfig.NOTION_CRASH_DB_ID.take(8)}..." else "not set"}",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Status card
            statusMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            msg.contains("\u2705") -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                            msg.contains("\u274c") -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ============================================================ //
            //  CRASH BUTTONS → catch + log + send to Notion
            // ============================================================ //

            Text(
                "Trigger Real Crashes \u2192 Notion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Each button triggers a real exception, catches it safely, " +
                    "logs via MBA, and sends to Bug Tickets" +
                    if (hasCrashDb) " + Crash Reports DBs." else " DB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            CrashButton("\uD83D\uDCA5", "NullPointerException", "Catch \u2192 Notion", MaterialTheme.colorScheme.error, !isLoading) {
                MBA.addBreadcrumb("Tapped: NPE")
                triggerAndSend("NullPointerException", "\uD83D\uDCA5") {
                    val s: String? = null; s!!.length
                }
            }

            CrashButton("\uD83D\uDD25", "IndexOutOfBounds", "Catch \u2192 Notion", Color(0xFFE65100), !isLoading) {
                MBA.addBreadcrumb("Tapped: IOOB")
                triggerAndSend("IndexOutOfBounds", "\uD83D\uDD25") {
                    listOf(1, 2, 3)[10]
                }
            }

            CrashButton("\u26A1", "IllegalStateException", "Catch \u2192 Notion", Color(0xFF6A1B9A), !isLoading) {
                MBA.addBreadcrumb("Tapped: ISE")
                triggerAndSend("IllegalStateException", "\u26A1") {
                    throw IllegalStateException("Simulated illegal state in checkout flow")
                }
            }

            CrashButton("\uD83D\uDCDD", "JSON Parse Error", "Catch \u2192 Notion", Color(0xFF00838F), !isLoading) {
                MBA.addBreadcrumb("Tapped: JSON")
                triggerAndSend("JsonDecodingException", "\uD83D\uDCDD") {
                    kotlinx.serialization.json.Json.parseToJsonElement("{bad json}")
                }
            }

            CrashButton("\uD83E\uDDF5", "StackOverflowError", "Catch \u2192 Notion", Color(0xFF37474F), !isLoading) {
                MBA.addBreadcrumb("Tapped: SOE")
                triggerAndSend("StackOverflowError", "\uD83E\uDDF5") {
                    throw StackOverflowError("Simulated stack overflow")
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("\u2699\uFE0F local.properties", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "NOTION_TOKEN=ntn_...\n" +
                            "NOTION_TICKET_DB_ID_OR_URL=...\n" +
                            "NOTION_CRASH_DB_ID_OR_URL=...\n" +
                            "GEMINI_API_KEY=AIza...\n\n" +
                            "Rebuild. Logcat filter: \"MBA\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ================================================================== //
//  Send a REAL caught crash to Notion (both DBs)
// ================================================================== //

private suspend fun sendRealCrashToNotion(throwable: Throwable, triggerName: String): String {
    val apiKey = BuildConfig.NOTION_API_KEY
    val ticketDbId = BuildConfig.NOTION_TICKET_DB_ID
    val crashDbId = BuildConfig.NOTION_CRASH_DB_ID

    if (apiKey.isBlank() || ticketDbId.isBlank()) {
        return "\u274c Missing credentials. Set NOTION_TOKEN + NOTION_TICKET_DB_ID_OR_URL in local.properties and rebuild."
    }

    return try {
        val backend = NotionTicketBackend(
            apiKey = apiKey,
            bugTicketDbId = ticketDbId,
            crashReportDbId = crashDbId.ifBlank { null },
        )

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val firstAppFrame = stackTrace.lines()
            .firstOrNull { it.trim().startsWith("at dev.sunnat629") }?.trim()
        val crashFile = firstAppFrame?.substringAfter("(")?.substringBefore(":") ?: "Unknown"
        val crashLine = firstAppFrame?.substringAfter(":")?.substringBefore(")")?.toIntOrNull()
        val crashMethod = firstAppFrame?.substringAfter("at ")?.substringBefore("(") ?: "Unknown"

        val device = DeviceContext(
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            osVersion = android.os.Build.VERSION.RELEASE,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            locale = java.util.Locale.getDefault().toLanguageTag(),
            totalMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            availableMemoryMb = Runtime.getRuntime().freeMemory() / (1024 * 1024),
        )

        val exceptionType = throwable::class.qualifiedName ?: "Unknown"

        val raw = RawCrashReport(
            id = "real-${System.currentTimeMillis()}",
            exceptionType = exceptionType,
            message = throwable.message,
            stackTrace = stackTrace,
            threadName = "main",
            isFatal = true,
            device = device,
            appVersion = "1.0.0-sample",
            buildType = "debug",
            currentScreen = MBA.currentScreen ?: "CrashTestScreen",
            breadcrumbs = listOf("Tapped: $triggerName"),
        )

        val report = ProcessedCrashReport(
            raw = raw,
            fingerprint = "real-${exceptionType.hashCode()}-${System.currentTimeMillis()}",
            severity = Severity.MEDIUM,
            confidence = 1.0f,
            title = "$triggerName in ${MBA.currentScreen ?: "CrashTestScreen"}",
            description = "$exceptionType: ${throwable.message ?: "(no message)"}. Triggered from MBA Sample App.",
            possibleCause = "Intentionally triggered from MBA Crash Lab.",
            crashFile = crashFile,
            crashLine = crashLine,
            crashMethod = crashMethod,
            isAppCode = true,
            sanitizedStackTrace = stackTrace.take(2000),
        )

        MBALog.d("Sample", "Sending $triggerName to Notion (ticket=${ticketDbId.take(8)}, crash=${crashDbId.take(8)})...")
        val result = backend.createTicket(report)
        backend.close()

        MBALog.d("Sample", "Result: success=${result.success}, id=${result.ticketId}")

        if (result.success) {
            "\u2705 $triggerName sent to Notion!\n" +
                "Bug Ticket: ${result.ticketId.take(12)}...\n" +
                (if (crashDbId.isNotBlank()) "Crash Report: also created\n" else "") +
                "URL: ${result.url}"
        } else {
            "\u274c $triggerName failed:\n${result.errorMessage?.take(300)}"
        }
    } catch (e: Exception) {
        MBALog.e("Sample", "Failed to send $triggerName", e)
        "\u274c Error: ${e::class.simpleName}: ${e.message}"
    }
}

@Composable
private fun CrashButton(
    emoji: String, label: String, description: String,
    containerColor: Color, enabled: Boolean = true, onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$emoji  $label", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

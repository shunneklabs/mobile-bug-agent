package dev.sunnat629.mba.sample

import android.util.Log
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
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.notion.NotionTicketBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

            // ============================================================ //
            //  SEND TO NOTION — Pre-MVP Test
            // ============================================================ //

            Text(
                text = "Pre-MVP: Notion Integration Test",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Show current config status
            val hasKey = BuildConfig.NOTION_API_KEY.isNotBlank()
            val hasDb = BuildConfig.NOTION_DB_ID.isNotBlank()
            if (!hasKey || !hasDb) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "\u26A0\uFE0F Config status:\n" +
                            "  NOTION_TOKEN: ${if (hasKey) "\u2705" else "\u274c MISSING"}\n" +
                            "  NOTION_CRASH_DB_ID_OR_URL: ${if (hasDb) "\u2705" else "\u274c MISSING"}\n\n" +
                            "Add to local.properties and REBUILD.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = {
                    isLoading = true
                    statusMessage = "\u23F3 Sending test crash report to Notion..."
                    scope.launch(Dispatchers.IO) {
                        val result = sendTestCrashToNotion()
                        isLoading = false
                        statusMessage = result
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B5E20),
                ),
                shape = MaterialTheme.shapes.medium,
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Sending...")
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "\uD83D\uDE80  Send Test Crash to Notion",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Creates a real page in your Notion DB",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }

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
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ============================================================ //
            //  Crash Trigger Buttons (safe mode)
            // ============================================================ //

            Text(
                text = "Crash Triggers (safe \u2014 caught & logged)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            CrashButton("\uD83D\uDCA5", "NullPointerException", "Caught & logged", MaterialTheme.colorScheme.error) {
                try {
                    MBA.addBreadcrumb("Tapped: NPE")
                    val s: String? = null; s!!.length
                } catch (e: Exception) {
                    MBA.logError(e, mapOf("trigger" to "npe"))
                    statusMessage = "\uD83D\uDCA5 NPE caught: ${e.message}"
                }
            }

            CrashButton("\uD83D\uDD25", "IndexOutOfBounds", "Caught & logged", Color(0xFFE65100)) {
                try {
                    MBA.addBreadcrumb("Tapped: IOOB")
                    listOf(1, 2, 3)[10]
                } catch (e: Exception) {
                    MBA.logError(e, mapOf("trigger" to "ioob"))
                    statusMessage = "\uD83D\uDD25 IOOB caught: ${e.message}"
                }
            }

            CrashButton("\u26A1", "Coroutine Crash", "Captured by MBA handler", Color(0xFF6A1B9A)) {
                MBA.addBreadcrumb("Tapped: Coroutine")
                CoroutineScope(Dispatchers.Default + MBA.exceptionHandler).launch {
                    kotlinx.coroutines.delay(200)
                    throw IllegalStateException("Coroutine crash test")
                }
                statusMessage = "\u26A1 Coroutine crash fired"
            }

            CrashButton("\uD83D\uDCDD", "Non-Fatal Error", "JSON parse logged", Color(0xFF00838F)) {
                try {
                    MBA.addBreadcrumb("Tapped: JSON error")
                    kotlinx.serialization.json.Json.parseToJsonElement("{bad}")
                } catch (e: Exception) {
                    MBA.logError(e, mapOf("trigger" to "json"))
                    statusMessage = "\uD83D\uDCDD Logged: ${e.message}"
                }
            }

            CrashButton("\uD83E\uDDF5", "Background Thread", "Caught on worker", Color(0xFF37474F)) {
                MBA.addBreadcrumb("Tapped: BG thread")
                Thread({
                    try {
                        Thread.sleep(300)
                        throw StackOverflowError("BG thread test")
                    } catch (e: Throwable) {
                        MBA.logError(RuntimeException(e), mapOf("trigger" to "bg"))
                    }
                }, "MBA-Test").start()
                statusMessage = "\uD83E\uDDF5 BG error fired"
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Setup instructions
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "\u2699\uFE0F Setup (local.properties)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "NOTION_TOKEN=ntn_or_secret_...\n" +
                            "NOTION_CRASH_DB_ID_OR_URL=your-db-id\n" +
                            "GEMINI_API_KEY=AIza...\n\n" +
                            "Then rebuild. Filter Logcat by \"MBA\" to see SDK logs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ================================================================== //
//  Notion test sender
// ================================================================== //

private suspend fun sendTestCrashToNotion(): String {
    val apiKey = BuildConfig.NOTION_API_KEY
    val dbId = BuildConfig.NOTION_DB_ID

    Log.d("MBA-Sample", "sendTestCrashToNotion: apiKey=${if (apiKey.isNotBlank()) "${apiKey.take(12)}..." else "EMPTY"}, dbId=${if (dbId.isNotBlank()) "${dbId.take(12)}..." else "EMPTY"}")

    if (apiKey.isBlank() || dbId.isBlank()) {
        return "\u274c Missing Notion credentials!\n\n" +
            "Your local.properties needs:\n" +
            "NOTION_TOKEN=ntn_...\n" +
            "NOTION_CRASH_DB_ID_OR_URL=abc...\n\n" +
            "Then REBUILD the app (BuildConfig is compile-time)."
    }

    return try {
        Log.d("MBA-Sample", "Creating NotionTicketBackend...")
        val backend = NotionTicketBackend(apiKey = apiKey, databaseId = dbId)

        val fakeDevice = DeviceContext(
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            marketingName = "Pixel 8 Pro",
            osVersion = "15",
            sdkInt = 35,
            locale = "en-US",
            totalMemoryMb = 8192,
            availableMemoryMb = 3200,
        )

        val fakeRaw = RawCrashReport(
            id = "test-${System.currentTimeMillis()}",
            exceptionType = "java.lang.NullPointerException",
            message = "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
            stackTrace = """java.lang.NullPointerException: Attempt to invoke virtual method
    at dev.sunnat629.mba.sample.CheckoutViewModel.processPayment(CheckoutViewModel.kt:87)
    at dev.sunnat629.mba.sample.CheckoutViewModel.onConfirmClick(CheckoutViewModel.kt:45)
    at dev.sunnat629.mba.sample.CheckoutFragment.onClick(CheckoutFragment.kt:112)
    at android.view.View.performClick(View.java:7448)""",
            threadName = "main",
            isFatal = true,
            device = fakeDevice,
            appVersion = "1.0.0-test",
            buildType = "debug",
            currentScreen = "CheckoutScreen",
            breadcrumbs = listOf("App launched", "Cart", "Checkout", "Confirm Payment"),
        )

        val fakeProcessed = ProcessedCrashReport(
            raw = fakeRaw,
            fingerprint = "test-fp-${System.currentTimeMillis()}",
            severity = Severity.HIGH,
            confidence = 0.92f,
            title = "\uD83D\uDEA8 [Test] Checkout crashes on payment",
            description = "NullPointerException in CheckoutViewModel.processPayment(). Test crash from MBA Sample.",
            stepsToReproduce = "1. Open app\n2. Add to cart\n3. Checkout\n4. Confirm Payment",
            possibleCause = "Payment API response is null",
            crashFile = "CheckoutViewModel.kt",
            crashLine = 87,
            crashMethod = "processPayment",
            isAppCode = true,
            sanitizedStackTrace = fakeRaw.stackTrace,
        )

        Log.d("MBA-Sample", "Sending to Notion...")
        val result = backend.createTicket(fakeProcessed)
        backend.close()

        Log.d("MBA-Sample", "Notion result: success=${result.success}, id=${result.ticketId}, url=${result.url}, error=${result.errorMessage}")

        if (result.success) {
            "\u2705 Ticket created!\n\nID: ${result.ticketId}\nURL: ${result.url}\n\nCheck your Notion database!"
        } else {
            "\u274c Ticket creation failed:\n${result.errorMessage}"
        }
    } catch (e: Exception) {
        Log.e("MBA-Sample", "Notion send failed", e)
        MBA.logError(e, mapOf("trigger" to "notion_test"))
        "\u274c Error: ${e::class.simpleName}\n${e.message}\n\nCheck Logcat for full stack trace."
    }
}

@Composable
private fun CrashButton(
    emoji: String,
    label: String,
    description: String,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$emoji  $label",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

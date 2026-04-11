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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.notion.NotionTicketBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
            //  🚀 SEND TO NOTION — Pre-MVP Test Button
            // ============================================================ //

            Text(
                text = "Pre-MVP: Notion Integration Test",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

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
                    containerColor = Color(0xFF1B5E20), // dark green
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

            // ── Status card ──────────────────────────────────────────── //
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
            //  Crash Trigger Buttons (all wrapped in try/catch now)
            // ============================================================ //

            Text(
                text = "Crash Triggers (safe mode \u2014 caught & logged)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Button 1: NullPointerException
            CrashButton(
                emoji = "\uD83D\uDCA5",
                label = "NullPointerException",
                description = "Caught & logged via MBA.logError()",
                containerColor = MaterialTheme.colorScheme.error,
                onClick = {
                    try {
                        MBA.addBreadcrumb("Tapped: NullPointerException")
                        val nullString: String? = null
                        nullString!!.length
                    } catch (e: Exception) {
                        MBA.logError(e, mapOf("trigger" to "npe_button"))
                        statusMessage = "\uD83D\uDCA5 NPE caught & logged: ${e.message}"
                    }
                },
            )

            // Button 2: IndexOutOfBoundsException
            CrashButton(
                emoji = "\uD83D\uDD25",
                label = "IndexOutOfBoundsException",
                description = "Caught & logged via MBA.logError()",
                containerColor = Color(0xFFE65100),
                onClick = {
                    try {
                        MBA.addBreadcrumb("Tapped: IndexOutOfBoundsException")
                        val list = listOf(1, 2, 3)
                        @Suppress("UNUSED_VARIABLE")
                        val boom = list[10]
                    } catch (e: Exception) {
                        MBA.logError(e, mapOf("trigger" to "ioob_button"))
                        statusMessage = "\uD83D\uDD25 IOOB caught & logged: ${e.message}"
                    }
                },
            )

            // Button 3: Coroutine Crash
            CrashButton(
                emoji = "\u26A1",
                label = "Coroutine Crash",
                description = "Caught by MBA.exceptionHandler",
                containerColor = Color(0xFF6A1B9A),
                onClick = {
                    MBA.addBreadcrumb("Tapped: Coroutine Crash")
                    CoroutineScope(Dispatchers.Default + MBA.exceptionHandler).launch {
                        kotlinx.coroutines.delay(200)
                        throw IllegalStateException("Coroutine crash: unexpected state")
                    }
                    statusMessage = "\u26A1 Coroutine crash fired (captured by MBA)"
                },
            )

            // Button 4: Non-Fatal Error
            CrashButton(
                emoji = "\uD83D\uDCDD",
                label = "Non-Fatal Error",
                description = "JSON parse error logged via MBA.logError()",
                containerColor = Color(0xFF00838F),
                onClick = {
                    try {
                        MBA.addBreadcrumb("Tapped: Non-Fatal Error")
                        kotlinx.serialization.json.Json.parseToJsonElement("{invalid}")
                    } catch (e: Exception) {
                        MBA.logError(e, mapOf("trigger" to "json_parse"))
                        statusMessage = "\uD83D\uDCDD Non-fatal logged: ${e.message}"
                    }
                },
            )

            // Button 5: Background Thread Crash
            CrashButton(
                emoji = "\uD83E\uDDF5",
                label = "Background Thread Error",
                description = "Caught on worker thread & logged",
                containerColor = Color(0xFF37474F),
                onClick = {
                    MBA.addBreadcrumb("Tapped: Background Thread Error")
                    Thread({
                        try {
                            Thread.sleep(300)
                            throw StackOverflowError("Simulated stack overflow")
                        } catch (e: Throwable) {
                            MBA.logError(RuntimeException(e), mapOf("trigger" to "bg_thread"))
                        }
                    }, "MBA-TestWorker").start()
                    statusMessage = "\uD83E\uDDF5 Background error fired (caught & logged)"
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Setup instructions ───────────────────────────────────── //
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "\u2699\uFE0F Setup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add to local.properties (not committed to git):\n\n" +
                            "notion.api.key=secret_...\n" +
                            "notion.db.id=your-database-id\n\n" +
                            "Then rebuild the app. The \"Send to Notion\" button will " +
                            "create a test page in your database.",
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

/**
 * Builds a fake ProcessedCrashReport and sends it to Notion.
 * Everything is wrapped in try/catch — returns a status string.
 */
private suspend fun sendTestCrashToNotion(): String {
    val apiKey = BuildConfig.NOTION_API_KEY
    val dbId = BuildConfig.NOTION_DB_ID

    if (apiKey.isBlank() || dbId.isBlank()) {
        return "\u274c Missing Notion credentials!\n\n" +
            "Add to local.properties:\n" +
            "notion.api.key=secret_...\n" +
            "notion.db.id=your-db-id"
    }

    return try {
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
            stackTrace = """
                java.lang.NullPointerException: Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference
                    at dev.sunnat629.mba.sample.CheckoutViewModel.processPayment(CheckoutViewModel.kt:87)
                    at dev.sunnat629.mba.sample.CheckoutViewModel.onConfirmClick(CheckoutViewModel.kt:45)
                    at dev.sunnat629.mba.sample.CheckoutFragment.onClick(CheckoutFragment.kt:112)
                    at android.view.View.performClick(View.java:7448)
                    at android.view.View.onKeyUp(View.java:14590)
            """.trimIndent(),
            threadName = "main",
            isFatal = true,
            device = fakeDevice,
            appVersion = "1.0.0-test",
            buildType = "debug",
            currentScreen = "CheckoutScreen",
            breadcrumbs = listOf(
                "App launched",
                "Navigated to Cart",
                "Tapped Checkout",
                "Entered payment details",
                "Tapped Confirm Payment",
            ),
        )

        val fakeProcessed = ProcessedCrashReport(
            raw = fakeRaw,
            fingerprint = "test-fp-${System.currentTimeMillis()}",
            severity = Severity.HIGH,
            confidence = 0.92f,
            title = "\uD83D\uDEA8 [Test] Checkout crashes on payment confirmation",
            description = "NullPointerException in CheckoutViewModel.processPayment(). " +
                "The payment response object is null when the user confirms the order. " +
                "This is a test crash report sent from the MBA Sample App.",
            stepsToReproduce = "1. Open the app\n2. Add items to cart\n3. Go to checkout\n4. Tap 'Confirm Payment'",
            possibleCause = "Payment API response is null — missing null check after network call",
            crashFile = "CheckoutViewModel.kt",
            crashLine = 87,
            crashMethod = "processPayment",
            isAppCode = true,
            sanitizedStackTrace = fakeRaw.stackTrace,
        )

        val result = backend.createTicket(fakeProcessed)
        backend.close()

        if (result.success) {
            "\u2705 Ticket created!\n\n" +
                "ID: ${result.ticketId}\n" +
                "URL: ${result.url}\n\n" +
                "Check your Notion database!"
        } else {
            "\u274c Ticket creation failed:\n${result.errorMessage}"
        }
    } catch (e: Exception) {
        MBA.logError(e, mapOf("trigger" to "notion_test_button"))
        "\u274c Error: ${e::class.simpleName}\n${e.message}"
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

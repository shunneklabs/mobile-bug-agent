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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Sample screen with 5 buttons that trigger different types of crashes and bugs.
 * Used to test the MBA SDK's crash capture pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashTestScreen() {
    // Track this screen for crash context
    LaunchedEffect(Unit) {
        MBA.setScreen("CrashTestScreen")
        MBA.addBreadcrumb("Opened crash test screen")
    }

    var statusMessage by remember { mutableStateOf<String?>(null) }

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
            Text(
                text = "Tap a button to trigger a crash or bug.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Button 1: Fatal NullPointerException ────────────────── //
            CrashButton(
                emoji = "\uD83D\uDCA5",
                label = "NullPointerException",
                description = "Fatal crash on the main thread",
                containerColor = MaterialTheme.colorScheme.error,
                onClick = {
                    MBA.addBreadcrumb("Tapped: NullPointerException")
                    val nullString: String? = null
                    // Force unwrap → NullPointerException
                    nullString!!.length
                },
            )

            // ── Button 2: Fatal IndexOutOfBoundsException ───────────── //
            CrashButton(
                emoji = "\uD83D\uDD25",
                label = "IndexOutOfBoundsException",
                description = "Array bounds crash on main thread",
                containerColor = Color(0xFFE65100), // deep orange
                onClick = {
                    MBA.addBreadcrumb("Tapped: IndexOutOfBoundsException")
                    val list = listOf(1, 2, 3)
                    // Access index 10 → IndexOutOfBoundsException
                    @Suppress("UNUSED_VARIABLE")
                    val boom = list[10]
                },
            )

            // ── Button 3: Coroutine Crash ────────────────────────────── //
            CrashButton(
                emoji = "\u26A1",
                label = "Coroutine Crash",
                description = "Unhandled exception in a CoroutineScope",
                containerColor = Color(0xFF6A1B9A), // purple
                onClick = {
                    MBA.addBreadcrumb("Tapped: Coroutine Crash")
                    // Launch with MBA.exceptionHandler so the SDK captures it
                    CoroutineScope(Dispatchers.Default + MBA.exceptionHandler).launch {
                        // Simulate async work then crash
                        kotlinx.coroutines.delay(200)
                        throw IllegalStateException(
                            "Coroutine crash: unexpected state in async pipeline"
                        )
                    }
                    statusMessage = "\u26A1 Coroutine crash fired (non-fatal, captured by MBA)"
                },
            )

            // ── Button 4: Non-Fatal Logged Error ─────────────────────── //
            CrashButton(
                emoji = "\uD83D\uDCDD",
                label = "Non-Fatal Error",
                description = "Logged via MBA.logError() — no crash",
                containerColor = Color(0xFF00838F), // teal
                onClick = {
                    MBA.addBreadcrumb("Tapped: Non-Fatal Error")
                    try {
                        // Simulate a caught exception
                        val json = "{invalid json}"
                        kotlinx.serialization.json.Json.parseToJsonElement(json)
                    } catch (e: Exception) {
                        // Log it as a non-fatal — SDK captures it, app keeps running
                        MBA.logError(
                            throwable = e,
                            metadata = mapOf(
                                "screen" to "CrashTestScreen",
                                "action" to "parse_json",
                            ),
                        )
                        statusMessage = "\uD83D\uDCDD Non-fatal error logged (check crash dir)"
                    }
                },
            )

            // ── Button 5: Background Thread Crash ────────────────────── //
            CrashButton(
                emoji = "\uD83E\uDDF5",
                label = "Background Thread Crash",
                description = "Fatal crash on a worker thread",
                containerColor = Color(0xFF37474F), // blue-grey
                onClick = {
                    MBA.addBreadcrumb("Tapped: Background Thread Crash")
                    Thread({
                        Thread.sleep(300)
                        throw StackOverflowError(
                            "Simulated stack overflow on background thread"
                        )
                    }, "MBA-TestWorker").start()
                    statusMessage = "\uD83E\uDDF5 Background crash fired (will crash in ~300ms)"
                },
            )

            // ── Status message ────────────────────────────────────────── //
            statusMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Info card ─────────────────────────────────────────────── //
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How it works",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "MBA captures crashes to disk instantly. " +
                            "On next app launch, the crash files are picked up by " +
                            "WorkManager, analyzed by AI, and a Notion ticket is created.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Reusable crash trigger button with emoji, label, and description.
 */
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

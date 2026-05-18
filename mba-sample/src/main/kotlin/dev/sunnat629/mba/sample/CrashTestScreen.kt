package dev.sunnat629.mba.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sunnat629.mba.core.MBA
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashTestScreen() {
    LaunchedEffect(Unit) {
        MBA.setScreen("SampleCrashLab")
        MBA.addBreadcrumb("Opened SampleCrashLab")
    }

    var status by remember { mutableStateOf("Ready. Fatal scenarios close the app; reopen it to let WorkManager process the saved crash.") }
    val context = LocalContext.current
    val settings by SampleRuntime.settings.collectAsState()
    val mode = settings.deliveryMode
    val integrationMode by SampleIntegrationRuntime.mode.collectAsState()
    val scenarios = remember { sampleScenarios() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MBA Sample Crash Lab") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ModeCard(mode)
            ConfigCard(settings)
            ProcessingCard(
                settings = settings,
                hasLlm = SampleRuntime.hasLlmConfig,
                onModeSelect = { requested ->
                    val applied = SampleRuntime.selectDeliveryMode(context, requested)
                    status = "Processing route: ${applied.deliveryMode.label}. Trigger a crash to test it."
                },
                onUseAgentChange = { enabled ->
                    val applied = SampleRuntime.setUseAgent(context, enabled)
                    status = "Agent: ${if (applied.useAgent) "Koog enabled" else "raw fallback"}. Trigger a crash to test it."
                },
                onHostedBackendChange = { enabled ->
                    val requested = if (enabled) SampleDeliveryMode.HOSTED else SampleDeliveryMode.SDK_ONLY
                    val applied = SampleRuntime.selectDeliveryMode(context, requested)
                    status = "Processing route: ${applied.deliveryMode.label}. Trigger a crash to test it."
                },
                onReset = {
                    val applied = SampleRuntime.resetToBuildDefaults(context)
                    status = "Processing route reset to build defaults: ${applied.deliveryMode.label}, agent=${if (applied.useAgent) "on" else "raw fallback"}."
                },
            )
            IntegrationCard(
                selected = integrationMode,
                onSelect = { requested ->
                    val applied = SampleIntegrationRuntime.select(context, requested)
                    status = "Integration route: ${applied.label}. Trigger a crash to test this app-layer setup."
                },
            )

            StatusCard(status)

            SectionHeader(
                title = "Crash Scenarios",
                body = "Use these to test grouping, Koog summaries, Notion parent/occurrence rows, and optional GitHub issue creation.",
            )

            scenarios.forEach { scenario ->
                ScenarioCard(
                    scenario = scenario,
                    onRun = {
                        MBA.setScreen(scenario.screen)
                        MBA.addBreadcrumb("Scenario selected: ${scenario.title}")
                        MBA.addBreadcrumb("Expected grouping key: ${scenario.expectedGrouping}")
                        runScenario(scenario)
                        status = if (scenario.fatal) {
                            "${scenario.statusLabel} '${scenario.title}' triggered. Reopen the app to process it."
                        } else {
                            "Non-fatal scenario '${scenario.title}' logged. It will be processed by the SDK worker."
                        }
                    },
                )
            }

            HorizontalDivider()

            SectionHeader(
                title = "What Should Happen",
                body = when (mode) {
                    SampleDeliveryMode.SDK_ONLY ->
                        "SDKOnly: app captures crash, runs Koog on-device with the app LLM key, updates local aggregation, invokes callback/JSON, then uses the selected app-layer integration route."
                    SampleDeliveryMode.HOSTED ->
                        "Hosted: app uploads raw crash to MBA Server. Backend Koog owns analysis, aggregation, Notion, and GitHub. The app does not create duplicate tickets."
                },
            )
        }
    }
}

@Composable
private fun ModeCard(mode: SampleDeliveryMode) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (mode) {
                SampleDeliveryMode.SDK_ONLY -> Color(0xFF123B2A)
                SampleDeliveryMode.HOSTED -> Color(0xFF152B52)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mode.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                when (mode) {
                    SampleDeliveryMode.SDK_ONLY -> "Standalone agent mode. The app owns LLM keys and optional Notion/GitHub keys."
                    SampleDeliveryMode.HOSTED -> "Backend mode. The app sends raw crashes to /report and the server owns agent work."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ConfigCard(settings: SampleProcessingSettings) {
    val hasLlm = SampleRuntime.hasLlmConfig
    val hasNotion = BuildConfig.NOTION_API_KEY.isNotBlank() && BuildConfig.NOTION_TICKET_DB_ID.isNotBlank()
    val hasBackend = BuildConfig.MBA_BACKEND_ENDPOINT.isNotBlank()
    val hasGitHub = BuildConfig.GITHUB_TOKEN.isNotBlank() && BuildConfig.GITHUB_OWNER.isNotBlank() && BuildConfig.GITHUB_REPO.isNotBlank()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigChip("Mode", settings.deliveryMode.label)
                ConfigChip("Agent", if (settings.useAgent) "on" else "raw fallback")
                ConfigChip("Mode default", BuildConfig.MBA_SAMPLE_MODE)
                ConfigChip("Agent default", BuildConfig.MBA_SAMPLE_USE_AGENT)
                ConfigChip("Gemini", if (hasLlm) "ready" else "missing")
                ConfigChip("Notion", if (hasNotion) "ready" else "missing")
                ConfigChip("Backend", if (hasBackend && settings.deliveryMode == SampleDeliveryMode.HOSTED) BuildConfig.MBA_BACKEND_ENDPOINT else "off")
                ConfigChip("GitHub", if (hasGitHub) "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}" else "off")
            }
        }
    }
}

@Composable
private fun ConfigChip(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            "$label: $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ProcessingCard(
    settings: SampleProcessingSettings,
    hasLlm: Boolean,
    onModeSelect: (SampleDeliveryMode) -> Unit,
    onUseAgentChange: (Boolean) -> Unit,
    onHostedBackendChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Processing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Active: ${settings.deliveryMode.label}, agent=${if (settings.useAgent) "Koog" else "raw fallback"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onReset) {
                    Text("Use defaults")
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProcessingButton(
                    label = "SDKOnly",
                    selected = settings.deliveryMode == SampleDeliveryMode.SDK_ONLY,
                    onClick = { onModeSelect(SampleDeliveryMode.SDK_ONLY) },
                )
                ProcessingButton(
                    label = "Hosted backend",
                    selected = settings.deliveryMode == SampleDeliveryMode.HOSTED,
                    onClick = { onModeSelect(SampleDeliveryMode.HOSTED) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Use hosted backend", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.deliveryMode == SampleDeliveryMode.HOSTED) {
                            "Send raw crashes to MBA Server"
                        } else {
                            "Process locally in SDKOnly mode"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.deliveryMode == SampleDeliveryMode.HOSTED,
                    onCheckedChange = onHostedBackendChange,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Use Koog agent", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            settings.useAgent -> "LLM analysis enabled"
                            hasLlm -> "Raw callback/ticket fallback"
                            else -> "Raw fallback; Gemini key missing"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.useAgent,
                    onCheckedChange = onUseAgentChange,
                    enabled = hasLlm,
                )
            }
        }
    }
}

@Composable
private fun ProcessingButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun IntegrationCard(
    selected: SampleIntegrationMode,
    onSelect: (SampleIntegrationMode) -> Unit,
) {
    val hasNotion = SampleIntegrationRuntime.hasNotionConfig
    val hasGitHub = SampleIntegrationRuntime.hasGitHubConfig

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App-layer Integrations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Active: ${selected.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        selected.label,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IntegrationButton(
                    label = "Callback only",
                    selected = selected == SampleIntegrationMode.CALLBACK_ONLY,
                    enabled = true,
                    onClick = { onSelect(SampleIntegrationMode.CALLBACK_ONLY) },
                )
                IntegrationButton(
                    label = "Use Notion",
                    selected = selected == SampleIntegrationMode.NOTION,
                    enabled = hasNotion,
                    onClick = { onSelect(SampleIntegrationMode.NOTION) },
                )
                IntegrationButton(
                    label = "Use GitHub",
                    selected = selected == SampleIntegrationMode.GITHUB,
                    enabled = hasGitHub,
                    onClick = { onSelect(SampleIntegrationMode.GITHUB) },
                )
                IntegrationButton(
                    label = "Use both",
                    selected = selected == SampleIntegrationMode.BOTH,
                    enabled = hasNotion && hasGitHub,
                    onClick = { onSelect(SampleIntegrationMode.BOTH) },
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigChip("Notion keys", if (hasNotion) "ready" else "missing")
                ConfigChip("GitHub keys", if (hasGitHub) "ready" else "missing")
            }
        }
    }
}

@Composable
private fun IntegrationButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(status, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionHeader(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScenarioCard(scenario: CrashScenario, onRun: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(scenario.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(scenario.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (scenario.fatal) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        scenario.badgeLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                "Expected grouping: ${scenario.expectedGrouping}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (scenario.fatal) {
                Button(
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(scenario.actionLabel)
                }
            } else {
                OutlinedButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                    Text(scenario.actionLabel)
                }
            }
        }
    }
}

private data class CrashScenario(
    val title: String,
    val description: String,
    val screen: String,
    val fatal: Boolean,
    val expectedGrouping: String,
    val actionLabel: String = if (fatal) "Trigger fatal crash" else "Log non-fatal error",
    val badgeLabel: String = if (fatal) "fatal" else "non-fatal",
    val statusLabel: String = if (fatal) "Fatal scenario" else "Non-fatal scenario",
    val run: () -> Unit,
)

private fun sampleScenarios(): List<CrashScenario> = listOf(
    CrashScenario(
        title = "Checkout null payment token",
        description = "Deterministic NPE. Trigger this twice to verify one parent bug plus occurrence count update.",
        screen = "CheckoutScreen",
        fatal = true,
        expectedGrouping = "same fingerprint on repeated runs",
        run = { StageNpeCrasher.trigger() },
    ),
    CrashScenario(
        title = "Catalog index out of bounds",
        description = "Different stack and exception type. Should create a separate parent bug.",
        screen = "CatalogScreen",
        fatal = true,
        expectedGrouping = "separate fingerprint",
        run = { listOf("one", "two")[5] },
    ),
    CrashScenario(
        title = "Main thread ANR",
        description = "Blocks the UI thread long enough for Android to report an ANR. On Android 11+, reopen the app to let MBA convert the previous ANR exit into a crash report.",
        screen = "ANRScreen",
        fatal = true,
        expectedGrouping = "ANR exit reason fingerprint after restart",
        actionLabel = "Trigger ANR",
        badgeLabel = "ANR",
        statusLabel = "ANR scenario",
        run = {
            Thread.sleep(20_000)
        },
    ),
    CrashScenario(
        title = "Session state violation",
        description = "Caught non-fatal error with metadata and breadcrumbs. Useful for SDKOnly callback testing.",
        screen = "SessionScreen",
        fatal = false,
        expectedGrouping = "stable non-fatal fingerprint",
        run = {
            MBA.logError(
                IllegalStateException("User session reached checkout without an active cart"),
                metadata = mapOf(
                    "sample.scenario" to "session-state",
                    "sample.user_tier" to "demo",
                ),
            )
        },
    ),
    CrashScenario(
        title = "Malformed remote config",
        description = "Caught JSON parsing failure. Koog should infer possible cause from malformed payload parsing.",
        screen = "RemoteConfigScreen",
        fatal = false,
        expectedGrouping = "JSON parser fingerprint",
        run = {
            try {
                Json.parseToJsonElement("{bad json}")
            } catch (error: SerializationException) {
                MBA.logError(
                    error,
                    metadata = mapOf(
                        "sample.scenario" to "remote-config-json",
                        "sample.config_key" to "paywall",
                    ),
                )
            }
        },
    ),
)

private fun runScenario(scenario: CrashScenario) {
    scenario.run()
}

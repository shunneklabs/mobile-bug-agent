package dev.sunnat629.mba.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sunnat629.mba.core.MBA
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CrashTestScreen() {
    LaunchedEffect(Unit) {
        MBA.setScreen("KotlinConfCrashLab")
        MBA.addBreadcrumb("Opened KotlinConfCrashLab")
    }

    var status by remember {
        mutableStateOf("Ready. Trigger a scenario, reopen the app after a fatal crash, and watch SDKOnly process the saved report.")
    }
    val context = LocalContext.current
    val settings by SampleRuntime.settings.collectAsState()
    val integrationMode by SampleIntegrationRuntime.mode.collectAsState()
    val scenarios = remember { sampleScenarios() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KotlinConf Crash Lab") },
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KotlinConfHero(settings = settings, integrationMode = integrationMode)

            AgentControls(
                settings = settings,
                hasLlm = SampleRuntime.hasLlmConfig,
                onUseAgentChange = { enabled ->
                    val applied = SampleRuntime.setUseAgent(context, enabled)
                    status = if (applied.useAgent) {
                        "Koog agent is on. Next crash will include title, cause, severity, and reproduction hints."
                    } else {
                        "Raw fallback is on. Next crash will emit technical JSON without LLM analysis."
                    }
                },
                onReset = {
                    val applied = SampleRuntime.resetToBuildDefaults(context)
                    status = "Reset for KotlinConf: SDKOnly, backend off, agent=${if (applied.useAgent) "on" else "raw fallback"}."
                },
            )

            IntegrationPanel(
                selected = integrationMode,
                onSelect = { requested ->
                    val applied = SampleIntegrationRuntime.select(context, requested)
                    status = "App-layer delivery changed to ${applied.label}. SDKOnly will still emit callback JSON first."
                },
            )

            StatusBanner(status)

            SectionHeader(
                title = "Crash Scenarios",
                body = "Each scenario is deterministic, so repeated runs should update the same local bug group instead of creating duplicate parent tickets.",
            )

            scenarios.forEachIndexed { index, scenario ->
                ScenarioCard(
                    index = index + 1,
                    scenario = scenario,
                    onRun = {
                        MBA.setScreen(scenario.screen)
                        MBA.addBreadcrumb("Scenario selected: ${scenario.title}")
                        MBA.addBreadcrumb("Expected grouping key: ${scenario.expectedGrouping}")
                        runScenario(scenario)
                        status = if (scenario.fatal) {
                            "${scenario.statusLabel} triggered. Reopen the app to process '${scenario.title}'."
                        } else {
                            "Logged '${scenario.title}'. The worker will process it through SDKOnly callbacks."
                        }
                    },
                )
            }

            HorizontalDivider()

            SectionHeader(
                title = "Event Flow",
                body = "SDKOnly captures the crash, stores it privately, processes it after restart, groups duplicates locally, emits latest and batch callbacks, then optional app-layer Notion/GitHub sinks run if selected.",
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun KotlinConfHero(
    settings: SampleProcessingSettings,
    integrationMode: SampleIntegrationMode,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF0F3329),
        contentColor = Color.White,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "KotlinConf SDKOnly Agent",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "Crash capture, Koog analysis, duplicate grouping, and app-owned delivery in one Android demo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD7EFE5),
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF47D18C)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("K", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill("Mode", settings.deliveryMode.label)
                HeroPill("Backend", "off")
                HeroPill("Agent", if (settings.useAgent) "Koog" else "raw")
                HeroPill("Delivery", integrationMode.label)
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.White.copy(alpha = 0.14f),
        contentColor = Color.White,
    ) {
        Text(
            "$label: $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentControls(
    settings: SampleProcessingSettings,
    hasLlm: Boolean,
    onUseAgentChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KotlinConf Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "SaaS is disabled for this event build. The sample always runs SDKOnly with backend upload off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigChip("SDKOnly", "locked")
                ConfigChip("Backend", "off")
                ConfigChip("Gemini", if (hasLlm) "ready" else "missing")
                ConfigChip("Build", BuildConfig.VERSION_NAME)
            }

            val agentColor by animateColorAsState(
                targetValue = if (settings.useAgent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                label = "agent-control-color",
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = agentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Use Koog agent", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Crossfade(
                            targetState = Triple(settings.useAgent, hasLlm, settings.deliveryMode),
                            label = "agent-caption",
                        ) { (enabled, llmReady, _) ->
                            Text(
                                when {
                                    enabled -> "LLM analysis adds title, severity, steps, and possible cause."
                                    llmReady -> "Raw fallback selected; callbacks still include crash and device metadata."
                                    else -> "Add GEMINI_API_KEY to enable agent analysis."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntegrationPanel(
    selected: SampleIntegrationMode,
    onSelect: (SampleIntegrationMode) -> Unit,
) {
    val hasNotion = SampleIntegrationRuntime.hasNotionConfig
    val hasGitHub = SampleIntegrationRuntime.hasGitHubConfig

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("App-layer Delivery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "SDKOnly emits JSON first. The app decides whether to ignore it, store it, or forward it to Notion and GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeliveryChip(
                    label = "Callback only",
                    selected = selected == SampleIntegrationMode.CALLBACK_ONLY,
                    enabled = true,
                    onClick = { onSelect(SampleIntegrationMode.CALLBACK_ONLY) },
                )
                DeliveryChip(
                    label = "Notion",
                    selected = selected == SampleIntegrationMode.NOTION,
                    enabled = hasNotion,
                    onClick = { onSelect(SampleIntegrationMode.NOTION) },
                )
                DeliveryChip(
                    label = "GitHub",
                    selected = selected == SampleIntegrationMode.GITHUB,
                    enabled = hasGitHub,
                    onClick = { onSelect(SampleIntegrationMode.GITHUB) },
                )
                DeliveryChip(
                    label = "Both",
                    selected = selected == SampleIntegrationMode.BOTH,
                    enabled = hasNotion && hasGitHub,
                    onClick = { onSelect(SampleIntegrationMode.BOTH) },
                )
            }

            AnimatedVisibility(visible = selected != SampleIntegrationMode.CALLBACK_ONLY) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        "Selected sinks receive grouped bug updates. Callback JSON still stays available for app-owned handling.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigChip("Notion keys", if (hasNotion) "ready" else "missing")
                ConfigChip("GitHub keys", if (hasGitHub) "ready" else "missing")
            }
        }
    }
}

@Composable
private fun DeliveryChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun ConfigChip(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
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
private fun StatusBanner(status: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            status,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
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
private fun ScenarioCard(index: Int, scenario: CrashScenario, onRun: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                ScenarioIndex(index)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            scenario.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        ScenarioBadge(scenario)
                    }
                    Text(
                        scenario.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    "Grouping: ${scenario.expectedGrouping}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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

@Composable
private fun ScenarioIndex(index: Int) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(index.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScenarioBadge(scenario: CrashScenario) {
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
        description = "Deterministic NPE. Trigger twice to verify one grouped bug and a higher occurrence count.",
        screen = "CheckoutScreen",
        fatal = true,
        expectedGrouping = "same fingerprint on repeated runs",
        run = { StageNpeCrasher.trigger() },
    ),
    CrashScenario(
        title = "Catalog index out of bounds",
        description = "Different stack and exception type. This should create a separate grouped bug.",
        screen = "CatalogScreen",
        fatal = true,
        expectedGrouping = "separate fingerprint",
        run = { listOf("one", "two")[5] },
    ),
    CrashScenario(
        title = "Main thread ANR",
        description = "Blocks the UI thread long enough for Android to report an ANR. Reopen the app to convert the previous ANR exit into a report.",
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
        description = "Caught non-fatal error with metadata and breadcrumbs. Useful for callback JSON testing.",
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

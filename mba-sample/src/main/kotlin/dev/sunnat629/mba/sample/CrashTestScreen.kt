package dev.sunnat629.mba.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
        MBA.setScreen("MobileBugAgent")
        MBA.addBreadcrumb("Opened Mobile Bug Agent")
    }

    val context = LocalContext.current
    val settings by SampleRuntime.settings.collectAsState()
    val integrationMode by SampleIntegrationRuntime.mode.collectAsState()
    val scenarios = remember { sampleScenarios() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSettings by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }

    if (showSettings) {
        SettingsSheet(
            sheetState = sheetState,
            settings = settings,
            integrationMode = integrationMode,
            onDismiss = { showSettings = false },
            onUseAgentChange = { enabled ->
                val applied = SampleRuntime.setUseAgent(context, enabled)
                status = if (applied.useAgent) "MBA analysis on" else "Raw mode on"
            },
            onDeliverySelect = { requested ->
                val applied = SampleIntegrationRuntime.select(context, requested)
                status = "Delivery: ${applied.label}"
            },
            onReset = {
                val applied = SampleRuntime.resetToBuildDefaults(context)
                status = if (applied.useAgent) "Defaults restored: MBA on" else "Defaults restored: raw"
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mobile Bug Agent") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        SettingsGlyph()
                    }
                },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeroPanel(
                settings = settings,
                integrationMode = integrationMode,
                status = status,
            )

            scenarios.forEach { scenario ->
                ScenarioRow(
                    scenario = scenario,
                    onRun = {
                        MBA.setScreen(scenario.screen)
                        MBA.addBreadcrumb("Scenario selected: ${scenario.title}")
                        MBA.addBreadcrumb("Expected grouping key: ${scenario.expectedGrouping}")
                        runScenario(scenario)
                        status = if (scenario.fatal) {
                            "Crash saved. Reopen app."
                        } else {
                            "Error logged"
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroPanel(
    settings: SampleProcessingSettings,
    integrationMode: SampleIntegrationMode,
    status: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF161616),
                            Color(0xFF20342E),
                            Color(0xFF171717),
                        )
                    )
                ),
        ) {
            HeroPattern(Modifier.matchParentSize())
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            "Crash to ticket",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            "Trigger a test issue. MBA groups it and sends it to your tools.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE8EFEA),
                        )
                    }
                    BrandMark()
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (settings.useAgent) "MBA analysis" else "Raw mode")
                    StatusPill(integrationMode.label)
                    StatusPill("SDKOnly")
                }

                AnimatedContent(
                    targetState = status,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "status",
                ) { text ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ) {
                        Text(
                            text,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPattern(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val gold = Color(0xFFD4AF37).copy(alpha = 0.20f)
        val green = Color(0xFF47D18C).copy(alpha = 0.12f)
        drawCircle(green, radius = size.width * 0.42f, center = Offset(size.width * 0.95f, size.height * 0.10f))
        drawArc(
            color = gold,
            startAngle = 205f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(size.width * 0.66f, size.height * 0.08f),
            size = Size(size.width * 0.48f, size.width * 0.48f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.10f),
            start = Offset(size.width * 0.08f, size.height * 0.92f),
            end = Offset(size.width * 0.72f, size.height * 0.92f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .padding(start = 12.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFFD4AF37)),
        contentAlignment = Alignment.Center,
    ) {
        Text("MBA", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color(0xFF171717))
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.White.copy(alpha = 0.12f),
        contentColor = Color.White,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ScenarioRow(scenario: CrashScenario, onRun: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeverityDot(scenario)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(scenario.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        scenario.shortDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = onRun,
                modifier = Modifier
                    .height(44.dp)
                    .widthIn(min = 92.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD4AF37),
                    contentColor = Color(0xFF171717),
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(scenario.actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SeverityDot(scenario: CrashScenario) {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = if (scenario.fatal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        contentColor = if (scenario.fatal) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(scenario.marker, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(
    sheetState: androidx.compose.material3.SheetState,
    settings: SampleProcessingSettings,
    integrationMode: SampleIntegrationMode,
    onDismiss: () -> Unit,
    onUseAgentChange: (Boolean) -> Unit,
    onDeliverySelect: (SampleIntegrationMode) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("MBA analysis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (SampleRuntime.hasLlmConfig) "Adds title, cause, severity, steps." else "Gemini key missing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.useAgent,
                        onCheckedChange = onUseAgentChange,
                        enabled = SampleRuntime.hasLlmConfig,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delivery", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeliveryChip("Callback", integrationMode == SampleIntegrationMode.CALLBACK_ONLY, true) {
                        onDeliverySelect(SampleIntegrationMode.CALLBACK_ONLY)
                    }
                    DeliveryChip("Notion", integrationMode == SampleIntegrationMode.NOTION, SampleIntegrationRuntime.hasNotionConfig) {
                        onDeliverySelect(SampleIntegrationMode.NOTION)
                    }
                    DeliveryChip("GitHub", integrationMode == SampleIntegrationMode.GITHUB, SampleIntegrationRuntime.hasGitHubConfig) {
                        onDeliverySelect(SampleIntegrationMode.GITHUB)
                    }
                    DeliveryChip("Both", integrationMode == SampleIntegrationMode.BOTH, SampleIntegrationRuntime.hasNotionConfig && SampleIntegrationRuntime.hasGitHubConfig) {
                        onDeliverySelect(SampleIntegrationMode.BOTH)
                    }
                }
            }

            TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                Text("Reset")
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
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun SettingsGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        val rows = listOf(7.dp.toPx(), 12.dp.toPx(), 17.dp.toPx())
        rows.forEachIndexed { index, y ->
            drawLine(color, Offset(4.dp.toPx(), y), Offset(20.dp.toPx(), y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            val knobX = when (index) {
                0 -> 9.dp.toPx()
                1 -> 15.dp.toPx()
                else -> 11.dp.toPx()
            }
            drawCircle(color, radius = 2.7.dp.toPx(), center = Offset(knobX, y))
        }
    }
}

private data class CrashScenario(
    val title: String,
    val shortDescription: String,
    val screen: String,
    val fatal: Boolean,
    val expectedGrouping: String,
    val marker: String,
    val actionLabel: String = if (fatal) "Crash" else "Log",
    val run: () -> Unit,
)

private fun sampleScenarios(): List<CrashScenario> = listOf(
    CrashScenario(
        title = "Checkout token",
        shortDescription = "Null payment token",
        screen = "CheckoutScreen",
        fatal = true,
        expectedGrouping = "same fingerprint on repeated runs",
        marker = "NPE",
        run = { StageNpeCrasher.trigger() },
    ),
    CrashScenario(
        title = "Catalog index",
        shortDescription = "Out-of-bounds item access",
        screen = "CatalogScreen",
        fatal = true,
        expectedGrouping = "separate fingerprint",
        marker = "IDX",
        run = { listOf("one", "two")[5] },
    ),
    CrashScenario(
        title = "Main thread ANR",
        shortDescription = "Blocked UI thread",
        screen = "ANRScreen",
        fatal = true,
        expectedGrouping = "ANR exit reason fingerprint after restart",
        marker = "ANR",
        actionLabel = "ANR",
        run = {
            Thread.sleep(20_000)
        },
    ),
    CrashScenario(
        title = "Session state",
        shortDescription = "Caught state violation",
        screen = "SessionScreen",
        fatal = false,
        expectedGrouping = "stable non-fatal fingerprint",
        marker = "ERR",
        run = {
            MBA.logError(
                IllegalStateException("User session reached checkout without an active cart"),
                metadata = mapOf(
                    "sample.scenario" to "session-state",
                    "sample.user_tier" to "demo",
                    "sample.intentional" to "true",
                    "sample.demo_note" to "intentional non-fatal scenario; used to demo fix pipeline",
                ),
            )
        },
    ),
    CrashScenario(
        title = "Remote config",
        shortDescription = "Malformed JSON payload",
        screen = "RemoteConfigScreen",
        fatal = false,
        expectedGrouping = "JSON parser fingerprint",
        marker = "JSON",
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

package io.hammerhead.pacepilot

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hammerhead.pacepilot.ai.CueBankGenerator
import io.hammerhead.pacepilot.ai.LlmProvider
import io.hammerhead.pacepilot.analytics.AnalyticsManager
import io.hammerhead.pacepilot.history.CueBankRepository
import io.hammerhead.pacepilot.history.PostRideInsight
import io.hammerhead.pacepilot.history.PostRideInsightsRepository
import io.hammerhead.pacepilot.history.RacePlan
import io.hammerhead.pacepilot.history.RacePlanRepository
import io.hammerhead.pacepilot.history.RideHistoryRepository
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.settings.SettingsRepository
import io.hammerhead.pacepilot.settings.UserSettings
import io.hammerhead.pacepilot.ui.PacePilotTheme
import kotlinx.coroutines.launch

private val BG = PacePilotTheme.BG
private val CardBg = PacePilotTheme.CardBg
private val CardBorder = PacePilotTheme.CardBorder
private val Primary = PacePilotTheme.Primary
private val PrimaryDim = PacePilotTheme.PrimaryDim
private val PrimaryMuted = PacePilotTheme.PrimaryMuted
private val PrimaryGhost = PacePilotTheme.PrimaryGhost
private val TextPrimary = PacePilotTheme.TextPrimary
private val TextSecondary = PacePilotTheme.TextSecondary
private val TextTertiary = PacePilotTheme.TextTertiary
private val Success = PacePilotTheme.Success
private val SuccessMuted = PacePilotTheme.SuccessMuted
private val FieldBorder = PacePilotTheme.FieldBorder
private val FieldBorderFocused = PacePilotTheme.FieldBorderFocused
private val CardShape = PacePilotTheme.CardShape
private val PillShape = PacePilotTheme.PillShape

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var postRideInsightsRepo: PostRideInsightsRepository
    private lateinit var historyRepo: RideHistoryRepository
    private lateinit var cueBankRepo: CueBankRepository
    private lateinit var racePlanRepo: RacePlanRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)
        postRideInsightsRepo = PostRideInsightsRepository(this)
        historyRepo = RideHistoryRepository(this)
        cueBankRepo = CueBankRepository(this)
        racePlanRepo = RacePlanRepository(this)
        AnalyticsManager.init(application, settingsRepo)
        handleDeepLink()

        setContent {
            val currentSettings = remember(intent) { settingsRepo.current }
            var racePlan by remember { mutableStateOf(RacePlan()) }
            LaunchedEffect(Unit) {
                historyRepo.load()
                cueBankRepo.load()
                racePlan = racePlanRepo.load()
            }
            PacePilotSettingsScreen(
                initial = currentSettings,
                onSave = { settingsRepo.save(it) },
                postRideInsightsRepo = postRideInsightsRepo,
                historyRepo = historyRepo,
                cueBankRepo = cueBankRepo,
                racePlan = racePlan,
                racePlanRepo = racePlanRepo,
                onRacePlanChange = { racePlan = it },
            )
        }
    }

    // Called when activity is already running (singleTop) and a new deep link arrives
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink()
    }

    private fun handleDeepLink() {
        val uri = intent?.data ?: return
        if (uri.scheme != "pacepilot" || uri.host != "config") return

        val geminiKey = uri.getQueryParameter("gemini_key")
        val mercuryKey = uri.getQueryParameter("mercury_key")
        val providerParam = uri.getQueryParameter("provider")

        val current = settingsRepo.current
        var updated = current

        if (!geminiKey.isNullOrBlank()) {
            updated = updated.copy(geminiApiKey = geminiKey)
            if (providerParam == "gemini" || providerParam == null) {
                updated = updated.copy(llmProvider = LlmProvider.GEMINI)
            }
            Toast.makeText(this, "Gemini API key saved!", Toast.LENGTH_SHORT).show()
        }
        if (!mercuryKey.isNullOrBlank()) {
            updated = updated.copy(mercuryApiKey = mercuryKey)
            if (providerParam == "mercury") {
                updated = updated.copy(llmProvider = LlmProvider.MERCURY)
            }
            Toast.makeText(this, "Mercury-2 API key saved!", Toast.LENGTH_SHORT).show()
        }

        if (updated != current) settingsRepo.save(updated)
    }
}

@Composable
fun PacePilotSettingsScreen(
    initial: UserSettings,
    onSave: (UserSettings) -> Unit,
    postRideInsightsRepo: PostRideInsightsRepository,
    historyRepo: RideHistoryRepository,
    cueBankRepo: CueBankRepository,
    racePlan: RacePlan,
    racePlanRepo: RacePlanRepository,
    onRacePlanChange: (RacePlan) -> Unit,
) {
    var settings by remember { mutableStateOf(initial) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestInsight by produceState<PostRideInsight?>(initialValue = null) {
        value = postRideInsightsRepo.load()
    }
    var cueBankStatus by remember { mutableStateOf<String?>(null) }
    var preparingCueBank by remember { mutableStateOf(false) }
    var localRacePlan by remember(racePlan) { mutableStateOf(racePlan) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ─── Header ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "PACEPILOT",
                    color = Primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.5.sp,
                )
                Text(
                    text = "Intelligent cycling coach",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    letterSpacing = 0.3.sp,
                )
            }
            // Status pill — green dot when active
            Box(
                modifier = Modifier
                    .clip(PillShape)
                    .background(if (settings.appEnabled) SuccessMuted else FieldBorder.copy(alpha = 0.4f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (settings.appEnabled) Success else TextTertiary),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (settings.appEnabled) "ACTIVE" else "OFF",
                        color = if (settings.appEnabled) Success else TextTertiary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
        }

        if (latestInsight != null) {
            SettingsCard(title = "POST-RIDE INTELLIGENCE") {
                Text(
                    latestInsight!!.summary,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                latestInsight!!.patterns.take(2).forEach { pattern ->
                    Text("• $pattern", color = TextSecondary, fontSize = 11.sp)
                }
                if (latestInsight!!.timeline.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Timeline", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    latestInsight!!.timeline.take(4).forEach { line ->
                        Text("· $line", color = TextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
            }
        }

        // --- Master App Toggle ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .border(
                    width = 1.dp,
                    color = if (settings.appEnabled) Primary.copy(alpha = 0.45f) else CardBorder,
                    shape = CardShape,
                )
                .background(if (settings.appEnabled) PrimaryGhost else CardBg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "PacePilot",
                        color = if (settings.appEnabled) Primary else TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp,
                    )
                    Text(
                        if (settings.appEnabled) "Coaching enabled on next ride"
                        else "Dormant — no coaching",
                        color = TextTertiary,
                        fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = settings.appEnabled,
                    onCheckedChange = { settings = settings.copy(appEnabled = it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = PrimaryMuted,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = FieldBorder,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }

        // All settings below are visually dimmed when app is disabled
        val sectionAlpha = if (settings.appEnabled) 1f else 0.4f

        // --- Zones Card ---
        SettingsCard(title = "POWER & HR", alpha = sectionAlpha) {
            InputField(
                label = "FTP (watts)",
                value = if (settings.ftpOverride > 0) settings.ftpOverride.toString() else "",
                placeholder = "Karoo profile default",
                keyboardType = KeyboardType.Number,
            ) { v -> settings = settings.copy(ftpOverride = v.toIntOrNull() ?: 0) }

            Spacer(modifier = Modifier.height(10.dp))

            InputField(
                label = "Max HR (bpm)",
                value = if (settings.maxHrOverride > 0) settings.maxHrOverride.toString() else "",
                placeholder = "Karoo profile default",
                keyboardType = KeyboardType.Number,
            ) { v -> settings = settings.copy(maxHrOverride = v.toIntOrNull() ?: 0) }
        }

        // --- Alerts Card ---
        SettingsCard(title = "COACHING ALERTS", alpha = sectionAlpha) {
            ToggleRow("Enable coaching alerts", settings.alertsEnabled) {
                settings = settings.copy(alertsEnabled = it)
            }
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow("Fueling reminders", settings.fuelingAlertsEnabled) {
                settings = settings.copy(fuelingAlertsEnabled = it)
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Alert frequency", color = TextPrimary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = settings.cooldownMultiplier,
                onValueChange = { settings = settings.copy(cooldownMultiplier = it) },
                valueRange = 0.5f..3f,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = FieldBorder,
                ),
            )
            Text(
                text = when {
                    settings.cooldownMultiplier < 0.8f -> "Aggressive — more cues"
                    settings.cooldownMultiplier > 1.8f -> "Quiet — fewer cues"
                    else -> "Standard"
                },
                color = TextTertiary,
                fontSize = 11.sp,
            )

            Spacer(modifier = Modifier.height(10.dp))
            InputField(
                label = "Min alert gap (sec)",
                value = settings.minAlertGapSec.toString(),
                placeholder = "45",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(minAlertGapSec = (v.toIntOrNull() ?: 45).coerceIn(10, 300))
            }

            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Max alerts / hour",
                value = settings.maxAlertsPerHour.toString(),
                placeholder = "15",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(maxAlertsPerHour = (v.toIntOrNull() ?: 15).coerceIn(4, 40))
            }
        }

        // --- Fueling Card ---
        SettingsCard(title = "FUELING", alpha = sectionAlpha) {
            InputField(
                label = "Carb target (g/h)",
                value = settings.carbTargetGramsPerHour.toString(),
                placeholder = "60",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(carbTargetGramsPerHour = (v.toIntOrNull() ?: 60).coerceIn(30, 120))
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Carbs per serving (g)",
                value = settings.carbsPerFuelServing.toString(),
                placeholder = "25",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(carbsPerFuelServing = (v.toIntOrNull() ?: 25).coerceIn(8, 60))
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Fueling alert threshold (g)",
                value = settings.fuelingAlertThresholdGrams.toString(),
                placeholder = "10",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(fuelingAlertThresholdGrams = (v.toIntOrNull() ?: 10).coerceIn(5, 40))
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Drink reminder (min)",
                value = settings.drinkReminderMinutes.toString(),
                placeholder = "20",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(drinkReminderMinutes = (v.toIntOrNull() ?: 20).coerceIn(10, 45))
            }
        }

        // --- Advanced Card ---
        SettingsCard(title = "ADVANCED", alpha = sectionAlpha) {
            InputField(
                label = "Climb route min gain (m)",
                value = settings.climbRouteMinGainM.toInt().toString(),
                placeholder = "1000",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(climbRouteMinGainM = (v.toIntOrNull() ?: 1000).toFloat().coerceIn(200f, 5000f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Climb gradient threshold (%)",
                value = settings.climbRouteGradientThresholdPct.toString(),
                placeholder = "4",
                keyboardType = KeyboardType.Decimal,
            ) { v ->
                settings = settings.copy(climbRouteGradientThresholdPct = (v.toFloatOrNull() ?: 4f).coerceIn(2f, 8f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Min effort cadence (rpm)",
                value = settings.minEffortCadenceRpm.toString(),
                placeholder = "75",
                keyboardType = KeyboardType.Number,
            ) { v ->
                settings = settings.copy(minEffortCadenceRpm = (v.toIntOrNull() ?: 75).coerceIn(50, 100))
            }
        }

        // --- AI Card ---
        SettingsCard(title = "AI COACHING", alpha = sectionAlpha) {
            Text("Provider", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            ProviderChips(
                selected = settings.llmProvider,
                enabled = settings.appEnabled,
                onSelect = { settings = settings.copy(llmProvider = it) },
            )

            when (settings.llmProvider) {
                LlmProvider.GEMINI -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    ApiKeyField(
                        label = "Gemini API Key",
                        placeholder = "AIza...",
                        value = settings.geminiApiKey,
                        enabled = settings.appEnabled,
                        onChange = { settings = settings.copy(geminiApiKey = it) },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Free at aistudio.google.com", color = TextTertiary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    TestConnectionButton(
                        provider = LlmProvider.GEMINI,
                        apiKey = settings.geminiApiKey,
                        enabled = settings.appEnabled && settings.geminiApiKey.isNotBlank(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoBanner(
                        text = "Personalised cues from live data + 30-ride history. Falls back to rule-based if offline.",
                        color = Success,
                        bgColor = SuccessMuted,
                    )
                }
                LlmProvider.MERCURY -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    ApiKeyField(
                        label = "Mercury-2 API Key",
                        placeholder = "incep_...",
                        value = settings.mercuryApiKey,
                        enabled = settings.appEnabled,
                        onChange = { settings = settings.copy(mercuryApiKey = it) },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("10M free tokens at platform.inceptionlabs.ai", color = TextTertiary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    TestConnectionButton(
                        provider = LlmProvider.MERCURY,
                        apiKey = settings.mercuryApiKey,
                        enabled = settings.appEnabled && settings.mercuryApiKey.isNotBlank(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoBanner(
                        text = "Experimental — 1,000+ tokens/sec diffusion model. Sub-200ms responses. Sends full context per call (no server cache).",
                        color = Primary,
                        bgColor = PrimaryMuted,
                    )
                }
                LlmProvider.DISABLED -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoBanner(
                        text = "Rules-only mode. Zero-latency coaching without internet. All alerts are deterministic.",
                        color = TextSecondary,
                        bgColor = FieldBorder.copy(alpha = 0.3f),
                    )
                }
            }
            if (settings.llmProvider != LlmProvider.DISABLED) {
                Spacer(modifier = Modifier.height(10.dp))
                PrepareOfflineCoachButton(
                    enabled = settings.appEnabled && !preparingCueBank,
                    preparing = preparingCueBank,
                    status = cueBankStatus,
                    onPrepare = {
                        preparingCueBank = true
                        cueBankStatus = "Generating…"
                        scope.launch {
                            runCatching {
                                val bank = CueBankGenerator(settings, historyRepo.current).generate()
                                cueBankRepo.save(bank)
                                cueBankStatus = "✓ ${bank.cueCount} cues ready (offline)"
                            }.onFailure {
                                cueBankStatus = "✗ ${it.message?.take(50) ?: "failed"}"
                            }
                            preparingCueBank = false
                        }
                    },
                )
            }
        }

        // --- Race Card ---
        SettingsCard(title = "RACE — 70.3", alpha = sectionAlpha) {
            ToggleRow("Race mode plan enabled", localRacePlan.enabled) {
                localRacePlan = localRacePlan.copy(enabled = it)
                onRacePlanChange(localRacePlan)
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Event",
                value = localRacePlan.eventName,
                placeholder = "Ironman 70.3 Kraków",
            ) { v -> localRacePlan = localRacePlan.copy(eventName = v); onRacePlanChange(localRacePlan) }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Target IF (0.80–0.85)",
                value = localRacePlan.targetIf.toString(),
                placeholder = "0.82",
                keyboardType = KeyboardType.Decimal,
            ) { v ->
                localRacePlan = localRacePlan.copy(targetIf = (v.toFloatOrNull() ?: 0.82f).coerceIn(0.65f, 0.95f))
                onRacePlanChange(localRacePlan)
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Target watts (0 = IF×FTP)",
                value = if (localRacePlan.targetWatts > 0) localRacePlan.targetWatts.toString() else "",
                placeholder = "auto",
                keyboardType = KeyboardType.Number,
            ) { v ->
                localRacePlan = localRacePlan.copy(targetWatts = v.toIntOrNull() ?: 0)
                onRacePlanChange(localRacePlan)
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Planned duration (min)",
                value = localRacePlan.durationMin.toString(),
                placeholder = "150",
                keyboardType = KeyboardType.Number,
            ) { v ->
                localRacePlan = localRacePlan.copy(durationMin = (v.toIntOrNull() ?: 150).coerceIn(60, 360))
                onRacePlanChange(localRacePlan)
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Distance (km)",
                value = localRacePlan.distanceKm.toString(),
                placeholder = "90",
                keyboardType = KeyboardType.Decimal,
            ) { v ->
                localRacePlan = localRacePlan.copy(distanceKm = (v.toFloatOrNull() ?: 90f).coerceIn(40f, 200f))
                onRacePlanChange(localRacePlan)
            }
            Spacer(modifier = Modifier.height(8.dp))
            InputField(
                label = "Race carbs (g/h)",
                value = localRacePlan.carbGramsPerHour.toString(),
                placeholder = "75",
                keyboardType = KeyboardType.Number,
            ) { v ->
                localRacePlan = localRacePlan.copy(carbGramsPerHour = (v.toIntOrNull() ?: 75).coerceIn(40, 120))
                onRacePlanChange(localRacePlan)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Set Ride Mode → Race before race day. Coaching status: 1=rules, 2=cue bank, 3=AI.",
                color = TextTertiary,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
        }

        // --- Mode Card ---
        SettingsCard(title = "RIDE MODE", alpha = sectionAlpha) {
            Text(
                "Detection mode",
                color = TextPrimary,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            ModeChips(
                selected = settings.forcedMode,
                onSelect = { settings = settings.copy(forcedMode = it) },
            )
        }

        // --- Language Card ---
        SettingsCard(title = "COACHING LANGUAGE", alpha = sectionAlpha) {
            Text("AI message language", color = TextPrimary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            LanguageChips(
                selected = settings.coachingLanguage,
                onSelect = { settings = settings.copy(coachingLanguage = it) },
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (settings.coachingLanguage == "en") "Messages in English (default)"
                else "AI will translate cues — messages stay ≤12 words",
                color = TextTertiary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }

        // --- Analytics Card ---
        SettingsCard(title = "ANALYTICS", alpha = sectionAlpha) {
            ToggleRow("Help improve PacePilot", settings.analyticsEnabled) {
                settings = settings.copy(analyticsEnabled = it)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Anonymous usage data (ride duration, AI success rate, alert counts). No GPS, no personal data.",
                color = TextTertiary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Save button — premium with subtle gradient feel via dim border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(colors = listOf(Primary, PrimaryDim)),
                )
                .clickable {
                    try {
                        onSave(settings)
                        scope.launch { racePlanRepo.save(localRacePlan) }
                        AnalyticsManager.updateOptOut()
                        val status = if (settings.appEnabled) "ON" else "OFF"
                        Toast.makeText(context, "Saved — PacePilot $status", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "SAVE SETTINGS",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
            )
        }

        // Version tag
        Text(
            text = "v2.0.0 · PacePilot",
            color = TextTertiary,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        )
    }
}

// ─── Reusable Components ────────────────────────────────────────────

@Composable
private fun SettingsCard(
    title: String,
    alpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(CardShape)
            .border(1.dp, CardBorder, CardShape)
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Primary),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = title,
                color = TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            )
        }
        Spacer(modifier = Modifier.height(11.dp))
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary,
                checkedTrackColor = PrimaryMuted,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = FieldBorder,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    Column {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = TextTertiary, fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Primary,
                focusedBorderColor = FieldBorderFocused,
                unfocusedBorderColor = FieldBorder,
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ApiKeyField(
    label: String = "API Key",
    placeholder: String = "key...",
    value: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val saved = value.isNotBlank()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 11.sp)
            Spacer(modifier = Modifier.weight(1f))
            if (saved) {
                Box(
                    modifier = Modifier
                        .clip(PillShape)
                        .background(SuccessMuted)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "✓ ${value.takeLast(4)}",
                        color = Success,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            placeholder = { Text(placeholder, color = TextTertiary, fontSize = 12.sp) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(
                    onClick = { visible = !visible },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (visible) "HIDE" else "SHOW",
                        color = if (enabled) TextSecondary else TextTertiary,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                disabledTextColor = TextTertiary,
                cursorColor = Primary,
                focusedBorderColor = FieldBorderFocused,
                unfocusedBorderColor = FieldBorder,
                disabledBorderColor = FieldBorder,
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderChips(
    selected: LlmProvider,
    enabled: Boolean = true,
    onSelect: (LlmProvider) -> Unit,
) {
    val providers = LlmProvider.values()

    @Composable
    fun RowScope.Chip(provider: LlmProvider) {
        val isSelected = selected == provider
        val bg = if (isSelected) PrimaryMuted else Color.Transparent
        val border = if (isSelected) Primary else FieldBorder
        val textColor = when {
            !enabled -> TextTertiary
            isSelected -> Primary
            else -> TextSecondary
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .background(bg)
                .clickable(enabled = enabled) { onSelect(provider) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (provider) {
                    LlmProvider.GEMINI -> "Gemini"
                    LlmProvider.MERCURY -> "Mercury"
                    LlmProvider.DISABLED -> "Off"
                },
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        providers.forEach { Chip(it) }
    }
}

@Composable
private fun InfoBanner(
    text: String,
    color: Color,
    bgColor: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            color = color.copy(alpha = 0.85f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ModeChips(
    selected: RideMode?,
    onSelect: (RideMode?) -> Unit,
) {
    val options = listOf(
        null to "Auto",
        RideMode.WORKOUT to "Workout",
        RideMode.ENDURANCE to "Endure",
        RideMode.CLIMB_FOCUSED to "Climb",
        RideMode.ADAPTIVE to "Adapt",
        RideMode.RACE to "Race",
        RideMode.RECOVERY to "Recover",
    )

    @Composable
    fun RowScope.Chip(mode: RideMode?, label: String) {
        val isSelected = selected == mode
        val bg = if (isSelected) PrimaryMuted else Color.Transparent
        val border = if (isSelected) Primary else FieldBorder
        val textColor = if (isSelected) Primary else TextSecondary

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .background(bg)
                .clickable { onSelect(mode) }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options.take(3).forEach { (mode, label) -> Chip(mode, label) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options.drop(3).forEach { (mode, label) -> Chip(mode, label) }
        }
    }
}

@Composable
private fun LanguageChips(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val languages = listOf(
        "en" to "EN",
        "de" to "DE",
        "fr" to "FR",
        "nl" to "NL",
        "es" to "ES",
        "it" to "IT",
        "pt" to "PT",
        "da" to "DA",
        "sv" to "SV",
        "nb" to "NO",
    )

    @Composable
    fun RowScope.LangChip(code: String, label: String) {
        val isSelected = selected == code
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, if (isSelected) Primary else FieldBorder, RoundedCornerShape(6.dp))
                .background(if (isSelected) PrimaryMuted else Color.Transparent)
                .clickable { onSelect(code) }
                .padding(vertical = 7.dp, horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (isSelected) Primary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            languages.take(5).forEach { (code, label) -> LangChip(code, label) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            languages.drop(5).forEach { (code, label) -> LangChip(code, label) }
        }
    }
}

@Composable
private fun TestConnectionButton(
    provider: LlmProvider,
    apiKey: String,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (enabled) Primary.copy(alpha = 0.5f) else FieldBorder, RoundedCornerShape(8.dp))
                .background(if (enabled) PrimaryGhost else Color.Transparent)
                .clickable(enabled = enabled && !testing) {
                    testing = true
                    status = "Testing…"
                    scope.launch {
                        val t0 = System.currentTimeMillis()
                        val result = runCatching {
                            val client: io.hammerhead.pacepilot.ai.AiCoachingClient = when (provider) {
                                LlmProvider.GEMINI -> io.hammerhead.pacepilot.ai.GeminiClient(apiKey)
                                LlmProvider.MERCURY -> io.hammerhead.pacepilot.ai.MercuryClient(apiKey)
                                LlmProvider.DISABLED -> return@launch
                            }
                            client.initRide("Coach.", "Test ride context.")
                            val gen = client.generate(
                                "Test prompt — say one word.",
                                fallback = "FALLBACK",
                            )
                            client.endRide()
                            gen
                        }
                        val elapsed = System.currentTimeMillis() - t0
                        status = result.fold(
                            onSuccess = { reply ->
                                if (reply == "FALLBACK" || reply.isBlank()) {
                                    "✗ No AI reply (${"%.1f".format(elapsed / 1000.0)}s) — using fallback (check key, Wi‑Fi, logcat: MercuryClient)"
                                } else {
                                    "✓ AI live (${"%.1f".format(elapsed / 1000.0)}s) → \"${reply.take(40)}\""
                                }
                            },
                            onFailure = { "✗ ${it.javaClass.simpleName}: ${it.message?.take(60) ?: ""}" },
                        )
                        testing = false
                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                    }
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (testing) "TESTING…" else "TEST AI CONNECTION",
                color = if (enabled) Primary else TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
        }
        status?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = it,
                color = when {
                    it.startsWith("✓") -> Success
                    it.startsWith("Testing") -> TextSecondary
                    else -> Color(0xFFFF5252)
                },
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun PrepareOfflineCoachButton(
    enabled: Boolean,
    preparing: Boolean,
    status: String?,
    onPrepare: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (enabled) Success.copy(alpha = 0.5f) else FieldBorder, RoundedCornerShape(8.dp))
                .background(if (enabled) SuccessMuted else Color.Transparent)
                .clickable(enabled = enabled && !preparing) { onPrepare() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (preparing) "PREPARING…" else "PREPARE OFFLINE COACH",
                color = if (enabled) Success else TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.0.sp,
            )
        }
        status?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, color = if (it.startsWith("✓")) Success else TextSecondary, fontSize = 11.sp)
        }
    }
}

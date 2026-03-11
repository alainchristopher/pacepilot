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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hammerhead.pacepilot.ai.LlmProvider
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.settings.SettingsRepository
import io.hammerhead.pacepilot.settings.UserSettings

private val BG = Color(0xFF0A0A0A)
private val CardBg = Color(0xFF141414)
private val CardBorder = Color(0xFF1F1F1F)
private val Primary = Color(0xFFFF6D00)
private val PrimaryMuted = Color(0x33FF6D00)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextSecondary = Color(0xFF9E9E9E)
private val TextTertiary = Color(0xFF616161)
private val Success = Color(0xFF00E676)
private val SuccessMuted = Color(0x1A00E676)
private val FieldBorder = Color(0xFF2A2A2A)
private val FieldBorderFocused = Color(0xFFFF6D00)
private val CardShape = RoundedCornerShape(12.dp)

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)
        handleDeepLink()

        setContent {
            PacePilotSettingsScreen(
                initial = settingsRepo.current,
                onSave = { settingsRepo.save(it) },
            )
        }
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
) {
    var settings by remember { mutableStateOf(initial) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Column(modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                text = "PACEPILOT",
                color = Primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Text(
                text = "Intelligent cycling coach",
                color = TextSecondary,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp,
            )
        }

        // --- Master App Toggle ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .border(
                    width = 1.dp,
                    color = if (settings.appEnabled) Primary.copy(alpha = 0.4f) else CardBorder,
                    shape = CardShape,
                )
                .background(if (settings.appEnabled) PrimaryMuted else CardBg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "PacePilot Active",
                        color = if (settings.appEnabled) Primary else TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (settings.appEnabled) "Coaching enabled on next ride" else "Extension dormant — no coaching",
                        color = TextTertiary,
                        fontSize = 11.sp,
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

        Spacer(modifier = Modifier.height(4.dp))

        // Save button
        Button(
            onClick = {
                try {
                    onSave(settings)
                    Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Text("SAVE", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        }

        // Version tag
        Text(
            text = "v1.0 · PacePilot",
            color = TextTertiary,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
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
            .padding(16.dp),
    ) {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
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
        Text(label, color = TextPrimary, fontSize = 14.sp)
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
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = TextTertiary, fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
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
    Column {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            placeholder = { Text(placeholder, color = TextTertiary, fontSize = 13.sp) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }, enabled = enabled) {
                    Text(
                        if (visible) "HIDE" else "SHOW",
                        color = if (enabled) TextSecondary else TextTertiary,
                        fontSize = 10.sp,
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

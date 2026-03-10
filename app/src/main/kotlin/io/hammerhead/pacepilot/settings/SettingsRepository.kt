package io.hammerhead.pacepilot.settings

import android.content.Context
import android.content.SharedPreferences
import io.hammerhead.pacepilot.ai.LlmProvider
import io.hammerhead.pacepilot.model.RideMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    val current: UserSettings get() = _settings.value

    fun save(settings: UserSettings) {
        prefs.edit()
            .putInt(KEY_FTP, settings.ftpOverride)
            .putInt(KEY_MAX_HR, settings.maxHrOverride)
            .putBoolean(KEY_ALERTS_ENABLED, settings.alertsEnabled)
            .putBoolean(KEY_FUELING_ALERTS, settings.fuelingAlertsEnabled)
            .putFloat(KEY_COOLDOWN_MULT, settings.cooldownMultiplier)
            .putString(KEY_FORCED_MODE, settings.forcedMode?.name)
            .putInt(KEY_FUEL_THRESHOLD_G, settings.fuelingAlertThresholdGrams)
            .putFloat(KEY_CLIMB_GRAD_PCT, settings.climbRouteGradientThresholdPct)
            .putFloat(KEY_CLIMB_GAIN_M, settings.climbRouteMinGainM)
            .putInt(KEY_MIN_CADENCE, settings.minEffortCadenceRpm)
            .putString(KEY_LLM_PROVIDER, settings.llmProvider.name)
            .putString(KEY_GEMINI_KEY, settings.geminiApiKey)
            .putInt(KEY_CARB_TARGET_GPH, settings.carbTargetGramsPerHour)
            .putInt(KEY_CARBS_PER_SERVING, settings.carbsPerFuelServing)
            .putInt(KEY_DRINK_INTERVAL_MIN, settings.drinkReminderMinutes)
            .apply()
        _settings.value = settings
    }

    private fun load(): UserSettings = UserSettings(
        ftpOverride = prefs.getInt(KEY_FTP, 0),
        maxHrOverride = prefs.getInt(KEY_MAX_HR, 0),
        alertsEnabled = prefs.getBoolean(KEY_ALERTS_ENABLED, true),
        fuelingAlertsEnabled = prefs.getBoolean(KEY_FUELING_ALERTS, true),
        cooldownMultiplier = prefs.getFloat(KEY_COOLDOWN_MULT, 1.0f),
        forcedMode = prefs.getString(KEY_FORCED_MODE, null)?.let {
            runCatching { RideMode.valueOf(it) }.getOrNull()
        },
        fuelingAlertThresholdGrams = prefs.getInt(KEY_FUEL_THRESHOLD_G, 10),
        climbRouteGradientThresholdPct = prefs.getFloat(KEY_CLIMB_GRAD_PCT, 4f),
        climbRouteMinGainM = prefs.getFloat(KEY_CLIMB_GAIN_M, 1000f),
        minEffortCadenceRpm = prefs.getInt(KEY_MIN_CADENCE, 75),
        llmProvider = prefs.getString(KEY_LLM_PROVIDER, null)?.let {
            runCatching { LlmProvider.valueOf(it) }.getOrNull()
        } ?: LlmProvider.DISABLED,
        geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "") ?: "",
        carbTargetGramsPerHour = prefs.getInt(KEY_CARB_TARGET_GPH, 60),
        carbsPerFuelServing = prefs.getInt(KEY_CARBS_PER_SERVING, 25),
        drinkReminderMinutes = prefs.getInt(KEY_DRINK_INTERVAL_MIN, 20),
    )

    companion object {
        private const val PREFS_NAME = "pacepilot_settings"
        private const val KEY_FTP = "ftp_override"
        private const val KEY_MAX_HR = "max_hr_override"
        private const val KEY_ALERTS_ENABLED = "alerts_enabled"
        private const val KEY_FUELING_ALERTS = "fueling_alerts"
        private const val KEY_COOLDOWN_MULT = "cooldown_multiplier"
        private const val KEY_FORCED_MODE = "forced_mode"
        private const val KEY_FUEL_THRESHOLD_G = "fuel_threshold_g"
        private const val KEY_CLIMB_GRAD_PCT = "climb_grad_pct"
        private const val KEY_CLIMB_GAIN_M = "climb_gain_m"
        private const val KEY_MIN_CADENCE = "min_cadence"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_CARB_TARGET_GPH = "carb_target_gph"
        private const val KEY_CARBS_PER_SERVING = "carbs_per_serving"
        private const val KEY_DRINK_INTERVAL_MIN = "drink_interval_min"
    }
}

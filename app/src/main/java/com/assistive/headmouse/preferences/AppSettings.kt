package com.assistive.headmouse.preferences

import android.content.Context
import android.content.SharedPreferences
import com.assistive.headmouse.model.CustomAiModel
import com.assistive.headmouse.model.DualOperatingMode
import com.assistive.headmouse.tracking.model.TrackingMode
import org.json.JSONArray

enum class CursorStyle(val displayName: String) {
    CLASSIC_TRIPLE_DOT("Classic Dot"),
    LIQUID_GLASS("Liquid Glass"),
    PRECISION_CROSSHAIR("Crosshair"),
    NEON_HALO("Neon Halo"),
    HOLOGRAM_DIAMOND("Diamond"),
    DUAL_ORBITAL("Dual Orbit"),
    STEALTH_GHOST("Stealth Ghost"),
    LASER_PIN("Laser Pin"),
    WATER_RIPPLE("Water Ripple"),
    AURA_GLOW("Aura Glow")
}

enum class AppTheme(val displayName: String, val primaryAccent: String, val bgTint: String) {
    CYBERPUNK_NEON("Cyberpunk Neon", "#00E5FF", "#121214"),
    DEEP_SLATE("Deep Slate", "#64748B", "#101418"),
    SOLAR_FROST("Solar Frost", "#FFB300", "#0B1020")
}

/**
 * Manages user settings, calibration offsets, sensitivity, and dwell thresholds.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isServiceActive: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, value).apply()

    var isHeadMouseActive: Boolean
        get() = prefs.getBoolean(KEY_HEAD_MOUSE_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_HEAD_MOUSE_ACTIVE, value).apply()

    var isJarvisActive: Boolean
        get() = prefs.getBoolean(KEY_JARVIS_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_JARVIS_ACTIVE, value).apply()

    var operatingMode: DualOperatingMode
        get() = DualOperatingMode.fromFlags(isHeadMouseActive, isJarvisActive)
        set(mode) {
            prefs.edit()
                .putBoolean(KEY_HEAD_MOUSE_ACTIVE, mode.isHeadMouseActive)
                .putBoolean(KEY_JARVIS_ACTIVE, mode.isJarvisActive)
                .apply()
        }

    var isHeadMouseEnabled: Boolean
        get() = isHeadMouseActive
        set(value) { isHeadMouseActive = value }

    var isJarvisServiceEnabled: Boolean
        get() = isJarvisActive
        set(value) { isJarvisActive = value }

    var trackingMode: TrackingMode
        get() {
            val name = prefs.getString(KEY_TRACKING_MODE, TrackingMode.HEAD_ONLY.name)
            return try {
                when (name) {
                    "HEAD_TRACKING" -> TrackingMode.HEAD_ONLY
                    "EYE_TRACKING" -> TrackingMode.EYE_ONLY
                    else -> TrackingMode.valueOf(name ?: TrackingMode.HEAD_ONLY.name)
                }
            } catch (e: Exception) {
                TrackingMode.HEAD_ONLY
            }
        }
        set(value) = prefs.edit().putString(KEY_TRACKING_MODE, value.name).apply()

    var cursorSpeed: Float
        get() = prefs.getFloat(KEY_CURSOR_SPEED, 1.30f)
        set(value) = prefs.edit().putFloat(KEY_CURSOR_SPEED, value).apply()

    var sensitivityX: Float
        get() = prefs.getFloat(KEY_SENS_X, 1.2f)
        set(value) = prefs.edit().putFloat(KEY_SENS_X, value).apply()

    var sensitivityY: Float
        get() = prefs.getFloat(KEY_SENS_Y, 1.2f)
        set(value) = prefs.edit().putFloat(KEY_SENS_Y, value).apply()

    var dwellTimeSeconds: Float
        get() = prefs.getFloat(KEY_DWELL_TIME, 0.8f)
        set(value) = prefs.edit().putFloat(KEY_DWELL_TIME, value).apply()

    var keyboardDwellTimeSeconds: Float
        get() = prefs.getFloat(KEY_KEYBOARD_DWELL_TIME, 0.6f)
        set(value) = prefs.edit().putFloat(KEY_KEYBOARD_DWELL_TIME, value).apply()

    var isGestureClickEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_CLICK, false) // Default false to prevent false rapid clicks
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_CLICK, value).apply()

    var dwellRadiusPx: Float
        get() = prefs.getFloat(KEY_DWELL_RADIUS, 36f)
        set(value) = prefs.edit().putFloat(KEY_DWELL_RADIUS, value).apply()

    var smoothingMinCutoff: Double
        get() = prefs.getFloat(KEY_SMOOTHING_CUTOFF, 0.05f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_SMOOTHING_CUTOFF, value.toFloat()).apply()

    var deadzoneDegrees: Float
        get() = prefs.getFloat(KEY_DEADZONE, 1.8f)
        set(value) = prefs.edit().putFloat(KEY_DEADZONE, value).apply()

    var centerPitchOffset: Float
        get() = prefs.getFloat(KEY_CENTER_PITCH, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_CENTER_PITCH, value).apply()

    var centerYawOffset: Float
        get() = prefs.getFloat(KEY_CENTER_YAW, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_CENTER_YAW, value).apply()

    var centerNoseX: Float
        get() = prefs.getFloat(KEY_CENTER_NOSE_X, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CENTER_NOSE_X, value).apply()

    var centerNoseY: Float
        get() = prefs.getFloat(KEY_CENTER_NOSE_Y, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CENTER_NOSE_Y, value).apply()

    var centerEyeX: Float
        get() = prefs.getFloat(KEY_CENTER_EYE_X, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CENTER_EYE_X, value).apply()

    var centerEyeY: Float
        get() = prefs.getFloat(KEY_CENTER_EYE_Y, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CENTER_EYE_Y, value).apply()

    var isDwellClickEnabled: Boolean
        get() = prefs.getBoolean(KEY_DWELL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DWELL_ENABLED, value).apply()

    var isHapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var isVoiceCommandsEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COMMANDS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_COMMANDS_ENABLED, value).apply()

    var cursorColor: String
        get() = prefs.getString(KEY_CURSOR_COLOR, "#00E5FF") ?: "#00E5FF"
        set(value) = prefs.edit().putString(KEY_CURSOR_COLOR, value).apply()

    var cursorStyle: CursorStyle
        get() {
            val name = prefs.getString(KEY_CURSOR_STYLE, CursorStyle.CLASSIC_TRIPLE_DOT.name)
            return try {
                CursorStyle.valueOf(name ?: CursorStyle.CLASSIC_TRIPLE_DOT.name)
            } catch (e: Exception) {
                CursorStyle.CLASSIC_TRIPLE_DOT
            }
        }
        set(value) = prefs.edit().putString(KEY_CURSOR_STYLE, value.name).apply()

    var appTheme: AppTheme
        get() {
            val name = prefs.getString(KEY_APP_THEME, AppTheme.CYBERPUNK_NEON.name)
            return try {
                AppTheme.valueOf(name ?: AppTheme.CYBERPUNK_NEON.name)
            } catch (e: Exception) {
                AppTheme.CYBERPUNK_NEON
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME, value.name).apply()

    var isCursorTrailEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURSOR_TRAIL, true)
        set(value) = prefs.edit().putBoolean(KEY_CURSOR_TRAIL, value).apply()

    var isUltraCoolMode: Boolean
        get() = prefs.getBoolean(KEY_ULTRA_COOL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_ULTRA_COOL_MODE, value).apply()

    var isDockOnLeft: Boolean
        get() = prefs.getBoolean(KEY_DOCK_ON_LEFT, false)
        set(value) = prefs.edit().putBoolean(KEY_DOCK_ON_LEFT, value).apply()

    var targetFps: Int
        get() = prefs.getInt(KEY_TARGET_FPS, 60)
        set(value) = prefs.edit().putInt(KEY_TARGET_FPS, value).apply()

    var isVehicleModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_VEHICLE_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_VEHICLE_MODE, value).apply()

    var isIntentGatingEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTENT_GATING, true)
        set(value) = prefs.edit().putBoolean(KEY_INTENT_GATING, value).apply()

    var keyboardCursorOffsetX: Float
        get() = prefs.getFloat(KEY_KB_OFFSET_X, 0f)
        set(value) = prefs.edit().putFloat(KEY_KB_OFFSET_X, value).apply()

    var keyboardCursorOffsetY: Float
        get() = prefs.getFloat(KEY_KB_OFFSET_Y, 0f)
        set(value) = prefs.edit().putFloat(KEY_KB_OFFSET_Y, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var customApiKey: String
        get() = getActiveCustomModel()?.apiKey ?: prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_API_KEY, value).apply()
            val active = getActiveCustomModel()
            if (active != null) {
                active.apiKey = value
                addOrUpdateCustomModel(active)
            }
        }

    var customBaseUrl: String
        get() = getActiveCustomModel()?.baseUrl ?: prefs.getString(KEY_CUSTOM_BASE_URL, "https://api.xkiro.com/v1") ?: "https://api.xkiro.com/v1"
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_BASE_URL, value).apply()
            val active = getActiveCustomModel()
            if (active != null) {
                active.baseUrl = value
                addOrUpdateCustomModel(active)
            }
        }

    var customModelName: String
        get() = getActiveCustomModel()?.modelId ?: prefs.getString(KEY_CUSTOM_MODEL_NAME, "deepseek/deepseek-chat") ?: "deepseek/deepseek-chat"
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_MODEL_NAME, value).apply()
            val active = getActiveCustomModel()
            if (active != null) {
                active.modelId = value
                addOrUpdateCustomModel(active)
            }
        }

    var isCloudAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_AI_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_AI_ENABLED, value).apply()

    var aiProvider: AiProvider
        get() {
            val savedName = prefs.getString(KEY_AI_PROVIDER, null)
            return if (!savedName.isNullOrBlank()) {
                try {
                    AiProvider.valueOf(savedName)
                } catch (_: Exception) {
                    AiProvider.CUSTOM_OPENROUTER
                }
            } else {
                if (getCustomModels().isNotEmpty()) AiProvider.CUSTOM_OPENROUTER else AiProvider.GEMINI
            }
        }
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()

    var aiModelName: String
        get() = getActiveCustomModel()?.modelId ?: prefs.getString(KEY_AI_MODEL_NAME, "deepseek/deepseek-chat") ?: "deepseek/deepseek-chat"
        set(value) = prefs.edit().putString(KEY_AI_MODEL_NAME, value).apply()

    fun getCustomModels(): List<CustomAiModel> {
        val jsonStr = prefs.getString(KEY_CUSTOM_MODELS_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<CustomAiModel>()
                for (i in 0 until array.length()) {
                    list.add(CustomAiModel.fromJson(array.getJSONObject(i)))
                }
                if (list.isNotEmpty()) {
                    if (list.none { it.isActive }) {
                        list[0].isActive = true
                    }
                    return list
                }
            } catch (e: Exception) {
                android.util.Log.w("AppSettings", "Error parsing custom models JSON", e)
            }
        }

        // Migration or initial fallback: if user previously entered a custom key or model, preserve it as custom model
        val legacyKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        val legacyUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "https://api.xkiro.com/v1") ?: "https://api.xkiro.com/v1"
        val legacyModel = prefs.getString(KEY_CUSTOM_MODEL_NAME, "deepseek/deepseek-chat") ?: "deepseek/deepseek-chat"

        if (legacyKey.isNotBlank()) {
            val initial = CustomAiModel(
                name = "xKiro Custom Brain",
                modelId = legacyModel,
                baseUrl = legacyUrl,
                apiKey = legacyKey,
                isActive = true
            )
            saveCustomModels(listOf(initial))
            return listOf(initial)
        }

        return emptyList()
    }

    fun saveCustomModels(models: List<CustomAiModel>) {
        val array = JSONArray()
        for (m in models) {
            array.put(m.toJson())
        }
        prefs.edit().putString(KEY_CUSTOM_MODELS_JSON, array.toString()).apply()

        // Sync active model settings
        val active = models.firstOrNull { it.isActive }
        if (active != null) {
            prefs.edit()
                .putString(KEY_CUSTOM_MODEL_NAME, active.modelId)
                .putString(KEY_AI_MODEL_NAME, active.modelId)
                .putString(KEY_CUSTOM_BASE_URL, active.baseUrl)
                .putString(KEY_CUSTOM_API_KEY, active.apiKey)
                .putString(KEY_AI_PROVIDER, AiProvider.CUSTOM_OPENROUTER.name)
                .apply()
        }
    }

    fun getActiveCustomModel(): CustomAiModel? {
        val models = getCustomModels()
        return models.firstOrNull { it.isActive } ?: models.firstOrNull()
    }

    fun setActiveCustomModel(id: String) {
        val models = getCustomModels()
        for (m in models) {
            m.isActive = (m.id == id)
        }
        saveCustomModels(models)
    }

    fun addOrUpdateCustomModel(model: CustomAiModel) {
        val models = getCustomModels().toMutableList()
        val index = models.indexOfFirst { it.id == model.id }
        if (index >= 0) {
            models[index] = model
        } else {
            if (models.isEmpty()) {
                model.isActive = true
            }
            models.add(model)
        }
        saveCustomModels(models)
    }

    fun deleteCustomModel(id: String) {
        val models = getCustomModels().toMutableList()
        val toRemove = models.firstOrNull { it.id == id }
        if (toRemove != null) {
            val wasActive = toRemove.isActive
            models.remove(toRemove)
            if (wasActive && models.isNotEmpty()) {
                models[0].isActive = true
            }
            saveCustomModels(models)
        }
    }

    fun getActiveApiKey(): String {
        return when (aiProvider) {
            AiProvider.GEMINI -> {
                val directKey = geminiApiKey.takeIf { it.isNotBlank() }
                val customKey = getActiveCustomModel()?.apiKey?.takeIf { it.isNotBlank() }
                directKey ?: customKey ?: prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
            }
            AiProvider.OPENAI -> {
                val directKey = openAiApiKey.takeIf { it.isNotBlank() }
                val customKey = getActiveCustomModel()?.apiKey?.takeIf { it.isNotBlank() }
                directKey ?: customKey ?: prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
            }
            AiProvider.CUSTOM_OPENROUTER -> {
                getActiveCustomModel()?.apiKey?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(KEY_CUSTOM_API_KEY, "")
                    ?: ""
            }
        }
    }

    fun setActiveApiKey(key: String) {
        prefs.edit().putString(KEY_CUSTOM_API_KEY, key).apply()
        val active = getActiveCustomModel()
        if (active != null) {
            active.apiKey = key
            addOrUpdateCustomModel(active)
        }
    }

    fun clearActiveApiKey() {
        setActiveApiKey("")
    }

    var isJarvisVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_JARVIS_VOICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_JARVIS_VOICE_ENABLED, value).apply()

    var isJarvisWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_JARVIS_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_JARVIS_WAKE_WORD_ENABLED, value).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "head_mouse_settings"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_TRACKING_MODE = "tracking_mode"
        private const val KEY_VOICE_COMMANDS_ENABLED = "voice_commands_enabled"
        private const val KEY_CURSOR_SPEED = "cursor_speed"
        private const val KEY_SENS_X = "sensitivity_x"
        private const val KEY_SENS_Y = "sensitivity_y"
        private const val KEY_DWELL_ENABLED = "dwell_enabled"
        private const val KEY_DWELL_TIME = "dwell_time"
        private const val KEY_KEYBOARD_DWELL_TIME = "keyboard_dwell_time"
        private const val KEY_GESTURE_CLICK = "gesture_click_enabled"
        private const val KEY_DWELL_RADIUS = "dwell_radius"
        private const val KEY_SMOOTHING_CUTOFF = "smoothing_cutoff"
        private const val KEY_DEADZONE = "deadzone"
        private const val KEY_CENTER_PITCH = "center_pitch"
        private const val KEY_CENTER_YAW = "center_yaw"
        private const val KEY_CENTER_NOSE_X = "center_nose_x"
        private const val KEY_CENTER_NOSE_Y = "center_nose_y"
        private const val KEY_CENTER_EYE_X = "center_eye_x"
        private const val KEY_CENTER_EYE_Y = "center_eye_y"
        private const val KEY_HAPTIC = "haptic_enabled"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        private const val KEY_CURSOR_STYLE = "cursor_style"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_CURSOR_TRAIL = "cursor_trail_enabled"
        private const val KEY_ULTRA_COOL_MODE = "ultra_cool_mode"
        private const val KEY_DOCK_ON_LEFT = "dock_on_left"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_VEHICLE_MODE = "vehicle_mode_enabled"
        private const val KEY_INTENT_GATING = "intent_gating_enabled"
        private const val KEY_KB_OFFSET_X = "keyboard_cursor_offset_x"
        private const val KEY_KB_OFFSET_Y = "keyboard_cursor_offset_y"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
        private const val KEY_CLOUD_AI_ENABLED = "cloud_ai_enabled"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_MODEL_NAME = "ai_model_name"
        private const val KEY_JARVIS_VOICE_ENABLED = "jarvis_voice_enabled"
        private const val KEY_JARVIS_WAKE_WORD_ENABLED = "jarvis_wake_word_enabled"
        private const val KEY_HEAD_MOUSE_ACTIVE = "head_mouse_active"
        private const val KEY_JARVIS_ACTIVE = "jarvis_active"
        private const val KEY_CUSTOM_MODELS_JSON = "custom_models_json"
    }
}

enum class AiProvider {
    GEMINI,
    OPENAI,
    CUSTOM_OPENROUTER
}

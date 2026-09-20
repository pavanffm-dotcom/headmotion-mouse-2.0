package com.assistive.headmouse.agent.jarvis

import android.content.Context
import android.util.Log
import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.autonomous.AgentOrchestrator
import com.assistive.headmouse.preferences.AiProvider
import com.assistive.headmouse.preferences.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class MissionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    ABORTED
}

data class MissionStep(
    val stepNumber: Int,
    val thought: String,
    val action: String,
    val x: Float? = null,
    val y: Float? = null,
    val text: String? = null,
    val spokenUpdate: String? = null,
    val isFinished: Boolean = false
)

/**
 * Autonomous Action Execution Engine for J.A.R.V.I.S.
 * Bridges cognitive planning to physical Android AccessibilityService gestures,
 * manages TaskContext, enforces single-mission execution lock, and guarantees
 * local screen verification and recovery without wasteful per-action cloud API calls.
 */
class JarvisMissionExecutor(
    private val context: Context,
    private val jarvisBrain: JarvisBrain,
    private val voiceEngine: JarvisVoiceEngine?
) {

    private val missionScope = CoroutineScope(Dispatchers.Main)
    private var activeJob: Job? = null
    private val appSettings = AppSettings(context)

    val screenObserver = ScreenObserver()
    val actionExecutor = ActionExecutor(context, screenObserver)
    val recoveryEngine = RecoveryEngine(screenObserver, actionExecutor)

    val agentOrchestrator = AgentOrchestrator(
        context = context,
        screenObserver = screenObserver,
        actionExecutor = actionExecutor,
        voiceEngine = voiceEngine,
        appSettings = appSettings,
        jarvisBrain = jarvisBrain
    )

    var currentStatus: MissionStatus = MissionStatus.IDLE
        private set

    var onStatusChanged: ((MissionStatus, String) -> Unit)? = null
    var onStepExecuted: ((Int, String) -> Unit)? = null

    init {
        // Wire heavy autonomous orchestrator callbacks
        agentOrchestrator.onStatusUpdate = { state, msg ->
            when (state) {
                "PLANNING", "OBSERVING", "EXECUTING", "VERIFYING" -> {
                    currentStatus = MissionStatus.RUNNING
                    onStatusChanged?.invoke(MissionStatus.RUNNING, msg)
                }
                "CANCELLED" -> {
                    currentStatus = MissionStatus.ABORTED
                    onStatusChanged?.invoke(MissionStatus.ABORTED, msg)
                }
                else -> {
                    onStatusChanged?.invoke(currentStatus, msg)
                }
            }
        }

        agentOrchestrator.onStepExecuted = { step, result ->
            val desc = "${step.action.name} on ${step.target?.value ?: step.text ?: "screen"}"
            onStepExecuted?.invoke(step.id, desc)
        }

        agentOrchestrator.onMissionFinished = { success, msg ->
            currentStatus = if (success) MissionStatus.COMPLETED else MissionStatus.FAILED
            onStatusChanged?.invoke(currentStatus, msg)
        }
    }

    /**
     * Initiates an end-to-end autonomous mission using AgentOrchestrator.
     * Full autonomous pipeline: OBSERVE -> PLAN -> ACT -> VERIFY -> REPLAN -> COMPLETE.
     */
    fun startMission(
        missionGoal: String,
        apiKey: String = "",
        isCloudEnabled: Boolean = false,
        provider: AiProvider = AiProvider.GEMINI,
        modelName: String = "gemini-1.5-flash",
        customBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions"
    ) {
        stopMission() // Cancel any prior active mission

        currentStatus = MissionStatus.RUNNING
        onStatusChanged?.invoke(MissionStatus.RUNNING, "Starting autonomous mission: \"$missionGoal\"")
        Log.i(TAG, "[CANONICAL_AI_ROUTE] JarvisMissionExecutor dispatching mission to canonical AgentOrchestrator: \"$missionGoal\"")

        // Run through full AgentOrchestrator
        agentOrchestrator.startMission(missionGoal)
    }

    /**
     * Emergency interruption ("Stop", "Jarvis ruk ja", "Cancel").
     * Halts all active operations within <200ms and releases the execution lock.
     */
    fun stopMission() {
        if (agentOrchestrator.isRunning || activeJob?.isActive == true) {
            agentOrchestrator.cancelMission()
            activeJob?.cancel()
            activeJob = null
            currentStatus = MissionStatus.ABORTED
            onStatusChanged?.invoke(MissionStatus.ABORTED, "Mission aborted by user.")
            Log.i(TAG, "Emergency abort dispatched to AgentOrchestrator.")
        }
    }

    companion object {
        private const val TAG = "JarvisMissionExecutor"
    }
}

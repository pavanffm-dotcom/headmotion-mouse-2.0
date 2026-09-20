package com.assistive.headmouse.agent.jarvis.autonomous.verification

import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall

/**
 * Phase 5: Result of empirical verification evaluating whether a tool invocation succeeded.
 *
 * [state] is the authoritative outcome. [verified] is a convenience alias.
 */
data class VerificationResult(
    val state: VerificationState,
    val stateChanged: Boolean,
    val focusChanged: Boolean = false,
    val screenChanged: Boolean = false,
    val explanation: String,
    val isRecoverable: Boolean = true
) {
    /** Convenience alias — true only when state == SUCCESS. */
    val verified: Boolean get() = state == VerificationState.SUCCESS
}

/**
 * Phase 5: Empirical Verification Engine.
 *
 * Evaluates every action's postcondition using WorldState diffs and resolves to
 * a precise [VerificationState]:
 *   SUCCESS, FAILED, PARTIAL, UNCHANGED, UNKNOWN, REPLAN_REQUIRED
 */
class VerificationEngine {

    /**
     * Evaluates whether the executed tool achieved its intended postcondition.
     */
    fun verify(
        toolCall: ToolCall,
        preState: WorldState?,
        postState: WorldState
    ): VerificationResult {
        if (preState == null) {
            return VerificationResult(
                state = VerificationState.SUCCESS,
                stateChanged = true,
                focusChanged = false,
                screenChanged = true,
                explanation = "Initial observation established."
            )
        }

        val screenChanged = (preState.screenHash != postState.screenHash ||
                preState.accessibilityHash != postState.accessibilityHash)
        val focusChanged = (preState.focusedNode != postState.focusedNode)
        val keyboardChanged = (preState.isKeyboardVisible != postState.isKeyboardVisible)
        val packageChanged = (preState.foregroundPackage != postState.foregroundPackage)
        val stateChanged = screenChanged || focusChanged || keyboardChanged || packageChanged

        return when (toolCall.name) {

            CanonicalTools.LAUNCH_APP -> {
                val target = toolCall.arguments["package_or_name"]?.toString()?.lowercase() ?: ""
                val targetPkg = resolveKnownPackage(target) ?: target
                val currentPkg = postState.foregroundPackage.lowercase()

                when {
                    // Exact match or substring match — clear success
                    currentPkg.contains(targetPkg) || (target.isNotBlank() && currentPkg.contains(target)) -> {
                        VerificationResult(
                            state = VerificationState.SUCCESS,
                            stateChanged = true,
                            screenChanged = true,
                            explanation = "Target app is active in foreground: ${postState.foregroundPackage}"
                        )
                    }
                    // Package changed but to a different app — unexpected navigation
                    packageChanged && !currentPkg.contains(targetPkg) -> {
                        VerificationResult(
                            state = VerificationState.REPLAN_REQUIRED,
                            stateChanged = true,
                            screenChanged = true,
                            explanation = "Expected '$target' but foreground is '${postState.foregroundPackage}'. Unexpected navigation.",
                            isRecoverable = true
                        )
                    }
                    // Package unchanged — app did not launch
                    else -> {
                        VerificationResult(
                            state = VerificationState.FAILED,
                            stateChanged = false,
                            explanation = "App '$target' did not launch. Foreground: '${postState.foregroundPackage}'",
                            isRecoverable = true
                        )
                    }
                }
            }

            CanonicalTools.TAP_ELEMENT, CanonicalTools.TAP_COORDINATES -> {
                val targetLabel = toolCall.arguments["label"]?.toString()?.lowercase() ?: ""

                when {
                    // Screen fully changed — definitive success
                    stateChanged && (screenChanged || packageChanged) -> {
                        VerificationResult(
                            state = VerificationState.SUCCESS,
                            stateChanged = true,
                            focusChanged = focusChanged,
                            screenChanged = screenChanged,
                            explanation = "UI mutated successfully after tap."
                        )
                    }
                    // Focus shifted to a node — partial/focus success
                    focusChanged && postState.focusedNode != null -> {
                        VerificationResult(
                            state = VerificationState.PARTIAL,
                            stateChanged = true,
                            focusChanged = true,
                            explanation = "Focus shifted after tap. Screen may still be loading."
                        )
                    }
                    // Target element disappeared (e.g. dismissed or navigated)
                    targetLabel.isNotBlank() -> {
                        val wasBefore = preState.nodes.any { it.label.lowercase().contains(targetLabel) }
                        val isNow = postState.nodes.any { it.label.lowercase().contains(targetLabel) }
                        if (wasBefore && !isNow) {
                            VerificationResult(
                                state = VerificationState.SUCCESS,
                                stateChanged = true,
                                explanation = "Target '$targetLabel' dismissed/navigated away — action confirmed."
                            )
                        } else {
                            VerificationResult(
                                state = VerificationState.UNCHANGED,
                                stateChanged = false,
                                explanation = "Screen unchanged after tap. Element may have missed or was unclickable.",
                                isRecoverable = true
                            )
                        }
                    }
                    else -> {
                        VerificationResult(
                            state = VerificationState.UNCHANGED,
                            stateChanged = false,
                            explanation = "Screen completely unchanged after tap.",
                            isRecoverable = true
                        )
                    }
                }
            }

            CanonicalTools.TYPE_TEXT -> {
                val textToType = toolCall.arguments["text"]?.toString() ?: ""
                val textFoundInNodes = postState.nodes.any {
                    (it.text?.contains(textToType, ignoreCase = true) == true) ||
                    (it.label.contains(textToType, ignoreCase = true)) ||
                    (it.isFocused && it.label.contains(textToType, ignoreCase = true))
                }
                when {
                    textFoundInNodes -> {
                        VerificationResult(
                            state = VerificationState.SUCCESS,
                            stateChanged = true,
                            explanation = "Injected text '$textToType' verified on screen / search results."
                        )
                    }
                    stateChanged -> {
                        // State changed but text not confirmed — keyboard may have appeared
                        VerificationResult(
                            state = VerificationState.PARTIAL,
                            stateChanged = true,
                            explanation = "State changed after typing but text '$textToType' not confirmed in nodes."
                        )
                    }
                    else -> {
                        VerificationResult(
                            state = VerificationState.FAILED,
                            stateChanged = false,
                            explanation = "Text '$textToType' not found in focused field after typing.",
                            isRecoverable = true
                        )
                    }
                }
            }

            CanonicalTools.SCROLL, CanonicalTools.SWIPE -> {
                when {
                    stateChanged -> VerificationResult(
                        state = VerificationState.SUCCESS,
                        stateChanged = true,
                        explanation = "Screen scrolled/swiped — new content visible."
                    )
                    else -> VerificationResult(
                        state = VerificationState.UNCHANGED,
                        stateChanged = false,
                        explanation = "Screen did not scroll. Possible end of page / boundary reached.",
                        isRecoverable = true
                    )
                }
            }

            CanonicalTools.PRESS_NAVIGATION -> {
                when {
                    packageChanged -> VerificationResult(
                        state = VerificationState.SUCCESS,
                        stateChanged = true,
                        explanation = "Navigation moved to new package: ${postState.foregroundPackage}"
                    )
                    stateChanged -> VerificationResult(
                        state = VerificationState.SUCCESS,
                        stateChanged = true,
                        explanation = "Navigation action changed screen state."
                    )
                    else -> VerificationResult(
                        state = VerificationState.UNCHANGED,
                        stateChanged = false,
                        explanation = "Navigation action did not change the screen state.",
                        isRecoverable = true
                    )
                }
            }

            CanonicalTools.LONG_PRESS -> {
                when {
                    stateChanged -> VerificationResult(
                        state = VerificationState.SUCCESS,
                        stateChanged = true,
                        explanation = "Long-press triggered UI change (context menu or selection)."
                    )
                    else -> VerificationResult(
                        state = VerificationState.UNCHANGED,
                        stateChanged = false,
                        explanation = "Long-press produced no visible screen change.",
                        isRecoverable = true
                    )
                }
            }

            CanonicalTools.WAIT, CanonicalTools.OBSERVE_SCREEN, CanonicalTools.TAKE_SCREENSHOT -> {
                VerificationResult(
                    state = VerificationState.SUCCESS,
                    stateChanged = stateChanged,
                    explanation = "Passive operation completed. Screen ${if (stateChanged) "changed" else "stable"}."
                )
            }

            CanonicalTools.WEB_SEARCH -> {
                VerificationResult(
                    state = VerificationState.SUCCESS,
                    stateChanged = false,
                    explanation = "Web search executed and results processed."
                )
            }

            CanonicalTools.FINISH_TASK -> {
                VerificationResult(
                    state = VerificationState.SUCCESS,
                    stateChanged = false,
                    explanation = "Mission completion confirmed."
                )
            }

            else -> {
                when {
                    stateChanged -> VerificationResult(
                        state = VerificationState.SUCCESS,
                        stateChanged = true,
                        explanation = "Action completed with detected state mutation."
                    )
                    else -> VerificationResult(
                        state = VerificationState.UNKNOWN,
                        stateChanged = false,
                        explanation = "No state mutation detected for tool '${toolCall.name}'."
                    )
                }
            }
        }
    }

    private fun resolveKnownPackage(name: String): String? = when (name.lowercase().trim()) {
        "youtube" -> "com.google.android.youtube"
        "whatsapp" -> "com.whatsapp"
        "settings" -> "com.android.settings"
        "play store", "playstore", "vending" -> "com.android.vending"
        "chrome", "browser" -> "com.android.chrome"
        "instagram" -> "com.instagram.android"
        "maps" -> "com.google.android.apps.maps"
        "gmail" -> "com.google.android.gm"
        else -> null
    }
}


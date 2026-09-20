# Original User Request

## Initial Request — 2026-09-04T16:34:33Z

This is a single self-contained fix; keep it small and focused.

Enhance HeadMotion Mouse with an immersive full-screen dark theme, a configurable 30 FPS / 60 FPS performance toggle with live in-app FPS meter, and an overhaul of the voice command engine (context-aware in-app searching, direct package launching for Instagram/Play Store, and elimination of erratic Play Store redirects).

Working directory: C:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse
Integrity mode: development

## Requirements

### R1. Immersive Full-Screen Modern Theme
- Upgrade the application UI to a sleek, modern, cohesive dark theme.
- Enable Android Window InsetsController Immersive Sticky Mode (hiding system status bar and navigation bar) so the app utilizes 100% of the display edge-to-edge with zero distraction from external system bars.

### R2. 30 FPS vs 60 FPS Control & Real-Time In-App FPS Meter
- In Settings (Tab 5), provide a 30 FPS vs 60 FPS performance toggle with thermal protection:
  - 30 FPS: Maximum battery conservation, ultra-low power.
  - 60 FPS: Ultra-smooth 60Hz display VSYNC motion without CPU/GPU thermal overload.
- Implement a real-time live FPS meter in the Settings tab that measures and renders the actual active render refresh rate while inside the app (stopping when exiting or pausing to conserve power).

### R3. Voice Command Engine Overhaul & Direct App Launching
- Fix the fallback bug where unrecognized speech inadvertently redirected to Google Play Store market search or YouTube search.
- Add direct launch intents for standard applications:
  - "Open Play Store" / "प्ले स्टोर खोलो" → opens `com.android.vending`.
  - "Open Instagram" / "इंस्टाग्राम खोलो" → opens `com.instagram.android` directly (fallback to package manager, never arbitrary redirects).
  - "Open YouTube", "Open WhatsApp", "Open Camera", "Open Settings", "Open Chrome", "Open Maps", "Open Gallery".
- Context-Aware In-App Search:
  - If user is currently in YouTube, "search [query]" executes search directly inside YouTube.
  - If user is currently in Play Store, "search [query]" executes search directly inside Play Store.
  - If not in a supported search host, gracefully notify or target system search without hijacking unrelated apps.

### R4. Instant Voice Command Latency & Smart Mic Lifecycle
- Optimize the speech recognizer audio session to eliminate delay and lagging responses on directional commands ('Down', 'Up', 'Click').
- Ensure microphone resources do not stay permanently locked or stall audio DSPs when idle.

## Acceptance Criteria

### UI & Theme
- [ ] Application runs in true immersive full-screen mode (system status and nav bars hidden).
- [ ] App design uses modern styling with high-contrast readable typography and dark glassmorphic cards.

### Performance & FPS
- [ ] Settings tab includes working 30 FPS / 60 FPS toggle.
- [ ] Live FPS meter displays active rendering frames per second in real time inside Settings.
- [ ] Floating cursor maintains smooth motion adhering to the selected FPS profile.

### Voice Commands
- [ ] Saying "Open Play Store" opens Google Play Store directly.
- [ ] Saying "Open Instagram" opens Instagram directly without redirecting to Play Store.
- [ ] Saying "Down" or "Up" executes immediate scroll dispatch with sub-second response.
- [ ] Contextual search queries correctly route to the foreground app (YouTube / Play Store).
- [ ] Unrecognized phrases do NOT launch Play Store market search.

### Verification
- [ ] `.\gradlew.bat compileDebugKotlin` passes with 0 errors.
- [ ] `.\gradlew.bat assembleDebug` succeeds and produces updated `app-debug.apk`.

## Follow-up — 2026-09-13T13:15:29Z

HeadMotionMouse is a hands-free Android accessibility app that lets people control their phone entirely using head movements and facial gestures detected via the front camera and ML Kit. Phase 4 targets the remaining 10% accuracy gap (cursor overshooting adjacent pixels), excessive battery drain and phone heating, a new in-app Testing section in Settings, and pixel-perfect keyboard key targeting.

Working directory: c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse

---

## Requirements

### R1. Sub-Pixel Cursor Accuracy (Fix the 10% Miss Problem)

The cursor frequently lands on adjacent pixels instead of the intended target. For example, if the user targets pixel column 750 on a 1080px screen, the cursor may settle on 740 or 760 instead. This is caused by three compounding issues in the existing pipeline:

1. **CalibrationManager**: The One-Euro Filter's `minCutoff = 0.05` and `beta = 0.004` are fixed values that do not adapt to the current FPS (30/60/120 Hz) or to the face detection resolution (320×240 input being upscaled to 1080p). At 30 FPS the filter over-smooths and at 120 FPS it under-smooths, both causing centroid drift away from the target.

2. **HeadPoseEngine**: The adaptive alpha thresholds for nose-landmark smoothing (`alphaNoseX/Y = 0.08f when diff < 0.01f`) were tuned for 60 FPS. At 30 FPS each frame interval is 2× longer, so the same alpha over-smooths; at 120 FPS it under-filters. The filters must be re-parameterized per-frame using elapsed time (dt).

3. **Coordinate Mapping**: `mapHeadPoseToScreen` applies `rawPixelX = (screenWidth / 2f) + (deltaX * screenWidth * factorX)`. When `factorX` includes the gear ramp (`smoothedGear * cursorSpeed * sensitivityX`), small changes in gear produce large pixel jumps near the edges of the screen. The gear ramp must be linearized relative to screen pixels, not relative to the [0,1] normalized input range.

Fix all three root causes so that when the user holds their head still over a target for 500ms, the cursor consistently snaps to within ±1.5dp of the intended target center with no drift.

### R2. Battery Optimization & Phone Heat Reduction

The app currently drains battery excessively even when the user is actively tracking (non-standby mode). Key sources of unnecessary work:

- CameraX is bound at 320×240 but ML Kit internally upsamples to a higher resolution for landmark computation — the face detector options must explicitly set `PERFORMANCE_MODE_FAST` and limit to `CONTOUR_MODE_NONE` where full landmarks are not required.
- `FaceTrackerManager.processImageProxy` allocates a new `InputImage` on every frame on the camera executor thread. This triggers GC on the background thread.
- The main handler posts a `faceCheckRunnable` every 180ms unconditionally even when the service is in standby (6 FPS) mode — during standby this polling interval should be stretched to at least 800ms.
- `CalibrationManager.mapHeadPoseToScreen` runs `kotlin.math.hypot` + `coerceIn` + two `OneEuroFilter.filter()` calls per frame. The One-Euro filter contains a `pow()` call on every invocation that can be replaced with a precomputed lookup for the fixed `dt` values at each FPS tier.

Reduce active-tracking battery drain by at least 25% (measured as milliwatts via Android Battery Historian or CPU time reduction in a Systrace) without changing the tracking quality from R1. The phone temperature must not rise above warm-to-the-touch (approximately ≤38°C) during 10 minutes of continuous active tracking.

### R3. In-App Testing Section (Settings → Test)

Add a new "Test" entry in the Settings tab (`layout_tab_settings` in `activity_main.xml`). When tapped (via dwell click), it opens a full-screen testing activity or fragment with independent test modules. All test modules must work hands-free via dwell click. Include at minimum:

1. **Cursor Accuracy Test** — Display a 3×3 grid of circular target dots at known pixel positions (corners, edges, center). User dwells on each dot; the test records the delta between cursor position at dwell-fire time and the dot center, then shows a final accuracy score (mean error in dp).

2. **Dwell Click Speed Test** — Show 10 sequential highlighted buttons. Measures the time from button highlight to user's successful dwell-click. Shows average reaction time and miss rate (cursor left tolerance zone before dwell completed).

3. **Keyboard Accuracy Test** — Open a text input field, detect the soft keyboard, and highlight random keys (A–Z, Space, Backspace) one at a time. User dwell-clicks each key; test records whether the actual key pressed (from `onAccessibilityEvent` text-change events) matches the highlighted key. Shows keyboard accuracy percentage.

4. **FPS & Thermal Monitor** — Live readout during tracking: current FPS (frames processed per second by ML Kit), CPU temperature (if readable via `/sys/class/thermal/thermal_zone*/temp`), and estimated battery draw (mA from `/sys/class/power_supply/battery/current_now`).

5. **Gesture Recognition Test** — Cycles through gestures (Teeth Show, Left Wink, Right Wink) with on-screen prompts. Records detection latency and false-positive rate over a 30-second window.

All test results must be displayable as a summary card after each test and optionally exportable as a plain-text report via Android's share sheet.

### R4. Pixel-Perfect Keyboard Key Targeting

The current dwell click often fires at the visual cursor position but that position does not align with the correct keyboard key center because:

1. The fallback QWERTY grid in `KeyboardKeyDetector.generateQwertyKeys()` uses hardcoded row offsets (42dp header height, 4 equal rows) which do not match Gboard's actual key layout on all screen densities and aspect ratios.
2. When `hasRealImeWindow = true` but the IME window does not expose `AccessibilityNodeInfo` nodes (Gboard often has `FLAG_IS_ACCESSIBILITY_FOCUS_TREE = false`), `findKeyFromWindowNodes()` falls through to the QWERTY grid fallback — but the cursor's visual position at that point is still in screen coordinates while the grid was computed with relative keyboard-local coordinates.
3. There is no "keyboard magnetic snap" that snaps the cursor to the nearest key center when inside the keyboard area — so the user must position the cursor with sub-key precision manually.

Implement a robust keyboard targeting system:
- When the cursor enters the keyboard area, switch the cursor display to a "key highlight" mode: instead of the circular dwell ring, draw a rectangular highlight border around the currently targeted key.
- Snap the cursor internally (for dwell firing) to the center of the nearest key, not to the raw cursor position. Visual feedback must show which key is targeted.
- The QWERTY grid fallback must dynamically measure the actual IME window height from `AccessibilityWindowInfo.getBoundsInScreen()` (already fetched in `isKeyboardActive`) and distribute rows proportionally rather than using hardcoded dp values.
- Add a `keyboardCursorOffsetX/Y` calibration parameter in `AppSettings` (default 0f each) so the user can fine-tune alignment if their camera angle introduces a systematic offset.

---

## Acceptance Criteria

### Cursor Accuracy (R1)
- [ ] When the user holds their head still for 500ms with the cursor over a known 48dp×48dp target, the dwell fires within ±1.5dp of the target center in at least 9 out of 10 attempts (measured by the Cursor Accuracy Test from R3).
- [ ] No visible "pixel jump" greater than 8dp occurs between two consecutive frames at any FPS setting (30/60/120).

### Battery & Thermal (R2)
- [ ] CPU time consumed by the face tracking thread drops by at least 25% relative to the pre-fix baseline (verifiable via `adb shell top -d 1 | grep -i face` or Systrace).
- [ ] The `faceCheckRunnable` polling interval in standby is ≥800ms (verifiable by reading the source code).
- [ ] No `pow()` call inside the OneEuroFilter's per-frame hot path (verifiable by grep on source).

### Testing Section (R3)
- [ ] Settings tab contains a "Test" card/button that navigates to a test screen.
- [ ] All 5 test modules are accessible via dwell click with no physical touch required.
- [ ] Cursor Accuracy Test reports mean error in dp after completing all 9 targets.
- [ ] Keyboard Accuracy Test correctly detects at least 26 distinct keys (A–Z) with proper highlight and records accuracy percentage.
- [ ] FPS & Thermal Monitor displays a live FPS value that updates at least once per second.

### Keyboard Accuracy (R4)
- [ ] When the soft keyboard is open, the dwell ring is replaced by a key highlight rectangle around the targeted key.
- [ ] Dwell fires at the snapped key center, not at the raw cursor position — verified by the Keyboard Accuracy Test achieving ≥85% accuracy on a standard QWERTY layout.
- [ ] `AppSettings` contains `keyboardCursorOffsetX` and `keyboardCursorOffsetY` Float fields (default 0f) that shift the keyboard snap grid.

---

## Build Verification

After all changes, the following commands must succeed without errors:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL` with 0 errors (deprecation warnings are acceptable).

---

*Constraints: All UI text must remain in English only. Do not break existing features: teeth-reveal pause/resume, FPS selector (30/60/120), 5-second recenter countdown, dwell grace delay, wink gestures, or dock navigation.*

## Follow-up — 2026-09-14T04:04:57Z

# Teamwork Project Prompt — JARVIS Milestone 1 (Phase 7): Semantic Screen Awareness & Smart Voice Targeting

> Status: Launched
> Goal: Implement real-time Accessibility Node Tree inspection, 2D spatial UI caching, magnetic UI snapping, and deictic/named voice targeting in HeadMotionMouse while preserving 60 FPS tracking and stability.
> Requested team: [none — teamwork routes from the description]

Integrate foundational JARVIS intelligence into HeadMotionMouse (Option C Decoupled Architecture as specified in `jarvis_architecture_design_document.md`): equip the Accessibility Service with semantic screen awareness by parsing the active window's UI hierarchy (`rootInActiveWindow`), maintaining an in-memory 2D spatial cache of interactive elements, enabling smart magnetic snapping to any on-screen button or control, and empowering the user with contextual voice commands ("Click this", "Click [Button Name]") alongside existing 60 FPS head/eye tracking.

Working directory: c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse
Integrity mode: development

---

## Requirements

### R1. Semantic Screen Node Tree Parser & Spatial Cache
- Safely inspect the on-screen UI hierarchy via `AccessibilityNodeInfo` (`rootInActiveWindow`).
- Recursively extract all interactive elements (`isClickable`, `isFocusable`, `isScrollable`, `isCheckable`) along with their screen bounds (`getBoundsInScreen`), display text, content descriptions, and view IDs.
- Maintain an in-memory spatial cache of active interactive elements updated on window state changes (`TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`), debounced (≥150ms) and executed on a background coroutine to ensure zero impact on main thread 60 FPS responsiveness.
- Automatically filter out non-interactive layout wrappers or invisible off-screen elements.

### R2. Smart Magnetic Snapping to On-Screen UI Elements
- Extend the cursor positioning engine so that beyond soft-keyboard keys, the cursor smoothly and magnetically snaps to nearby interactive screen elements (buttons, links, switches, list items) when:
  1. The cursor enters an element's capture radius (e.g. 35-45dp), and
  2. Cursor velocity slows below a fixation threshold.
- Provide clear visual reticle/highlight feedback on `CursorOverlayView` showing the currently locked/snapped target element.
- Seamlessly release magnetic lock when the user executes a deliberate head sweep away from the element.

### R3. Deictic & Named Voice Command Targeting
- Extend `VoiceCommandManager` to handle spatial and semantic voice targeting:
  - **Deictic Spatial Action**: Saying "Click this", "Select this", or "Yeh click karo" immediately triggers an action on the element currently under or closest to the cursor/gaze, bypassing the dwell timer.
  - **Named Element Action**: Saying "Click [Name]" or "Open [Text]" (e.g. "Click Search", "Click Settings", "Click Cancel", "Click Send") performs a fuzzy match against visible screen element labels/content descriptions and either navigates the cursor to it or executes the click.
  - **Contextual Scrolling**: Saying "Scroll down", "Scroll up" automatically identifies the active scrollable container (`isScrollable == true`) and dispatches smooth scroll gestures.

### R4. Privacy, Safety & Performance Guardrails
- **Sensitive Field Protection**: If a node has `isPassword == true` or matches password input types, completely exclude its text and content from indexing and speech matching.
- **Performance Isolation**: Screen tree parsing and spatial indexing must run asynchronously or offload to background coroutines, ensuring the 60 FPS head tracking loop, 1€ filter, and camera pipeline experience zero stutter.
- **Preservation of Existing Features**: Retain all existing capabilities: 10 cursor styles, vehicle vibration compensation, teeth pause/resume toggle, soft-keyboard key detector, in-app test suite, and 3 visual themes.

---

## Acceptance Criteria

### Semantic Screen Parsing (R1)
- [ ] Service extracts all interactive UI nodes from `rootInActiveWindow` with accurate screen coordinates.
- [ ] Nodes are stored in a debounced spatial cache that updates when screen content changes.
- [ ] Invisible or zero-width/height elements are pruned from the spatial index.

### Magnetic Snapping & Visual Reticle (R2)
- [ ] Cursor magnetically snaps to the center of nearby clickable buttons/elements when hovering near them.
- [ ] Snapped element bounds are visually highlighted on the overlay without obscuring target text.
- [ ] Fast head movements easily break the magnetic snap without sticky hesitation.

### Voice Targeting (R3)
- [ ] Voice command "Click this" triggers an immediate click on the focused/snapped UI element without waiting for dwell timer.
- [ ] Voice command "Click [Name]" successfully finds and activates matching UI elements by visible text or content description.
- [ ] Voice feedback HUD on `CursorOverlayView` displays recognized voice target status.

### Safety & Build Quality (R4)
- [ ] Password fields are completely excluded from text logging and indexing.
- [ ] Head-tracking cursor remains fluid at 60 FPS without frame drops during tree inspection.
- [ ] Existing unit tests pass and new tests verify spatial node caching and fuzzy text matching.
- [ ] Project builds cleanly via `./gradlew.bat assembleDebug` with zero errors.

---

## Verification Plan

### Automated Unit Tests
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest
```

### Full Build & APK Assembly
```powershell
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL` with 0 errors.

---
*Reference architecture: jarvis_architecture_design_document.md*


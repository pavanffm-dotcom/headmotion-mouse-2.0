# Phase 0 Forensic Report: Android Accessibility & Screen Perception

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Classification Mode:** FORENSIC AUDIT ONLY (Zero Production Source Code Modifications)  
**Date:** 2026-09-17  

---

## 1. Primary Perception Pipeline Trace: Accessibility Hierarchy

```
ANDROID PHYSICAL SCREEN
  │
  ▼
[Accessibility Events] onAccessibilityEvent(event: AccessibilityEvent?)
  ├── File: app/src/main/java/com/assistive/headmouse/service/HeadMouseAccessibilityService.kt:1524
  ├── Event Filter: TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOWS_CHANGED (Line 1528)
  └── Debounce: mainHandler.postDelayed(a11yParseDebounceRunnable, 250L) (Line 1531)
        │
        ▼
[Hierarchy Extraction] refreshSpatialCacheSync(): List<ScreenNode> (Line 1539)
  ├── Step 1: Inspect rootInActiveWindow (Line 1541)
  │     val isOurPackage = root?.packageName?.toString() == packageName (Line 1542)
  │     [DEFECT]: When Floating Arc Reactor or CursorOverlayView is active,
  │     rootInActiveWindow frequently returns com.assistive.headmouse or null!
  │
  ├── Step 2: Multi-Window Fallback (Line 1546-1558)
  │     val appWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
  │     [DEFECT]: If multiple application windows exist (e.g. Launcher underneath, Recents, Split-Screen),
  │     firstOrNull can pick an inactive or background application window instead of the foreground app!
  │
  ├── Step 3: AccessibilityTreeParser.parseTree(root) (Line 1561)
  │     File: app/src/main/java/com/assistive/headmouse/agent/perception/AccessibilityTreeParser.kt:24
  │     ├── Recursive traversal (depth capped at 40)
  │     ├── Bounds extraction: node.getBoundsInScreen(tempRect)
  │     ├── Filtering: width > 10, height > 10, visible within screen width/height (1080x2400)
  │     ├── Max node cap: collector.size < 120 (Line 76)
  │     └── Extracts: text, contentDescription, viewIdResourceName, className, isClickable
  │
  ├── Step 4: SpatialNodeCache.updateNodes(nodes) (Line 1567)
  │     Stores parsed nodes in memory.
  │
  └── Step 5: Fallback on Failure (Line 1596)
        return spatialNodeCache.getNodes()
        [DEFECT]: If extraction fails or throws, it returns the OLD cached nodes from the PREVIOUS screen!
```

---

## 2. The "Stale Screen / Wrong Screen" Mystery Solved

The user reported:
> *"Ab maan lo YouTube open bhi kar diya, main abhi current screen mein hoon. Usse bol raha hoon Shorts mein click karna hai, toh woh pata nahi kaunsi screen read karne lagta hai. Woh mera current screen ko read hi nahi kar pa raha hai na Shorts mein click kar raha hai."*

### Verified Root Causes in Code:

1. **Overlay Window Hijacking `rootInActiveWindow` (`HeadMouseAccessibilityService.kt:1541`)**:
   - `HeadMouseAccessibilityService` maintains two continuous system overlays: `CursorOverlayView` (type `TYPE_ACCESSIBILITY_OVERLAY` or `TYPE_APPLICATION_OVERLAY`) and `FloatingArcReactorOverlay`.
   - When the user speaks or taps, the system window manager often directs focus to the overlay window. As a result, `rootInActiveWindow` points to `com.assistive.headmouse` instead of `com.google.android.youtube`.
2. **First-Window Fallback Ambiguity (`HeadMouseAccessibilityService.kt:1548`)**:
   - `windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }` iterates the global window array.
   - On Android 13/14 (specifically realme UI / ColorOS on Android 14), the launcher or recent task window may appear earlier in the `windows` list than YouTube while YouTube is transitioning.
   - The parser reads the launcher's nodes instead of YouTube!
3. **Stale Cache Fallback (`HeadMouseAccessibilityService.kt:1596`)**:
   - If `refreshSpatialCacheSync()` fails to acquire an active root node during an activity transition (returning empty), line 1596 returns `spatialNodeCache.getNodes()`.
   - `spatialNodeCache` contains the **nodes from the home screen or settings from 20 seconds ago**!
   - When `JarvisBrain.kt:379` inspects `liveNodes`, it searches for "Shorts" inside the **home screen's stale nodes**, finds nothing, and does nothing!
4. **Coordinate Offset Error (`HeadMouseAccessibilityService.kt:903-904`)**:
   - When clicking:
     ```kotlin
     cursorView?.getLocationOnScreen(location)
     val windowOffsetY = location[1].toFloat()
     val exactPhysicalY = (y + windowOffsetY).coerceIn(...)
     ```
   - `ScreenNode.centerY` was extracted via `node.getBoundsInScreen(tempRect)`. It is **already in absolute screen coordinates**.
   - Adding `windowOffsetY` shifts the click down by the status bar/insets height, causing touches to miss the button completely.

---

## 3. Secondary Perception Pipeline Trace: Vision & Screenshots

```
ANDROID SCREEN
  │
  ▼
[MediaProjection API] JarvisScreenCaptureManager.kt:52
  ├── Authorized in MainActivity.kt via createScreenCaptureIntent()
  ├── VirtualDisplay captures frames into ImageReader (720p) (Line 55)
  └── captureScreenshotJpeg(): Acquires frame, compresses to JPEG byte array (Line 78)
        │
        ▼ [CRITICAL DISCONNECT IN CODE]
  ├── Where is captureScreenshotJpeg() called?
  │     └── ONLY in JarvisBrain.kt:1565 (planNextMissionStep)
  │
  └── Where is planNextMissionStep called?
        └── NOWHERE in the entire codebase!
```

**Forensic Verdict:**
- `JarvisScreenCaptureManager` is fully operational in isolation, but it is **100% disconnected** from `AgentOrchestrator`, `DynamicPlanner`, and `ActionExecutor`.
- The autonomous agent is currently **100% blind** to graphical/visual pixels. It relies exclusively on the accessibility tree.

---

## 4. Does the Post-Action Screen Reach the Next AI Decision?

**NO.** This is the foundational flaw in the current architecture:

```
[Action Injected] (e.g. OPEN_APP YouTube)
       │
       ▼
[Wait for UI] SmartWaiter.waitForScreenChange()
       │
       ▼
[Post Screen Observed] ScreenObserver.observeScreen()
       │
       ▼
[Verification Only] ScreenObserver.verifyState(step, observation)
       │
       ├─► IF TRUE:  objective.currentStepIndex++  (NO CALL TO LLM)
       │             Next action is taken from the PRE-PLANNED STATIC ARRAY!
       │
       └─► IF FALSE: ReplanningEngine.replan()     (NO CALL TO LLM)
                     Calls static heuristic rules! If none match, ABORTS!
```

The post-action observation is used **strictly for local boolean verification**. It is **never serialized or fed back into an LLM prompt** to solicit the next dynamic action.

# WidgetLeaf Non-Negotiable Technical Invariants (`_agents/INVARIANTS.md`)

> [!CAUTION]
> **DO NOT VIOLATE THESE INVARIANTS:** Every rule below guards against a subtle Android Desktop, Jetpack Compose, or Linux VM pitfall verified on real hardware. If you discover a new platform quirk or gesture invariant, add it here before finishing your task.

---

## 1. Compose Pointer Input & Drag Gesture Invariants

### INV-1: Conditional `Modifier.pointerInput` Branches MUST Include the Mode Key
- **Why:** In Jetpack Compose, `Modifier.pointerInput(key1) { ... }` creates a `SuspendPointerInputElement` whose `equals()` compares **only its keys (`key1`, `key2`, `keys`)**, never the lambda body.
- **Rule:** In `WidgetGridPage.kt`, when switching a placed widget between `isEditing == true` (`detectDragGestures` for moving) and `isEditing == false` (right-click / long-press detector), **always pass `isEditing` (or `true`/`false`) as a second key**:
  ```kotlin
  // Editing branch:
  .pointerInput(widget.appWidgetId, true) { detectDragGestures(...) }
  // Non-editing branch:
  .pointerInput(widget.appWidgetId, false) { awaitPointerEventScope { ... } }
  ```
  If both branches use `.pointerInput(widget.appWidgetId)`, Compose will not restart the pointer input coroutine when `isEditing` transitions from `false` to `true`, breaking widget movement on real devices.

### INV-2: Place `.pointerInput` BEFORE Dynamic Drag `.offset { ... }`
- **Why:** `detectDragGestures` computes `dragAmount = change.position - change.previousPosition` in the local coordinate space of the `pointerInput` modifier node. If `.offset { IntOffset(moveDragX, moveDragY) }` is placed *before* `.pointerInput`, shifting the box by `moveDragX` on frame $N$ shifts the local pointer coordinate in the opposite direction on frame $N+1$, causing severe oscillation and cancelling out the drag distance.
- **Rule:** Always place the static grid position `.offset(x = leftDp, y = topDp)` and `.pointerInput(...)` **before** the live drag visual translation `.offset { IntOffset(moveDragX.roundToInt(), moveDragY.roundToInt()) }`.
- Similarly, in `WidgetEditResizeFrame` (`GlassSurfaceBox.kt`), apply `.offset { IntOffset(...) }` only to the inner visual indicator pill of each resize handle, keeping the outer `20.dp` hit-target `Box` stationary during the drag.

### INV-3: `DeckAppWidgetHostView` Must Not Steal Pointer Events in `EDIT_MODE`
- **Why:** Android `AppWidgetHostView` (`AndroidView`) sits inside the same parent `Box` as Compose's `WidgetEditResizeFrame` and can intercept touch/mouse down events at the `ViewGroup` level before Compose sees them.
- **Rule:** `DeckAppWidgetHostView.dispatchTouchEvent` MUST immediately return `false` whenever `interactionMode == InteractionMode.EDIT_MODE`.

### INV-4: Entering or Operating in Widget Edit Mode Must Never Open the Canvas Context Menu
- **Why:** Right-clicking / two-finger clicking a widget should only enter `EDIT_MODE` for that widget (`Reconfigure | Remove` + resize handles), never simultaneously pop up the `Widgets / Overlay Settings` context menu.
- **Rule:**
  - `DeckViewModel.enterEditMode(appWidgetId)` must clear `contextMenuPosition = null`.
  - In `WidgetGridPage.kt`, the root canvas gesture detector must check `findWidgetAtPosition(downPos)` and `currentEditingWidgetId == null` before calling `onOpenContextMenu`.
  - Keep the `Reconfigure | Remove` pill centered (`Alignment.Center`) inside `WidgetEditResizeFrame` so it never extends above the top of top-row widgets or overlaps the top status bar.

---

## 2. Overlay Dismissal & Lifecycle Invariants

### INV-5: Adding or Removing a Widget Must Never Dismiss the Overlay
- **Rule:** Removing a widget (`onRemoveWidget`) or adding a widget (`onAddWidgetAutoPlace`) must keep the WidgetLeaf overlay open.
- **Dismissal Triggers:** The overlay should dismiss ONLY when:
  1. Left-clicking / tapping empty background when not in edit mode or right after a context-menu/widget action (`350ms–400ms` guard window).
  2. Pressing `Escape` or any key that is not a WidgetLeaf navigation/UI key (`Left`, `Right`, `Up`, `Down`, `PageUp`, `PageDown`, `MoveHome`, `MoveEnd`, `1..9`, `0`, `Tab`, `Enter`, `Delete`, ` ForwardDel`, or pure modifier keys) while no dialog/sheet/inline editor is active.

### INV-6: Launch on Startup Runs Strictly in the Background
- **Rule:** `BootCompletedReceiver` (`RECEIVE_BOOT_COMPLETED`) must ONLY restore background triggers (`StatusBarTriggerManager.syncTopBarNotification` and `WidgetDeckTileService.requestListeningState`). It must **never** launch `WidgetDeckActivity` on boot.

---

## 3. Desktop Top Bar (BentoBar Coexistence) & Taskbar Invariants

### INV-7: Top Bar Accessibility Service Must NEVER Set `flagRequestAccessibilityButton`
- **Why:** Setting `android:accessibilityFlags="flagRequestAccessibilityButton"` in `res/xml/top_bar_accessibility_service.xml` causes Android Desktop to display a floating system accessibility Dock/button on the side of the screen instead of just allowing our custom top-bar leaf view.
- **Rule:** `res/xml/top_bar_accessibility_service.xml` must only use `flagDefault|flagRetrieveInteractiveWindows|flagRequestFilterKeyEvents`.
- `WidgetLeafTopBarService` renders its own `28×24dp` leaf icon inside the top status bar (`y = 0`, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, `FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS`) and uses `StatusBarSpaceScanner` to coexist with `BentoBar` (`com.bentobar` / `bento`).

### INV-8: Taskbar Running Icon vs. Top Bar Pinning
- **Rule:** `OverlaySettings.effectiveShowInRunningTaskbar` returns `!pinToTopBar && showInRunningTaskbarRight`. Whenever `pinToTopBar` is enabled, `TaskbarPlacementManager` sets `setExcludeFromRecents(true)` so the running icon does not duplicate in the right taskbar.

---

## 4. Build & Test Environment Invariants (Android Linux VM)

### INV-9: Redirect Gradle Cache to `$HOME/.cache` & Split Debug/Release Invocations
- **Rule:** Always pass `--project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"` to `./gradlew`.
- Run `testDebugUnitTest assembleDebug` and `assembleRelease` in separate `./gradlew` invocations so the Gradle Daemon (`384 MiB` MaxMetaspaceSize) does not run out of Metaspace mid-build.

### INV-10: Detach `ComposeView` in Robolectric Activity Tests Before `destroy()`
- **Why:** In Robolectric, destroying a `ComponentActivity` (`WidgetDeckActivityTest`) without detaching its `ComposeView` and idling the main looper can leave a stale `AndroidComposeView` registered in `ComposeRootRegistry`, causing subsequent `createComposeRule()` tests (`DeckScreenComposeTest`) in the same JVM to fail with `AppNotIdleException`.
- **Rule:** Every test in `WidgetDeckActivityTest` must clean up via `tearDownController(controller)` (which calls `activity.setContentView(View(activity))` and `Shadows.shadowOf(Looper.getMainLooper()).idle()` before `controller.pause().stop().destroy()`).

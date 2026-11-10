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

### INV-11: Edit-Mode Move Overlay Sits Above `AndroidView` & Root Detector Must Not Consume Widget Touches
- **Why:** In Jetpack Compose, an `AndroidView` (`DeckAppWidgetHostView` or child views in the remote widget layout) claims touch streams when `dispatchTouchEvent` returns true, causing parent `detectDragGestures` to see consumption and immediately cancel. Furthermore, if the root canvas gesture detector marks `event.changes.forEach { it.consume() }` upon entering edit mode on a widget, subsequent move events in that gesture are killed.
- **Rule:**
  1. In `WidgetGridPage.kt`, placed widgets must render their editing surface in a dedicated hit layer (`testTag("placed_widget_$id")`) drawn **above** the `AndroidView` in z-order, with `pointerInput(widget.appWidgetId, true) { detectDragGestures(...) }` residing on an un-translated static Box (respecting INV-2).
  2. `WidgetEditResizeFrame` resides inside this editing layer, translated by live visual drag offset so handles and action pill follow the drag, while edge resize handles consume touches before the move drag.
  3. When long-press timeout triggers on a placed widget in the root canvas detector, it must **never** consume subsequent changes or set `contextMenuOrEditTriggered = true`, allowing the user to seamlessly drag the widget immediately without lifting their finger.
  4. `DeckAppWidgetHostView` must reset `isLongPressTriggered = false` on `interactionMode = EDIT_MODE`, `ACTION_UP`, and `ACTION_CANCEL`, ensuring it never gets stuck in `return true`.

### INV-12: Desktop Cascade Displacement & Drag Preview Invariants
- **Why:** In desktop-style cascade displacement, dragging a widget onto occupied space must displace other widgets without altering any widget's size. Displaced widgets must animate smoothly during drag hover previews, revert cleanly on hover-out/cancel, and never produce 1-frame snap-back flicker or pointer oscillation.
- **Rule:**
  1. **Anchor Dragged Widget Base Offset:** During a drag session, the dragged widget's static base offset `(leftDp, topDp)` MUST stay anchored at `dragStartCell` throughout the entire drag. It must NEVER jump to the speculative preview or hovered cell while dragging, as shifting the static base offset mid-drag violates INV-2 and breaks pointer delta tracking.
  2. **Synchronous Atomic Reset on Commit:** In `onDragEnd` when committing, `moveDragX`, `moveDragY`, and `dragStartCell` are reset in the same frame that `commitWidgetDrag` publishes the committed cell to `uiState.widgets`. This eliminates 1-frame snap-back flicker where the widget would otherwise snap to the old start position before jumping to the committed position.
  3. **Displaced Spring Slide vs. Dragged Snap:** Non-dragged widgets animate position changes with spring physics (`PAGE_FLIP_SPRING_DAMPING`, `PAGE_FLIP_SPRING_STIFFNESS`) keyed by `key(widget.appWidgetId)`. When displaced by hover preview or when reverting on hover-out/cancel, widgets slide smoothly with spring physics. The dragged widget uses `snap<Dp>()` on drop (`isJustDropped`) because its visual placement was already continuously aligned with the pointer.
  4. **Drag Session Cleanup & Session Token Guard:** `cancelWidgetDrag()` is called on `exitWidgetEditMode`, `handleEscapeKey`, `onOverlayShown`, `removeWidget`, and user page navigation (`navigatePreviousPage`, `navigateNextPage`, `selectPage`, `jumpToPageByNumberKey`). Edge drag transitions remain uninterrupted. Debounced preview computation is strictly guarded by `dragSessionToken` and `current.draggingWidgetId == appWidgetId` to eliminate late preview writes.
  5. **Strict Move vs. Resize Separation:** `moveWidget` (and drag moves) strictly preserves `spanX`, `spanY`, `minSpanX`, `minSpanY`, `maxSpanX`, and `maxSpanY`. Widgets NEVER resize as a result of being moved or displaced. Only `resizeWidget` can modify spans.
  6. **Provider Resize Limits & Resize Mode:** `resizeWidget` and `WidgetEditResizeFrame` clamp spans to provider `minSpan` (from `minResizeWidth/Height` or `minWidth/Height`) and explicit `maxSpan` (ONLY when provider declares non-zero `maxResizeWidth/Height`; otherwise max = grid columns/rows). `resizeMode` strictly controls handle visibility: `RESIZE_NONE` renders no handles; `RESIZE_HORIZONTAL` renders only left/right handles; `RESIZE_VERTICAL` renders only top/bottom handles; `RESIZE_BOTH` renders all 4.
  7. **Resize Handle Hit Area Restriction:** In `WidgetEditResizeFrame` (`GlassSurfaceBox.kt`), perimeter resize handles must be restricted to centered pill zones (~64×20dp and 20×64dp) with modest hit padding rather than full-length edges, preventing accidental resize hijacking when grabbing near a widget border to move it.

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

### INV-8: Top Bar As Primary Entry Point & Taskbar Recents Exclusion
- **Rule:** WidgetLeaf's primary desktop entry point is the top status bar leaf icon (`WidgetLeafTopBarService`, positioned at `TopBarPosition.LEFT` or `RIGHT`). To prevent visual clutter and duplicate entry points on desktop environments, `WidgetDeckActivity` is ALWAYS excluded from recents (`task.setExcludeFromRecents(true)` via `TaskbarPlacementManager`). It never appears in the right-side running taskbar. The legacy taskbar settings have been removed in favor of a single setting: "Top Bar Position: Left | Right".

---

## 4. Build & Test Environment Invariants (Android Linux VM)

### INV-9: Redirect Gradle Cache to `$HOME/.cache` & Split Debug/Release Invocations
- **Rule:** Always pass `--project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"` to `./gradlew`.
- Run `testDebugUnitTest assembleDebug` and `assembleRelease` in separate `./gradlew` invocations so the Gradle Daemon (`384 MiB` MaxMetaspaceSize) does not run out of Metaspace mid-build.

### INV-10: Detach `ComposeView` in Robolectric Activity Tests Before `destroy()`
- **Why:** In Robolectric, destroying a `ComponentActivity` (`WidgetDeckActivityTest`) without detaching its `ComposeView` and idling the main looper can leave a stale `AndroidComposeView` registered in `ComposeRootRegistry`, causing subsequent `createComposeRule()` tests (`DeckScreenComposeTest`) in the same JVM to fail with `AppNotIdleException`.
- **Rule:** Every test in `WidgetDeckActivityTest` must clean up via `tearDownController(controller)` (which calls `activity.setContentView(View(activity))` and `Shadows.shadowOf(Looper.getMainLooper()).idle()` before `controller.pause().stop().destroy()`).

---

## 5. Widget Host View & Responsive Sizing Invariants

### INV-13: `DeckAppWidgetHostView` Padding Must Be Zero & Reported Sizes Must Match Actual Layout Bounds Exactly
- **Why:** On Android API 31+ (Android 12+), `AppWidgetHostView.setAppWidget` applies framework default padding (`getDefaultPadding()`, ~8–16dp per edge) and computes the available RemoteViews layout size as `(measuredWidth - paddingLeft - paddingRight) / density`. If the host view retains framework default padding, `RemoteViews.findBestFitLayout` evaluates an available canvas smaller than the dimensions reported via `updateAppWidgetSizes` (`OPTION_APPWIDGET_SIZES`), causing responsive widgets (such as Google Weather forecast 4×3+) to determine that no responsive layout fits and fall back to `findSmallestRemoteView()`, truncating typography and content.
- **Rule:**
  1. `DeckAppWidgetHostView` MUST force padding to zero in `init`, override `setPadding` and `setPaddingRelative` to delegate strictly to `super.setPadding(0, 0, 0, 0)` / `super.setPaddingRelative(0, 0, 0, 0)`, and re-assert zero padding after `super.setAppWidget(...)`.
  2. The `AndroidView` inside `HostedAppWidgetContent` must measure with exact constraints equal to the Bento grid cell box (`DesktopGridProfile.widthDpForSpan` / `heightDpForSpan`) without inner/outer padding or borders causing dp discrepancies. Reported dp == actual view dp.
  3. `DeckViewModel` MUST synchronize widget sizes via `syncWidgetSizes` on the FIRST `widgetsFlow` emission and in `onOverlayShown()`, and on subsequent emissions only when a widget is new, its span changed, or the grid profile changed, avoiding redundant gateway size calls.

### INV-14: Grid Width Must Stretch Full-Width With Mathematically Symmetric Margins
- **Why:** Hardcoding an artificial max canvas width cap or asymmetric margins causes the right side of the desktop canvas to become a dead zone where widgets cannot be dragged or placed, while left margins sit tightly against the screen edge. Furthermore, relying purely on display density or unadjusted window metrics can mismatch Compose layout on scaled displays.
- **Rule:**
  1. `DesktopGridProfileResolver` MUST stretch columns and cell width to use the full available width, computing symmetric horizontal margins as `(availableWidth - totalGridWidth) / 2f` so `leftMargin == rightMargin` exactly across all displays (portrait, 1024×640, 1280×800, 1920×1080, and ultrawide).
  2. `WidgetDeckOverlayScreen` uses `BoxWithConstraints` to dynamically provide true Compose dp width/height to `updateGridProfile`.
  3. `DeckViewModel.updateGridProfile` MUST keep existing widget positions stationary when columns grow, and reflow out-of-bounds widgets for display only when columns/rows shrink without writing to the repository, allowing original positions to restore when the grid expands; the persisted layout is modified only on explicit user commit (move, resize, drop, remove). Degenerate measurements (<= 10dp or 0) and unchanged profiles are ignored. Responsive sizes are re-synced for displayed widgets.


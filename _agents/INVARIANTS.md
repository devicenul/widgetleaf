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

### INV-12: Desktop Cascade Displacement, Drag & Resize Preview Invariants
- **Why:** In desktop-style cascade displacement, dragging a widget to move or resize it onto occupied space must displace other widgets without altering any displaced widget's size. Displaced widgets must animate smoothly during drag/resize hover previews, revert cleanly on hover-out, resize retraction, or cancel/Escape, and never produce 1-frame snap-back flicker or pointer oscillation.
- **Rule:**
  1. **Anchor Dragged/Resized Widget Base Offset & Spans:** During a move or resize session, the target widget's static base offset `(leftDp, topDp)` and base spans `(spanX, spanY)` MUST stay anchored at the gesture start snapshot throughout the entire drag. It must NEVER jump to speculative preview bounds mid-gesture, as shifting the static base offset or container dimensions violates INV-2 and breaks pointer delta tracking and hit targets.
  2. **Synchronous Atomic Reset on Commit:** In `onDragEnd` when committing, gesture offsets (`moveDragX`, `moveDragY`, handle deltas `topDragY`, `bottomDragY`, `leftDragX`, `rightDragX`), start bounds, and local session flags are reset in the same frame that `commitWidgetDrag` / `commitWidgetResize` publishes the committed cell and spans to `uiState.widgets`. This eliminates 1-frame snap-back flicker where the widget would otherwise snap to the old start position/size before jumping to the committed state.
  3. **Displaced Spring Slide vs. Dragged/Resized Snap:** Non-dragged widgets animate position changes with spring physics (`PAGE_FLIP_SPRING_DAMPING`, `PAGE_FLIP_SPRING_STIFFNESS`) keyed by `key(widget.appWidgetId)`. When displaced by hover preview or when reverting on hover-out/retraction/cancel, widgets slide smoothly with spring physics. The dragged or resized widget uses continuous offset / live outline preview during the gesture, snapping cleanly on drop.
  4. **Drag/Resize Session Cleanup & Session Token Guard:** `cancelWidgetDrag()` (and `cancelWidgetResize()`) is called on `exitWidgetEditMode`, `handleEscapeKey`, `onOverlayShown`, `removeWidget`, and user page navigation (`navigatePreviousPage`, `navigateNextPage`, `selectPage`, `jumpToPageByNumberKey`). Debounced preview computation (250 ms) is strictly guarded by `dragSessionToken` and active session id (`draggingWidgetId == appWidgetId` or `resizingWidgetId == appWidgetId`) to eliminate late preview writes.
  5. **Strict Move vs. Resize Separation:** `moveWidget` (and drag moves) strictly preserves `spanX`, `spanY`, `minSpanX`, `minSpanY`, `maxSpanX`, and `maxSpanY`. Displaced widgets NEVER change span. Only `commitWidgetResize` / `resizeWidget` modifies the resized widget's spans.
  6. **Provider Resize Limits & Resize Mode:** `commitWidgetResize`, `onWidgetResizeBoundsChanged`, and `WidgetEditResizeFrame` clamp spans to provider `minSpan` (from `minResizeWidth/Height` or `minWidth/Height`) and explicit `maxSpan` (ONLY when provider declares non-zero `maxResizeWidth/Height`; otherwise max = grid columns/rows). `resizeMode` strictly controls handle visibility: `RESIZE_NONE` renders no handles; `RESIZE_HORIZONTAL` renders only left/right handles; `RESIZE_VERTICAL` renders only top/bottom handles; `RESIZE_BOTH` renders all 4 edge handles AND 4 diagonal corner handles.
  7. **Perimeter Resize Handles & Diagonal Corner Handles:**
     - Perimeter edge handles are restricted to centered pill zones (~64×20dp and 20×64dp) with native ↔ / ↕ resize cursors.
     - Four corner handles (~28dp square hit target with diagonal resize cursors `TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW` / `TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW`) are rendered ONLY when `resizeMode == RESIZE_BOTH`.
     - Corner handles resize both axes simultaneously anchoring the opposite corner (top-left anchors bottom-right, top-right anchors bottom-left, bottom-left anchors top-right, bottom-right anchors top-left), snap to cells, and clamp to grid and provider limits.
     - Corner handle hit areas take priority over edge pills and the move surface while the centered Reconfigure | Remove pill remains accessible.
  8. **Live Cascade Preview During Resize:** During a resize drag, if the proposed bounds stay unchanged for 250 ms, displaced widgets animate to cascade positions resolved by `CascadeDisplacementResolver`. If proposed bounds return to a non-overlapping or original size, the preview reverts immediately. Releasing commits atomically with new pages and page pruning; cancelling or pressing Escape reverts all widgets to the gesture snapshot.

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

### INV-9: 4 GB Linux VM RAM Guardrails & Cache Redirection
- **Why:** The Linux VM inside the Android Terminal environment has a fixed default total memory of ~3.9 GB (~4 GB). If multiple Gradle processes, parallel workers, or unbounded test forks run simultaneously, memory drops below ~50 MB, causing kernel `kswapd0` thrashing and multi-hour unrecoverable hangs.
- **Rule:**
  1. **Cache Redirection:** Always pass `--project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"` to `./gradlew`.
  2. **Worker & Concurrency Limits:** Always append `--no-parallel --max-workers=1` and `-Dorg.gradle.jvmargs="-Xmx640m -XX:MaxMetaspaceSize=384m"`.
  3. **Test Process Bounds:** Keep test tasks configured with `maxParallelForks = 1` and capped `jvmArgs("-Xmx512m", "-XX:MaxMetaspaceSize=256m")` (or `maxHeapSize = "512m"`).
  4. **Stop Daemons Between Heavy Runs:** Always run `./gradlew --stop` (with the project cache dir) before heavy builds or test suites to release expired daemons.
  5. **Targeted Iteration:** While iterating, run only the relevant test classes using `--tests <pattern>`, reserving full-suite runs and JaCoCo reports for final verification.
  6. **Split Invocations:** Execute `testDebugUnitTest jacocoTestReport assembleDebug` and `assembleRelease` in separate `./gradlew` commands to prevent Metaspace and heap exhaustion.
  7. **Hang Recovery:** If any Gradle execution shows zero progress for >15 minutes, check `free -m` and kill runaway Gradle/JVM processes immediately.

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
  3. `DeckViewModel.updateGridProfile` MUST keep existing widget positions stationary when columns grow, and reflow out-of-bounds widgets for display only when columns/rows shrink without writing to the repository, allowing original positions to restore when the grid expands.
  4. **Ground-Truth Raw Layout Preservation on User Commits:** Committing an ADD (`addWidgetAutoPlace`, `dropCatalogWidgetAtCell`), configure result (`onConfigureWidgetResult`), move, or resize MUST always compute against and persist to the RAW persisted layout (`rawPersistedWidgets`), never saving reflowed positions or spans of other widgets into Room. Adding a new widget evaluates available spots against `rawPersistedWidgets` on the full/last-good profile (`lastGoodProfile`), guaranteeing that existing widgets never have their spans shrunk or positions altered by reflow, completely preventing overlaps. Moving and resizing also cascade against `rawPersistedWidgets` using `effectiveProfile`, strictly preserving all un-acted widgets' raw spans and coordinates. Degenerate measurements (<= 10dp or 0) and unchanged profiles are ignored. Responsive sizes are re-synced for displayed widgets.

### INV-15: Edit-Mode Drag Layer Must Sit Above `AnimatedContent`
- **Why:** In Jetpack Compose, `AnimatedContent` disposes exiting page composable nodes and cancels all coroutines inside their `Modifier.pointerInput` scopes. If widget move drags and resize frames live inside page composables (`WidgetGridPage`), mid-drag page flips instantly abort the active pointer coroutine and cancel the drag.
- **Rule:** The edit-mode move surface, resize frame, and dragged visual are hoisted into an un-animated sibling overlay (`WidgetEditDragLayer`) rendered in `WidgetDeckOverlayScreen` strictly above `AnimatedContent` (Layer 3.5). `WidgetGridPage` skips rendering any widget whose `appWidgetId == editingWidgetId`. Drag gestures remain rock-solid across page transitions, satisfying INV-2 and INV-12.

### INV-16: Single-Parent Enforcement & View Cache Safety for `DeckAppWidgetHostView`
- **Why:** An Android `View` can have at most one parent `ViewGroup`. When transitioning into/out of edit mode, the widget's `DeckAppWidgetHostView` moves between `WidgetGridPage` and `WidgetEditDragLayer`. If the host view is reused or cached, Compose factory composition timing can attach the view to the incoming container before the outgoing container's disposal pass completes. If the outgoing `onRelease` blindly calls `(hostView.parent as? ViewGroup)?.removeView(hostView)`, it strips the view from the *new* container, causing the widget to show as a blank outline during edit mode/drag and disappear entirely after drop/exit edit mode.
- **Rule:**
  1. `WidgetDeckHost.createHostView` MUST cache `DeckAppWidgetHostView` by `appWidgetId` and explicitly detach the view from any existing parent (`(view.parent as? ViewGroup)?.removeView(view)`) before returning it.
  2. `DeckWidgetHostContainer` wraps `DeckAppWidgetHostView` in a lightweight `FrameLayout` container. In `onReset` and `onRelease`, it MUST only detach the host view if it is strictly owned by *this* container (`detachHostViewIfOwned(container, hostView)` where `hostView.parent === container`). It must NEVER blindly detach a host view that has already been adopted by another container.
  3. `WidgetGridPage` MUST skip composing any widget whose `appWidgetId == editingWidgetId || appWidgetId == draggingWidgetId`, ensuring exactly one `AndroidView` per `appWidgetId` is active at any time.
  4. `WidgetDeckHost` clears cached views on `deleteAppWidgetId` and `clearViews`.

### INV-17: In-Drag Edge Dwell Page Switching Semantics
- **Why:** Abruptly bumping a widget across pages on drop without visual feedback is jarring and prevents precise placement. Mid-drag edge dwell allows users to preview the destination page and its cascade displacement before committing.
- **Rule:**
  1. Holding a dragged widget at the screen/grid edge for 500 ms flips the active page mid-drag.
  2. The dwell timer repeats every 500 ms if held continuously, and resets immediately upon leaving the edge zone. Minor micro-movements within the edge zone do not reset the timer.
  3. Left dwell stops at Page 0; right dwell past the last page synthesizes speculative pages (shown in `PageIndicatorHud` via `uiState.effectivePages`).
  4. During page flips, the dragged widget remains anchored under the pointer; live cascade preview (250 ms debounce) runs on the newly active page.
  5. Dropping commits atomically via `CascadeDisplacementResolver` and prunes trailing empty/speculative pages. Cancelling (or `Escape`) reverts everything to the drag-start snapshot and restores the original page index. The old drop-at-edge bump path is removed.

### INV-18: Swipe Gesture Routing, Trackpad Classification & Hotkey Toggle Semantics
- **Why:** Full-screen overlay applications on Android Desktop must support natural touch and trackpad navigation (page flips and dismissals) while coexisting with interactive widgets (e.g. scrollable calendars, carousels), excluding traditional mouse wheels, disabling gestures during widget edits, and ensuring hotkey presses toggle cleanly without repeat races.
- **Rule:**
  1. **Touch Swipes (`PointerEventPass.Initial`):** 1-finger horizontal swipes flip pages (swipe left → next, swipe right → previous). 1-finger vertical swipes (up or down) dismiss the overlay pane. Both require 1.2x dominant axis locking. Over widgets, gestures query `canChildScrollHorizontally` or `canChildScrollVertically`; if the child widget can scroll in the gesture direction, the gesture is not claimed. If the widget cannot scroll (or over empty background), the gesture claims the pointer stream, consumes subsequent events in Initial pass, and invokes `cancelChildTouches()`.
  2. **Page Boundaries:** Swipe page navigation is strictly non-wrapping (stops at Page 0 and last page; never wraps around and never synthesizes speculative pages).
  3. **Trackpad vs. Mouse Wheel Distinction:** Regular mouse wheels (`TOOL_TYPE_MOUSE`, discrete integer scroll steps) must NEVER page or dismiss. Trackpads are recognized via Android 14+ `CLASSIFICATION_TWO_FINGER_SWIPE` / `MULTI_FINGER_SWIPE`, non-zero `AXIS_GESTURE_SCROLL_X_DISTANCE` / `Y_DISTANCE`, `SOURCE_TOUCHPAD`, or `TOOL_TYPE_FINGER`. Trackpad deltas follow natural scrolling and are accumulated with dominant-axis locking in `TrackpadSwipeAccumulator`.
  4. **Edit Mode Guard:** All touch and trackpad swipe gestures are strictly disabled whenever any widget is in edit mode (`editingWidgetId != null`).
  5. **Consistent Hotkey Toggle & Debounce:** Pressing the global hotkey combo (default `Alt + W`), clicking the top status bar leaf pill, or triggering `ToggleDeckShortcutActivity` toggles the overlay: visible → dismiss; hidden → open. Debounces key repeats (350 ms). Non-navigation key dismissal is suppressed during the initial 300 ms after creation and on the hotkey keyCode itself, preventing key release races from dismissing newly opened windows.

### INV-19: New Widget Non-Displacement Placement Rule
- **Why:** Adding a new widget to an existing page must never unexpectedly shove, cascade, or scatter existing widgets across columns or pages. Users expect their arranged layouts to remain stable when exploring or placing new widgets.
- **Rule:**
  1. **Zero Displacement on Add:** When adding a NEW widget (catalog picker auto-place via `DeckViewModel.addWidgetAutoPlace` or catalog drag-drop via `DeckViewModel.dropCatalogWidgetAtCell`), if it cannot fit in free space on the target page, existing widgets MUST NOT be displaced.
  2. **Deterministic Page Search Order:** The system searches for free space in the following order:
     - Active / target page first;
     - Subsequent pages in order (`(activePage + 1) until pages.size`);
     - A brand new page appended at the end (`index = pages.size, cell = 0, 0`).
  3. **Column-Major Search Order:** Within every page, available spots are evaluated in COLUMN-MAJOR order (downward within column $x$, then next column $x+1$ right), consistent with `CascadeDisplacementResolver`.
  4. **Occupied Drop Fallback:** Explicit catalog drag-drop onto an occupied cell never displaces existing widgets; if the dropped cell overlaps an existing widget, it places into the first free spot on that page, else the next page with space, else a new page.
  5. **Auto-Navigation:** The overlay automatically navigates to the page where the new widget was placed (`activePageIndex = candidate.pageIndex`) so the user immediately sees it.
  6. **Existing Widgets Cascade:** Moving and resizing *existing* placed widgets continues to use `CascadeDisplacementResolver`.

### INV-20: Widget Configuration Flow Isolation via Trampoline Task (`WidgetConfigureTrampolineActivity`)
- **Why:** On Android Desktop environments (Android 14/15/16 Desktop Mode, ChromeOS ARC-VM, Googlebook), launching a 3rd-party widget configuration activity (e.g. Clock or nzb360) places the external activity in a desktop freeform window container with caption and window controls. If launched directly from `WidgetDeckActivity` or within its task, the WindowManager irrevocably transitions the entire task into `WINDOWING_MODE_FREEFORM`. Window-level re-assertions (`setLayout(MATCH_PARENT)`, window flags, or `requestFullscreenMode`) are ignored or rejected by desktop mode, leaving WidgetLeaf trapped in a bordered floating window upon return.
- **Rule:**
  1. **Dedicated Trampoline Activity:** All 3rd-party widget configuration flows (both adding new configurable widgets and reconfiguring existing widgets) MUST be delegated to `WidgetConfigureTrampolineActivity`, an invisible activity declared with an isolated task affinity (`android:taskAffinity="io.devnullapps.widgetleaf.configure"`), `android:excludeFromRecents="true"`, and `android:theme="@style/Theme.WidgetLeaf.Trampoline"`.
  2. **Overlay Exit Before External Launch:** When configuration is required, `WidgetDeckActivity` starts `WidgetConfigureTrampolineActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK` and explicit `ActivityOptions` (`launchDisplayId`, API 34+ `setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`), and immediately finishes via `finishWithoutAnimation()`. This ensures the overlay task is cleanly destroyed rather than degraded to freeform mode.
  3. **Direct Placement Finalization & Target Page Propagation:** `WidgetConfigureTrampolineActivity` receives the pending `appWidgetId`, `candidateWidget`, `isReconfigure`, and `originPageIndex` (`EXTRA_ORIGIN_PAGE_INDEX`). It starts the external configure activity for result (`startAppWidgetConfigureActivityForResult`).
     - On `RESULT_OK`: for a new widget, it transactionally persists the candidate to the Room database via `repository.replaceDeckState`, updates `lastViewedPageIndex = candidate.pageIndex`, and relaunches the overlay targeting the placed page (`targetPageIndex = candidate.pageIndex`). For reconfiguring an existing widget, it relaunches the overlay targeting the widget/origin page (`targetPageIndex = candidateWidget?.pageIndex ?: originPageIndex`).
     - On `RESULT_CANCELED`: for a new widget, it deletes the allocated `appWidgetId` via `widgetHostGateway.deleteAppWidgetId`, does not write to the repository, and relaunches the overlay targeting the page that was active when adding started (`targetPageIndex = originPageIndex`). For reconfiguring, it returns to `originPageIndex`.
  4. **Clean Fullscreen Overlay Relaunch with One-Shot Target Page Override:** The overlay is relaunched using `ActiveDisplayLaunchHelper.launchOverlay(context, ACTION_TOGGLE_DECK, targetPageIndex)`, attaching `EXTRA_TARGET_PAGE_INDEX` to the launch intent with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`. `WidgetDeckActivity.onCreate()` reads `targetPageOverride`, immediately removes the extra from the intent so subsequent normal opens continue to follow user settings (`openOnPageOne`), and passes `targetPageOverride` to `viewModel.onOverlayShown(targetPageOverride)`.
  5. **Play Protect / Policy Hygiene:** No hidden reflection APIs (such as `@hide setLaunchWindowingMode`) are used.
  6. **Overlay Presentation & Diagnostics:** `WidgetDeckActivity.reassertOverlayPresentation()` enforces `MATCH_PARENT`, `FLAG_LAYOUT_IN_SCREEN`, `FLAG_LAYOUT_NO_LIMITS`, blur, and taskbar exclusion. In debug builds, `onResume()` logs multi-window mode, window bounds, and display metrics for diagnostics.

---

## 6. Page Canvas Gating & Transition Semantics

### INV-21: Initial Deck Page Resolution, Ready Gating & Programmatic Snap Semantics
- **Why:** On overlay launch when restored to a non-zero page (e.g. `openOnPageOne = false`, `lastViewedPageIndex = 2`, or configure relaunch target page), initializing `activePageIndex = 0` and resolving DataStore/Room settings asynchronously caused `AnimatedContent(targetState = activePageIndex)` to compose Page 0 on frame 0 and then visibly animate a 280ms spring slide to the target page, or clamp to 0 if initial single-page flows arrived early.
- **Rule:**
  1. **Ready Gate:** `DeckUiState` includes `isReady: Boolean = false`. In `WidgetDeckOverlayScreen`, Layer 3 `AnimatedContent` (page canvas) and `PageIndicatorHud` are strictly gated behind `uiState.isReady`. The window background scrim, blur, and radial emerald glow continue fading in smoothly via `overlayAlpha` (220ms decelerate).
  2. **Atomic Target Page Resolution:** `DeckViewModel.onOverlayShown(targetPageOverride)` queries repository snapshot and settings, derives displayed layout, resolves `targetPage` (`targetPageOverride` if present, else `0` if `openOnPageOne`, else `lastViewedPageIndex`), and publishes all fields atomically in `_uiState.update`: `activePageIndex = targetPage, isReady = true, snapPageTransitions = true`. When `isReady` becomes true, `AnimatedContent` composes for the very first time with `targetState = targetPage`; Page 0 content is never composed or displayed.
  3. **Programmatic Snap vs. Interactive Animation:** `DeckUiState` includes `snapPageTransitions: Boolean = true`. `DeckPageTransitionSpec.createTransition` yields `EnterTransition.None togetherWith ExitTransition.None` whenever `snapPageTransitions == true` or `targetState == initialState`. Programmatic page restoration snaps instantly with zero duration.
  4. **Post-Ready Animation Enablement:** Once the initial ready state is composed, `LaunchedEffect(uiState.isReady)` calls `onReady` (`viewModel.enablePageAnimations()`), setting `snapPageTransitions = false`. All subsequent user-initiated navigations (touch/trackpad swipes, pagination HUD pill clicks, arrow keys, number keys, and in-drag edge dwell) animate with smooth spring physics (`PAGE_FLIP_SPRING_DAMPING`, `PAGE_FLIP_SPRING_STIFFNESS`).





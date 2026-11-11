# WidgetLeaf Agent Onboarding & Context Index (`_agents/`)

> [!IMPORTANT]
> **LIVING DOCUMENTATION MANDATE FOR ALL AGENTS:**
> These `_agents/` documents are the single source of truth for AI agents and engineers working on **WidgetLeaf**. Whenever you add a feature, change window/service behavior, alter the database/DataStore schema, or discover a new UI/platform invariant, **you MUST update `_agents/README.md`, `_agents/ARCHITECTURE.md`, and `_agents/INVARIANTS.md` in the same commit** before marking your task complete.

---

## 1. Mission & Functional Summary

**WidgetLeaf** (`io.devnullapps.widgetleaf`, current version `v0.0.4`) is an on-demand, frosted-glass HUD and modular desktop widget overlay built for Android Desktop / Laptop environments ("Googlebook").

- **Ephemeral Flyleaf Overlay:** Triggered via the **Top Menu Bar leaf icon** (`WidgetLeafTopBarService`, configurable Left or Right position), **Global Hotkey** (default `Alt + W`, customizable in Overlay Settings), **Quick Settings tile** (`WidgetDeckTileService`), or **`ACTION_ASSIST`**.
- **Full-Screen Frosted Glass Canvas:** Renders over the active desktop using hardware cross-window blur (`FLAG_BLUR_BEHIND`, `36dp`), dark scrim, ambient emerald radial glow, and `2%` procedural noise grain.
- **Frameless Live Android Widgets:** Hosts real `AppWidgetHostView` instances (`DeckAppWidgetHostView`, host ID `1024`) on a responsive desktop Bento snap grid (`DesktopGridProfileResolver`) spanning full width with mathematically symmetric margins (`INV-14`). Forces host view padding to zero (INV-13) and syncs multi-orientation sizes so responsive RemoteViews layouts match grid cell dimensions with zero fallback truncation.
- **Desktop Pointer & Keyboard First:**
  - **Normal Mode:** Widgets are live and interactive. Clicking empty background or pressing `Escape` / any non-UI key (`Space`, letters, symbols) dismisses the overlay.
  - **Page Swipes & Dismiss Gestures (INV-18):**
    - **Page Swipes:** 1-finger horizontal touch swipe on overlay pane or 2-finger horizontal swipe on trackpad flips pages following content direction (swipe left → next page, swipe right → previous page). Strictly enforces boundaries (stops at Page 0 and last page; no wrap-around, no page creation by swipe). Operates over empty canvas and non-scrollable widgets; yields touch streams to widgets that can scroll horizontally in the gesture direction.
    - **Dismiss Swipes:** 1-finger vertical touch swipe or 2-finger vertical trackpad swipe (up or down) dismisses the overlay pane over empty background or over widgets that cannot scroll vertically in that direction (e.g. clocks, weather). Scrollable widgets (calendars, task lists) retain vertical scrolling.
    - **Trackpad vs. Mouse Wheel Distinction:** Regular mouse wheels (`TOOL_TYPE_MOUSE`, discrete notch increments) NEVER page or dismiss. Trackpad two-finger swipes are differentiated via Android 14+ classification (`CLASSIFICATION_TWO_FINGER_SWIPE` / `CLASSIFICATION_MULTI_FINGER_SWIPE`), gesture axes (`AXIS_GESTURE_SCROLL_X_DISTANCE` / `Y`), `SOURCE_TOUCHPAD`, or `TOOL_TYPE_FINGER`.
    - **Edit Mode Guard:** All page and dismiss swipe gestures are strictly disabled while any widget is in edit mode.
  - **Hotkey Toggle (INV-18):** Pressing the global hotkey combo (default `Alt + W`), clicking the top bar leaf pill, or triggering `ToggleDeckShortcutActivity` behaves as a consistent toggle: opens the pane when hidden, dismisses it when already visible. Debounces key repeats (350ms window) and protects the initial 300ms window so key releases do not race with "dismiss on other keys".
  - **Context Menu:** Right-clicking (`BUTTON_SECONDARY`), `Ctrl+Click`, two-finger clicking, or long-pressing the **empty canvas** opens the desktop context menu (`Widgets`, `Overlay Settings`).
  - **Widget Edit Mode:** Right-clicking, `Ctrl+Click`, two-finger clicking, or long-pressing a **widget** enters `EDIT_MODE` for that widget ONLY (suppressing the canvas context menu). Displays a centered `Reconfigure | Remove` pill inside the widget bounds, 4 centered perimeter resize handles (`↔` / `↕`), 4 diagonal corner handles (rendered ONLY for `RESIZE_BOTH` widgets with diagonal double-arrow cursors), and interior drag-to-move with **Desktop-Style Cascade Displacement**:
    - **Cascade Displacement:** Moving or resizing a widget onto occupied space cascades displaced widgets in column-major order (downward first, then next column right, then next page top-left, auto-creating new pages if needed) without changing any displaced widget's size. Vacated space is freed.
    - **Live Drag & Resize Preview:** After hovering over a target cell during move or holding proposed resize bounds unchanged for 250ms, displaced widgets animate (spring slide) into their cascade positions. Hovering back to the start cell, retracting resize to a non-overlapping/original size, or cancelling reverts all widgets to the gesture snapshot. Dropping or releasing commits and persists atomically.
    - **Diagonal Resizing:** 4 corner handles (~28dp square hit target) resize both axes simultaneously anchoring the opposite corner, snapping to grid cells and clamping to grid and provider limits.
    - **In-Drag Edge Dwell Page Switching (INV-17):** Holding a dragged widget at the left/right screen edge for ~500ms flips pages mid-drag (repeating every 500ms while holding; leaving edge zone resets timer). Dragging right past the last page creates a speculative new page (shown on HUD via `uiState.effectivePages`); left dwell stops at Page 0. After flip, the dragged widget remains anchored under the pointer; live cascade preview runs on the new page. Dropping commits atomically and prunes empty/speculative pages; Escape/cancel reverts everything to drag-start snapshot and start page. Hoisted into `WidgetEditDragLayer` above `AnimatedContent` (INV-15) with single-parent host view safety (INV-16). Old drop-at-edge bump behavior is removed.
  - **Bottom Pagination Pill:** Circular page loop (`◄` / `►`), page pills (`1 .. N`) with inline right-click/long-press rename, `+` (Widget Picker), and `⚙` (Overlay Settings).
- **Adding New Widgets (Non-Displacement Rule, INV-19):** Adding a new widget (auto-place or catalog drag-drop) never displaces existing widgets. If the target page is full or the dropped spot overlaps, WidgetLeaf scans the active page, then subsequent pages in order, then appends a new page at the end, evaluating spots in column-major order and navigating to the placed page. Existing widget moves and resizes retain desktop cascade displacement.
- **Configuration Flow Overlay Preservation (INV-20):** Launching 3rd party widget configuration activities is delegated to an isolated trampoline activity (`WidgetConfigureTrampolineActivity`, `taskAffinity="io.devnullapps.widgetleaf.configure"`). `WidgetDeckActivity` finishes prior to external launch, preventing the overlay task from degrading into a desktop freeform window, and relaunches a fresh fullscreen overlay upon configure completion.

---

## 2. Core Documentation Index

| Document | Purpose |
|---|---|
| [**`_agents/README.md`**](README.md) | This onboarding index, functional summary, repository map, and build/test commands. |
| [**`_agents/ARCHITECTURE.md`**](ARCHITECTURE.md) | 4-layer architecture (`ui`, `domain`, `data`, `platform`/`services`), top-bar BentoBar coexistence, gesture routing, and state flows. |
| [**`_agents/INVARIANTS.md`**](INVARIANTS.md) | Non-negotiable technical guardrails (Compose `pointerInput` keys, `.offset` ordering, `AccessibilityService` flags, Gradle VM cache rules). |
| [**`../AGENTS.md`**](../AGENTS.md) | Root entry-point pointer for agents scanning the repository root. |

---

## 3. Repository Map

```text
.
├── AGENTS.md                        # Root pointer to _agents/ documentation
├── _agents/                         # Authoritative AI agent documentation (KEEP UPDATED)
│   ├── README.md                    # Onboarding, functional summary, repo map & commands
│   ├── ARCHITECTURE.md              # Clean 4-layer architecture & desktop surface mechanics
│   └── INVARIANTS.md                # Non-negotiable invariants & hard-won bug preventions
├── app/
│   ├── build.gradle.kts             # Versioning (v0.0.4), signing, Room KSP, Compose, JaCoCo
│   └── src/
│       ├── main/kotlin/io/devnullapps/widgetleaf/
│       │   ├── WidgetLeafApp.kt     # Application entrypoint & AppContainer owner
│       │   ├── di/AppContainer.kt   # Manual DI container (Room, DataStore, UseCases, Gateways)
│       │   ├── domain/
│       │   │   ├── model/           # PlacedWidget, DeckPage, DesktopGridProfile, OverlaySettings
│       │   │   ├── repository/      # WidgetDeckRepository interface
│       │   │   └── usecase/         # CascadeDisplacementResolver, GridCollisionResolver,
│       │   │                        # EdgePageDragTransferUseCase, CalculateResponsiveSizesUseCase,
│       │   │                        # WidgetSpanCalculator, ReconcileOrphanWidgetsUseCase
│       │   ├── data/
│       │   │   ├── local/           # LeafDatabase (Room), PreferencesDataStore, DAOs & Entities
│       │   │   └── repository/      # DefaultWidgetDeckRepository (atomic transactions + page pruning)
│       │   ├── platform/            # DesktopGridProfileResolver, AndroidWindowBlurController,
│       │   │                        # AndroidWidgetHostGateway
│       │   ├── services/            # WidgetLeafTopBarService (Accessibility top-bar leaf + hotkey),
│       │   │                        # StatusBarSpaceScanner (BentoBar coexistence),
│       │   │                        # TaskbarPlacementManager, StatusBarTriggerManager,
│       │   │                        # BootCompletedReceiver, WidgetDeckTileService
│       │   └── ui/
│       │       ├── WidgetDeckActivity.kt        # Translucent overlay Activity & AppWidget bind flow
│       │       ├── viewmodel/                   # DeckViewModel & DeckUiState
│       │       ├── hostview/                    # WidgetDeckHost, DeckAppWidgetHostView & DeckWidgetHostContainer
│       │       ├── theme/                       # Color, Theme, MotionSpec
│       │       └── components/                  # WidgetDeckOverlayScreen, WidgetGridPage,
│       │                                        # WidgetEditDragLayer, GlassSurfaceBox (WidgetEditResizeFrame),
│       │                                        # BottomPaginationHud, DesktopContextMenu,
│       │                                        # WidgetPickerBottomSheet, OverlaySettingsDialog
│       └── test/kotlin/io/devnullapps/widgetleaf/ # Robolectric + Compose UI + Domain unit tests
└── docs/IMPLEMENTATION_PLAN.md      # Original design & implementation specification
```

---

## 4. Everyday Commands (Android Linux VM Environment - 4 GB RAM Guardrails)

Because the Linux VM runs in a fixed 4 GB RAM container and the workspace resides on `/storage/emulated/10` (`fuse`), **always redirect Gradle's project cache to `$HOME/.cache` (`ext4`)**, limit concurrency to 1 worker (`--max-workers=1 --no-parallel`), run `./gradlew --stop` before heavy runs, and execute debug/test and release builds in separate invocations:

```bash
# 1. Stop existing Daemons & run Unit + Robolectric Compose UI tests + Jacoco coverage
./gradlew --stop --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" && \
./gradlew testDebugUnitTest jacocoTestReport assembleDebug \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" \
  -Dorg.gradle.jvmargs="-Xmx640m -XX:MaxMetaspaceSize=384m" \
  --no-parallel --max-workers=1 \
  --console=plain

# 2. Stop Daemons & build signed Release APK (R8 minified + resource shrunk)
./gradlew --stop --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" && \
./gradlew assembleRelease \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" \
  -Dorg.gradle.jvmargs="-Xmx640m -XX:MaxMetaspaceSize=384m" \
  --no-parallel --max-workers=1 \
  --console=plain

# 3. Export APKs to shared root for host installation
cp app/build/outputs/apk/debug/WidgetLeaf-debug.apk /storage/emulated/10/AntiGravity/WidgetLeaf-debug.apk
cp app/build/outputs/apk/release/WidgetLeaf-release.apk /storage/emulated/10/AntiGravity/WidgetLeaf-release.apk
```

### 4.1 Release Signing Credentials Setup

Release signing credentials (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) are resolved dynamically without hardcoded secrets:
1. **Environment Variables (CI / Automation):** `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (and optional `KEYSTORE_FILE`).
2. **Local Keystore Properties (`keystore.properties` in repo root):**
   - Keys: `storeFile` (relative to `app/`, e.g. `keystore/release.jks`), `storePassword`, `keyAlias`, `keyPassword`.
   - File is strictly gitignored (`.gitignore`).
3. **Safety Fallback:** Debug builds and unit tests execute cleanly without release credentials present. Release packaging tasks (`assembleRelease`, `bundleRelease`) fail fast with a descriptive error if credentials are missing.

---

## 5. Definition of Done for Agents

1. **Tests Green:** `testDebugUnitTest` passes with 0 failures; new UI/domain behavior has a corresponding test in `app/src/test/`.
2. **Deliverables Built:** Both `WidgetLeaf-debug.apk` and `WidgetLeaf-release.apk` build cleanly and are copied to `/storage/emulated/10/AntiGravity/`.
3. **Agent Docs Updated:** If you changed any behavior, setting, gesture, or architectural contract, update `_agents/README.md`, `_agents/ARCHITECTURE.md`, and `_agents/INVARIANTS.md` before finishing.

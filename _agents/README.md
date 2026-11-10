# WidgetLeaf Agent Onboarding & Context Index (`_agents/`)

> [!IMPORTANT]
> **LIVING DOCUMENTATION MANDATE FOR ALL AGENTS:**
> These `_agents/` documents are the single source of truth for AI agents and engineers working on **WidgetLeaf**. Whenever you add a feature, change window/service behavior, alter the database/DataStore schema, or discover a new UI/platform invariant, **you MUST update `_agents/README.md`, `_agents/ARCHITECTURE.md`, and `_agents/INVARIANTS.md` in the same commit** before marking your task complete.

---

## 1. Mission & Functional Summary

**WidgetLeaf** (`io.devnullapps.widgetleaf`, current version `v0.0.1`) is an on-demand, frosted-glass HUD and modular desktop widget overlay built for Android Desktop / Laptop environments ("Googlebook").

- **Ephemeral Flyleaf Overlay:** Triggered via the **Top Menu Bar leaf icon** (`WidgetLeafTopBarService`, configurable Left or Right position), **Global Hotkey** (default `Alt + W`, customizable in Overlay Settings), **Quick Settings tile** (`WidgetDeckTileService`), or **`ACTION_ASSIST`**.
- **Full-Screen Frosted Glass Canvas:** Renders over the active desktop using hardware cross-window blur (`FLAG_BLUR_BEHIND`, `36dp`), dark scrim, ambient emerald radial glow, and `2%` procedural noise grain.
- **Frameless Live Android Widgets:** Hosts real `AppWidgetHostView` instances (`DeckAppWidgetHostView`, host ID `1024`) on a responsive `8×8` / `6×6` desktop Bento snap grid (`DesktopGridProfileResolver`).
- **Desktop Pointer & Keyboard First:**
  - **Normal Mode:** Widgets are live and interactive. Clicking empty background or pressing `Escape` / any non-UI key (`Space`, letters, symbols) dismisses the overlay.
  - **Context Menu:** Right-clicking (`BUTTON_SECONDARY`), `Ctrl+Click`, two-finger clicking, or long-pressing the **empty canvas** opens the desktop context menu (`Widgets`, `Overlay Settings`).
  - **Widget Edit Mode:** Right-clicking, `Ctrl+Click`, two-finger clicking, or long-pressing a **widget** enters `EDIT_MODE` for that widget ONLY (suppressing the canvas context menu). Displays a centered `Reconfigure | Remove` pill inside the widget bounds, 4 centered perimeter resize handles with native desktop resize cursors (`↔` / `↕`) and live emerald outline preview (hit-testing restricted to the pill zone to prevent accidental resize hijacking), and interior drag-to-move with **Desktop-Style Cascade Displacement**:
    - **Cascade Displacement:** The dragged widget takes the target cell without altering its span; displaced widgets keep their dimensions and cascade in column-major order (downward first, then next column right, then next page top-left, auto-creating new pages if needed). Vacated space is freed.
    - **Live Preview:** After hovering over a target cell for 250ms, displaced widgets animate (spring slide) into their cascade positions. Hovering back to the original cell or cancelling reverts all widgets to the drag-start snapshot. Dropping commits and persists atomically.
  - **Bottom Pagination Pill:** Circular page loop (`◄` / `►`), page pills (`1 .. N`) with inline right-click/long-press rename, `+` (Widget Picker), and `⚙` (Overlay Settings).

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
│   ├── build.gradle.kts             # Versioning (v0.0.1), signing, Room KSP, Compose, JaCoCo
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
│       │       ├── hostview/                    # WidgetDeckHost & DeckAppWidgetHostView
│       │       ├── theme/                       # Color, Theme, MotionSpec
│       │       └── components/                  # WidgetDeckOverlayScreen, WidgetGridPage,
│       │                                        # GlassSurfaceBox (WidgetEditResizeFrame),
│       │                                        # BottomPaginationHud, DesktopContextMenu,
│       │                                        # WidgetPickerBottomSheet, OverlaySettingsDialog
│       └── test/kotlin/io/devnullapps/widgetleaf/ # Robolectric + Compose UI + Domain unit tests
└── docs/IMPLEMENTATION_PLAN.md      # Original design & implementation specification
```

---

## 4. Everyday Commands (Android Linux VM Environment)

Because the workspace resides on `/storage/emulated/10` (`fuse`), **always redirect Gradle's project cache to `$HOME/.cache` (`ext4`)** and run debug/test and release builds in separate Gradle invocations so the `384 MiB` JVM Metaspace is not exhausted:

```bash
# 1. Run all unit & Robolectric Compose UI tests + build Debug APK
./gradlew testDebugUnitTest assembleDebug \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" \
  --console=plain

# 2. Build signed Release APK (R8 minified + resource shrunk)
./gradlew assembleRelease \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" \
  --console=plain

# 3. Export APKs to shared root for host installation
cp app/build/outputs/apk/debug/WidgetLeaf-debug.apk /storage/emulated/10/AntiGravity/WidgetLeaf-debug.apk
cp app/build/outputs/apk/release/WidgetLeaf-release.apk /storage/emulated/10/AntiGravity/WidgetLeaf-release.apk
```

---

## 5. Definition of Done for Agents

1. **Tests Green:** `testDebugUnitTest` passes with 0 failures; new UI/domain behavior has a corresponding test in `app/src/test/`.
2. **Deliverables Built:** Both `WidgetLeaf-debug.apk` and `WidgetLeaf-release.apk` build cleanly and are copied to `/storage/emulated/10/AntiGravity/`.
3. **Agent Docs Updated:** If you changed any behavior, setting, gesture, or architectural contract, update `_agents/README.md`, `_agents/ARCHITECTURE.md`, and `_agents/INVARIANTS.md` before finishing.

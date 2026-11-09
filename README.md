# WidgetLeaf (`io.devnullapps.widgetleaf`)

<p align="center">
  <img src="docs/assets/widgetleaf_icon.jpg" alt="WidgetLeaf Icon" width="220" />
</p>

**WidgetLeaf** is an on-demand, frosted-glass HUD and modular desktop widget overlay designed for Android Desktop and Laptop environments ("Googlebook").

Inspired by the metaphor of a *Digital Flyleaf*, WidgetLeaf drops an ephemeral, translucent, hardware-blurred (`36dp` `FLAG_BLUR_BEHIND`) sheet over your active workspace windows when triggered from the **Taskbar/Shelf**, **Top Menu Bar / BentoBar**, or **global hotkey** (`Search + W` / `Alt + Space` / `ACTION_ASSIST`), hosting multiple customizable pages of live Android widgets that vanish instantly when you press `Escape` or click outside.

---

## Key Features

- **Full-Screen Frosted Glass Blur (Zero Extra UI Chrome):** Hardware-accelerated cross-window blur (`36dp`), `scrim-dark` (`rgba(10, 13, 16, 0.72)`), ambient `leaf-glow` radial backlighting, and `2%` procedural noise grain—with native frameless widget rendering directly on the canvas and a minimal **bottom** virtual-desktop pagination pill.
- **Multi-Surface Desktop Activation:**
  - **Taskbar:** Pin to the left/main dock or keep in the **Running Apps** section on the right side of the taskbar divider.
  - **Top Menu Bar / BentoBar:** Pin an ongoing status-bar icon on the **left** or a Quick Settings `TileService` tile on the **right**.
  - **Global Hotkey & Launch on Startup:** Supports `ACTION_ASSIST` (`Search + W` / `Alt + Space`) and automatic trigger restoration on boot (`RECEIVE_BOOT_COMPLETED`).
- **Circular Multi-Page Navigation & Inline Re-Labeling:** Flip pages with `Left`/`Right` arrows (with circular wrap-around), `PageUp`/`PageDown`, number keys `1`–`9` & `0` (pages $1 \dots 10$), or trackpad horizontal scroll. Right-click or long-press any page pill to rename it inline (saving empty text resets to `1 .. N`).
- **Native Desktop Grid, Auto-Bump & Edge-DragParity:** Matches host `WindowMetrics` and `Launcher3` `DeviceProfile` rules, automatically shifts/bumps intersecting widgets on move/resize, creates a new page when dragging off the right edge of the last page, and automatically prunes empty pages.
- **Desktop Right-Click / Two-Finger Click Workflow:** Right-click (`BUTTON_SECONDARY`) or `Ctrl+Click` on the empty canvas (or the Taskbar/Shelf icon) to open the `Launcher3`-style **Widget Picker** or **Overlay Settings**; right-click any widget to move, resize, reconfigure, or remove it.

---

## Documentation

- [AI Agent Onboarding & Architecture (`_agents/`)](_agents/README.md) ([`AGENTS.md`](AGENTS.md))
- [Architecture & Implementation Plan](docs/IMPLEMENTATION_PLAN.md)
- [WidgetLeaf Design Bible (Google Doc)](https://docs.google.com/document/d/1noMsVDyMKCZhwh6bBdH45exHTnK5qCK6_NqssBTImI4/edit)

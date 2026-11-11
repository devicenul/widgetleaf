# AGENTS.md — AI Agent Guide for WidgetLeaf (`io.devnullapps.widgetleaf`)

> [!IMPORTANT]
> **KEEP AGENT DOCS UP TO DATE:**
> Before starting any task, read the authoritative agent documentation in [`_agents/`](_agents/README.md). Whenever you change functional behavior, UI/gesture mechanics, settings, or architecture, **you MUST update `_agents/README.md`, `_agents/ARCHITECTURE.md`, and `_agents/INVARIANTS.md` in the same commit**.

## Quick Links

1. [**`_agents/README.md`**](_agents/README.md) — Functional overview, repository directory map, build/test commands, and Definition of Done.
2. [**`_agents/ARCHITECTURE.md`**](_agents/ARCHITECTURE.md) — 4-layer architecture (`ui`, `domain`, `data`, `platform`/`services`), `DesktopGridProfileResolver`, BentoBar top-bar coexistence, and widget host lifecycle.
3. [**`_agents/INVARIANTS.md`**](_agents/INVARIANTS.md) — Non-negotiable technical guardrails (Compose `pointerInput` keys, `.offset` ordering, `DeckAppWidgetHostView` edit-mode touch handling, `AccessibilityService` flags, Robolectric teardown, and Linux VM Gradle cache rules).

## Essential Build & Test Commands (4 GB Linux VM Guardrails)

```bash
# 1. Stop daemons & run Unit + Robolectric Compose UI Tests, Jacoco Coverage & Assemble Debug APK
./gradlew --stop --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" && \
./gradlew testDebugUnitTest jacocoTestReport assembleDebug \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" \
  -Dorg.gradle.jvmargs="-Xmx640m -XX:MaxMetaspaceSize=384m" \
  --no-parallel --max-workers=1 \
  --console=plain

# 2. Stop daemons & assemble Signed Release APK & Copy Deliverables
./gradlew --stop --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" && \
./gradlew assembleRelease \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle" \
  -Dorg.gradle.jvmargs="-Xmx640m -XX:MaxMetaspaceSize=384m" \
  --no-parallel --max-workers=1 \
  --console=plain && \
cp app/build/outputs/apk/debug/WidgetLeaf-debug.apk /storage/emulated/10/AntiGravity/WidgetLeaf-debug.apk && \
cp app/build/outputs/apk/release/WidgetLeaf-release.apk /storage/emulated/10/AntiGravity/WidgetLeaf-release.apk
```

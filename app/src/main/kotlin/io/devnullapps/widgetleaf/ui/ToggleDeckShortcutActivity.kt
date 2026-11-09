package io.devnullapps.widgetleaf.ui

import android.app.Activity
import android.os.Bundle
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper

/**
 * Lightweight shortcut entry point (`Theme.NoDisplay`) that toggles the WidgetLeaf overlay
 * and finishes immediately.
 *
 * Exposes a dedicated target in Android's system Keyboard Shortcuts customizer
 * (`Settings > System > Keyboard > Customize keyboard shortcuts` / `Action + /`),
 * matching the pattern used by BentoBar's `BarMenuActivity`.
 */
class ToggleDeckShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActiveDisplayLaunchHelper.launchOverlay(
            this,
            ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
        )
        finish()
    }
}

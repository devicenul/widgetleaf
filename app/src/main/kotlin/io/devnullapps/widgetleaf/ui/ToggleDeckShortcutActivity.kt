package io.devnullapps.widgetleaf.ui

import android.app.Activity
import android.os.Bundle
import android.view.Display
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import io.devnullapps.widgetleaf.services.WidgetLeafTopBarService

/**
 * Lightweight shortcut entry point (`Theme.NoDisplay`) that toggles or routes the WidgetLeaf overlay
 * onto the active display and finishes immediately.
 *
 * Used both by Android's Keyboard Shortcuts customizer (`Action + /`) and ongoing status bar
 * notification triggers so active-display routing is evaluated live at invocation time.
 */
class ToggleDeckShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action ?: ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
        val ownDisplayId = ActiveDisplayLaunchHelper.getActiveDisplayId(this)
        val serviceDisplayId = WidgetLeafTopBarService.instance?.let {
            ActiveDisplayLaunchHelper.getActiveDisplayId(it)
        }
        val targetDisplayId = if (ownDisplayId != Display.DEFAULT_DISPLAY) {
            ownDisplayId
        } else {
            serviceDisplayId ?: ownDisplayId
        }

        ActiveDisplayLaunchHelper.toggleOrLaunchOverlay(
            context = this,
            displayId = targetDisplayId,
            action = action
        )
        finish()
    }
}

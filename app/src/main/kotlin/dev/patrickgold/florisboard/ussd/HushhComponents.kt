/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ussd

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches optional system integrations of the app on or off. */
object HushhComponents {

    /**
     * The "Pay with HushhBoard" entry Android adds to the text-selection menu of every app.
     * Off by default (it appeared on every single selection); users can opt in from Settings.
     */
    fun setPayInSelectionMenu(context: Context, enabled: Boolean) {
        val component = ComponentName(context, ProcessTextActivity::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) {
            // Never crash settings over an optional menu entry.
        }
    }
}

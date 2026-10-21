package com.sailer.browser.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Disimulo opcional: cambia el icono y el nombre de la app por los de una
 * "Calculadora". Se hace con dos accesos directos (activity-alias) y se
 * activa uno u otro.
 */
object Disguise {

    private const val NORMAL = "com.sailer.browser.LauncherNormal"
    private const val CALCULATOR = "com.sailer.browser.LauncherCalculator"

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, CALCULATOR)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        // Primero activar el nuevo y luego desactivar el viejo, para no quedarse nunca sin icono.
        val (on, off) = if (enabled) CALCULATOR to NORMAL else NORMAL to CALCULATOR
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, on),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, off),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Nombre que se muestra (notificaciones, apps recientes). */
    fun label(context: Context): String =
        if (isEnabled(context)) "Calculadora" else "Sailer"
}

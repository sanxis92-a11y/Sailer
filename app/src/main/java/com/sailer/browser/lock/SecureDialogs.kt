package com.sailer.browser.lock

import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Los diálogos son ventanas aparte y no heredan la protección contra
 * capturas de la pantalla: se la añadimos a cada uno.
 */
fun MaterialAlertDialogBuilder.showSecure(): AlertDialog {
    val dialog = create()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    dialog.show()
    return dialog
}

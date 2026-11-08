package com.sailer.browser.lock

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla de calculadora que funciona de verdad. Si se escribe el PIN
 * (solo números) y se pulsa «=», se llama a [onPinEntered]. Mantener
 * pulsado «=» abre la huella, si está activada.
 */
class CalculatorScreen(
    private val context: Context,
    private val onPinEntered: (String) -> Boolean,
    private val onBiometric: (() -> Unit)?,
) {

    private var expression = ""
    private var justEvaluated = false

    private val result = text(48f, Color.WHITE)
    private val history = text(20f, Color.parseColor("#9AA0A6"))

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#FF101214"))
        fitsSystemWindows = true
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
        addView(history, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(result, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "="),
        ).forEach { row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { key -> addView(button(key), LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
            })
        }
    }

    init {
        refresh()
    }

    private fun press(key: String) {
        when (key) {
            "C" -> { expression = ""; history.text = "" }
            "⌫" -> expression = expression.dropLast(1)
            "=" -> { equals(); return }
            "±" -> expression = toggleSign(expression)
            "%" -> if (expression.isNotEmpty() && expression.last().isDigit()) expression += "%"
            "÷", "×", "−", "+" -> {
                val op = key[0]
                expression = when {
                    expression.isEmpty() -> if (op == '−') "−" else ""
                    Calculator.isOperator(expression.last()) -> expression.dropLast(1) + op
                    else -> expression + op
                }
            }
            "." -> {
                if (justEvaluated) expression = ""
                val lastNumber = expression.takeLastWhile { !Calculator.isOperator(it) }
                if (!lastNumber.contains('.')) expression += if (lastNumber.isEmpty()) "0." else "."
            }
            else -> {
                if (justEvaluated) expression = ""
                if (expression.length < 40) expression += key
            }
        }
        justEvaluated = false
        refresh()
    }

    private fun equals() {
        // Solo números (sin operaciones): puede ser el PIN.
        if (expression.length >= 4 && expression.all { it.isDigit() } && onPinEntered(expression)) return
        val value = Calculator.evaluate(expression)
        history.text = if (expression.isEmpty()) "" else "$expression ="
        expression = value ?: ""
        result.text = value ?: "Error"
        justEvaluated = true
    }

    private fun toggleSign(expr: String): String {
        val start = expr.indexOfLast { Calculator.isOperator(it) } + 1
        val number = expr.substring(start)
        if (number.isEmpty()) return expr
        val before = expr.substring(0, start)
        return if (before.endsWith("−") && (before.length == 1 || Calculator.isOperator(before[before.length - 2]))) {
            before.dropLast(1) + number
        } else {
            before + "−" + number
        }
    }

    private fun refresh() {
        result.text = expression.ifEmpty { "0" }
    }

    private fun button(key: String) = TextView(context).apply {
        text = key
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        val isOp = key in setOf("÷", "×", "−", "+", "=")
        val isFn = key in setOf("C", "⌫", "%", "±")
        setTextColor(if (isFn) Color.parseColor("#FFB4A9FF") else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(38).toFloat()
            setColor(Color.parseColor(if (isOp) "#FF3F51B5" else "#FF22262B"))
        }
        isClickable = true
        isFocusable = true
        contentDescription = key
        setOnClickListener { press(key) }
        if (key == "=" && onBiometric != null) {
            setOnLongClickListener { onBiometric.invoke(); true }
        }
    }

    private fun text(sizeSp: Float, color: Int) = TextView(context).apply {
        gravity = Gravity.END or Gravity.BOTTOM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        maxLines = 2
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

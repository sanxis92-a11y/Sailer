package com.sailer.browser.lock

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Calculadora sencilla (+ − × ÷ %) para el disimulo. Respeta la prioridad
 * de × y ÷ sobre + y −.
 */
object Calculator {

    private val OPERATORS = setOf('+', '−', '×', '÷')

    fun isOperator(c: Char) = c in OPERATORS

    /** Calcula la expresión o devuelve null si no es válida (p. ej. dividir entre 0). */
    fun evaluate(expression: String): String? = runCatching {
        val tokens = tokenize(expression.trimEnd { isOperator(it) })
        if (tokens.isEmpty()) return null
        // Primero × y ÷, después + y −.
        val terms = mutableListOf<BigDecimal>()
        val signs = mutableListOf<Char>()
        var current = tokens[0] as BigDecimal
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i] as Char
            val next = tokens[i + 1] as BigDecimal
            when (op) {
                '×' -> current = current.multiply(next, MC)
                '÷' -> current = current.divide(next, MC)
                else -> {
                    terms += current
                    signs += op
                    current = next
                }
            }
            i += 2
        }
        terms += current
        var result = terms[0]
        signs.forEachIndexed { index, op ->
            result = if (op == '+') result.add(terms[index + 1], MC) else result.subtract(terms[index + 1], MC)
        }
        format(result)
    }.getOrNull()

    private val MC = MathContext(16, RoundingMode.HALF_UP)

    private fun tokenize(expression: String): List<Any> {
        val tokens = mutableListOf<Any>()
        val number = StringBuilder()
        fun flush() {
            if (number.isEmpty()) return
            var text = number.toString()
            val percent = text.endsWith("%")
            text = text.trimEnd('%')
            var value = BigDecimal(if (text == "-") "0" else text)
            if (percent) value = value.divide(BigDecimal(100), MC)
            tokens += value
            number.clear()
        }
        expression.forEachIndexed { index, c ->
            if (isOperator(c) && !(c == '−' && (index == 0 || isOperator(expression[index - 1])))) {
                flush()
                tokens += c
            } else {
                number.append(if (c == '−') '-' else c)
            }
        }
        flush()
        return tokens
    }

    private fun format(value: BigDecimal): String {
        val plain = value.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val text = if (plain.abs() >= BigDecimal("1e12") || (plain.signum() != 0 && plain.abs() < BigDecimal("1e-6"))) {
            plain.toString()
        } else {
            plain.toPlainString()
        }
        return text.replace('-', '−')
    }
}

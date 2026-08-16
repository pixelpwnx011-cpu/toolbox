package com.geneo.smartboard.overlay

import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Wires up a basic 4-function calculator (with %, decimal point, backspace, clear)
 * to the buttons in overlay_calculator.xml. Keeps its own small piece of state
 * (current operand, pending operator, running total) rather than parsing a full
 * expression string, which keeps behaviour predictable for quick on-board sums.
 *
 * IMPORTANT: this class previously formatted numbers with thousands-separator
 * commas (e.g. "1,234"). That string was then fed straight back into
 * String.toDouble() on the next button press, which fails to parse and left
 * the calculator "stuck". Combined with an unguarded WindowManager call
 * elsewhere in the overlay, that could bring the whole floating toolbox down.
 * Fixed by (a) never formatting with grouping separators, and (b) wrapping
 * every button action so a bad edge case only resets that button press
 * instead of throwing.
 */
class CalculatorController(root: View) {

    private val tvExpression: TextView = root.findViewById(R.id.tvCalcExpression)
    private val tvResult: TextView = root.findViewById(R.id.tvCalcResult)

    // No grouping symbol ("#,##0" -> "0") so every formatted value round-trips
    // cleanly back through String.toDoubleOrNull() on the next button press.
    private val symbols = DecimalFormatSymbols(Locale.US)
    private val fmt = DecimalFormat("0.##########", symbols)

    private var pendingOperator: Char? = null
    private var storedValue: Double = 0.0
    private var currentInput: String = "0"
    private var expressionText: String = ""
    private var justEvaluated = false

    init {
        digit(root, R.id.btn0, "0")
        digit(root, R.id.btn1, "1")
        digit(root, R.id.btn2, "2")
        digit(root, R.id.btn3, "3")
        digit(root, R.id.btn4, "4")
        digit(root, R.id.btn5, "5")
        digit(root, R.id.btn6, "6")
        digit(root, R.id.btn7, "7")
        digit(root, R.id.btn8, "8")
        digit(root, R.id.btn9, "9")

        safeClick(root, R.id.btnDot) { onDot() }
        safeClick(root, R.id.btnClear) { onClear() }
        safeClick(root, R.id.btnBackspace) { onBackspace() }
        safeClick(root, R.id.btnPercent) { onPercent() }
        safeClick(root, R.id.btnPlus) { onOperator('+') }
        safeClick(root, R.id.btnMinus) { onOperator('-') }
        safeClick(root, R.id.btnMultiply) { onOperator('×') }
        safeClick(root, R.id.btnDivide) { onOperator('÷') }
        safeClick(root, R.id.btnEquals) { onEquals() }

        render()
    }

    /** Every button funnels through here so a thrown exception can never escape to the service. */
    private fun safeClick(root: View, id: Int, action: () -> Unit) {
        root.findViewById<Button>(id).setOnClickListener {
            try {
                action()
            } catch (t: Throwable) {
                Log.w("CalculatorController", "Recovered from calculator error", t)
                onClear()
            }
        }
    }

    private fun digit(root: View, id: Int, value: String) {
        safeClick(root, id) { onDigit(value) }
    }

    private fun onDigit(value: String) {
        if (justEvaluated) {
            currentInput = "0"
            expressionText = ""
            justEvaluated = false
        }
        // Cap length so huge sums can't overflow the card width or the Double parser.
        if (currentInput.replace("-", "").replace(".", "").length >= 15) return
        currentInput = if (currentInput == "0") value else currentInput + value
        render()
    }

    private fun onDot() {
        if (justEvaluated) {
            currentInput = "0"
            expressionText = ""
            justEvaluated = false
        }
        if (!currentInput.contains(".")) {
            currentInput += "."
            render()
        }
    }

    private fun onBackspace() {
        if (justEvaluated) {
            onClear()
            return
        }
        currentInput = if (currentInput.length > 1) currentInput.dropLast(1) else "0"
        render()
    }

    private fun onClear() {
        pendingOperator = null
        storedValue = 0.0
        currentInput = "0"
        expressionText = ""
        justEvaluated = false
        render()
    }

    private fun onPercent() {
        val value = safeParse(currentInput) ?: return onClear()
        currentInput = formatForInput(value / 100.0)
        render()
    }

    private fun onOperator(op: Char) {
        val value = safeParse(currentInput) ?: return onClear()
        storedValue = if (pendingOperator != null && !justEvaluated) {
            compute(storedValue, value, pendingOperator!!)
        } else {
            value
        }
        if (storedValue.isNaN() || storedValue.isInfinite()) return showError()

        pendingOperator = op
        expressionText = "${fmt.format(storedValue)} $op"
        currentInput = "0"
        justEvaluated = false
        renderExpressionOnly(storedValue)
    }

    private fun onEquals() {
        val value = safeParse(currentInput) ?: return onClear()
        val op = pendingOperator
        if (op != null) {
            val result = compute(storedValue, value, op)
            if (result.isNaN() || result.isInfinite()) return showError()
            expressionText = "${fmt.format(storedValue)} $op ${fmt.format(value)} ="
            storedValue = result
            currentInput = formatForInput(result)
        }
        pendingOperator = null
        justEvaluated = true
        render()
    }

    private fun compute(a: Double, b: Double, op: Char): Double {
        return when (op) {
            '+' -> a + b
            '-' -> a - b
            '×' -> a * b
            '÷' -> if (b == 0.0) Double.NaN else a / b
            else -> b
        }
    }

    /** Parses text the user/formatter produced. Never throws — returns null on anything unparsable. */
    private fun safeParse(text: String): Double? {
        return text.trim().toDoubleOrNull()
    }

    /** Formats a result back into plain digits (no grouping) so it can be parsed again immediately. */
    private fun formatForInput(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        return fmt.format(value)
    }

    private fun showError() {
        pendingOperator = null
        storedValue = 0.0
        currentInput = "0"
        expressionText = ""
        justEvaluated = true
        tvExpression.text = ""
        tvResult.text = "Error"
    }

    private fun render() {
        tvExpression.text = expressionText
        tvResult.text = currentInput
    }

    private fun renderExpressionOnly(value: Double) {
        tvExpression.text = expressionText
        tvResult.text = fmt.format(value)
    }
}

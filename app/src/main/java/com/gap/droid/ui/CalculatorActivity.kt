package com.gapmesh.droid.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gapmesh.droid.MainActivity
import com.gapmesh.droid.service.DecoyModeManager
import com.gapmesh.droid.ui.theme.BitchatTheme

/**
 * A fully functional calculator that serves as the decoy mode.
 * Users enter their secret PIN and press = to exit back to Gap Mesh.
 * Otherwise behaves as a standard dark-theme calculator.
 */
class CalculatorActivity : OrientationAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Back button minimises the app instead of going back to chat
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        setContent {
            BitchatTheme {
                CalculatorScreen(
                    onUnlock = {
                        Log.w("CalculatorActivity", "Correct PIN entered — exiting decoy")
                        DecoyModeManager.deactivateDecoy(applicationContext)
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                )
            }
        }
    }
}

// ── Calculator Composable ──────────────────────────────────────────────────────

@Composable
private fun CalculatorScreen(onUnlock: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Calculator state
    var displayText by remember { mutableStateOf("0") }
    var firstOperand by remember { mutableDoubleStateOf(0.0) }
    var pendingOperator by remember { mutableStateOf<String?>(null) }
    var isNewInput by remember { mutableStateOf(true) }
    var digitBuffer by remember { mutableStateOf("") }

    fun formatResult(value: Double): String {
        return if (value == value.toLong().toDouble() && value < 1e15) {
            value.toLong().toString()
        } else {
            val s = "%.10g".format(value)
            // Strip trailing zeros after decimal
            if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
        }
    }

    fun onDigit(d: String) {
        if (isNewInput) {
            displayText = if (d == ".") "0." else d
            digitBuffer = if (d == ".") "0." else d
            isNewInput = false
        } else {
            if (d == "." && displayText.contains('.')) return
            displayText = if (displayText == "0" && d != ".") d else displayText + d
            digitBuffer += d
        }
    }

    fun onOperator(op: String) {
        if (pendingOperator != null && !isNewInput) {
            // Chain evaluation
            val secondOperand = displayText.toDoubleOrNull() ?: 0.0
            val result = when (pendingOperator) {
                "+" -> firstOperand + secondOperand
                "−" -> firstOperand - secondOperand
                "×" -> firstOperand * secondOperand
                "÷" -> if (secondOperand != 0.0) firstOperand / secondOperand else Double.NaN
                else -> secondOperand
            }
            displayText = formatResult(result)
            firstOperand = result
        } else {
            firstOperand = displayText.toDoubleOrNull() ?: 0.0
        }
        pendingOperator = op
        isNewInput = true
        digitBuffer = ""
    }

    fun onEquals() {
        // Check PIN first
        if (DecoyModeManager.isCorrectPIN(context, digitBuffer)) {
            onUnlock()
            return
        }

        if (pendingOperator != null) {
            val secondOperand = displayText.toDoubleOrNull() ?: 0.0
            val result = when (pendingOperator) {
                "+" -> firstOperand + secondOperand
                "−" -> firstOperand - secondOperand
                "×" -> firstOperand * secondOperand
                "÷" -> if (secondOperand != 0.0) firstOperand / secondOperand else Double.NaN
                else -> secondOperand
            }
            displayText = if (result.isNaN()) "Error" else formatResult(result)
            firstOperand = result
            pendingOperator = null
        }
        isNewInput = true
        digitBuffer = ""
    }

    fun onClear() {
        displayText = "0"
        firstOperand = 0.0
        pendingOperator = null
        isNewInput = true
        digitBuffer = ""
    }

    fun onToggleSign() {
        val value = displayText.toDoubleOrNull() ?: return
        displayText = formatResult(-value)
    }

    fun onPercent() {
        val value = displayText.toDoubleOrNull() ?: return
        displayText = formatResult(value / 100.0)
    }

    // ── Layout ──────────────────────────────────────────────────────────

    val bgColor = Color(0xFF1C1C1E)
    val displayColor = Color.White
    val btnDark = Color(0xFF333333)
    val btnLight = Color(0xFFA5A5A5)
    val btnAccent = Color(0xFFFF9500)
    val btnAccentText = Color.White

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Display
            Text(
                text = displayText,
                color = displayColor,
                fontSize = if (displayText.length > 9) 48.sp else 72.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            )

            Spacer(Modifier.height(8.dp))

            // Rows of buttons
            val buttonSpacing = 10.dp

            // Row 1: AC, ±, %, ÷
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(buttonSpacing)) {
                CalcButton("AC", btnLight, Color.Black, Modifier.weight(1f)) { onClear() }
                CalcButton("±", btnLight, Color.Black, Modifier.weight(1f)) { onToggleSign() }
                CalcButton("%", btnLight, Color.Black, Modifier.weight(1f)) { onPercent() }
                CalcButton("÷", btnAccent, btnAccentText, Modifier.weight(1f)) { onOperator("÷") }
            }
            Spacer(Modifier.height(buttonSpacing))

            // Row 2: 7, 8, 9, ×
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(buttonSpacing)) {
                CalcButton("7", btnDark, Color.White, Modifier.weight(1f)) { onDigit("7") }
                CalcButton("8", btnDark, Color.White, Modifier.weight(1f)) { onDigit("8") }
                CalcButton("9", btnDark, Color.White, Modifier.weight(1f)) { onDigit("9") }
                CalcButton("×", btnAccent, btnAccentText, Modifier.weight(1f)) { onOperator("×") }
            }
            Spacer(Modifier.height(buttonSpacing))

            // Row 3: 4, 5, 6, −
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(buttonSpacing)) {
                CalcButton("4", btnDark, Color.White, Modifier.weight(1f)) { onDigit("4") }
                CalcButton("5", btnDark, Color.White, Modifier.weight(1f)) { onDigit("5") }
                CalcButton("6", btnDark, Color.White, Modifier.weight(1f)) { onDigit("6") }
                CalcButton("−", btnAccent, btnAccentText, Modifier.weight(1f)) { onOperator("−") }
            }
            Spacer(Modifier.height(buttonSpacing))

            // Row 4: 1, 2, 3, +
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(buttonSpacing)) {
                CalcButton("1", btnDark, Color.White, Modifier.weight(1f)) { onDigit("1") }
                CalcButton("2", btnDark, Color.White, Modifier.weight(1f)) { onDigit("2") }
                CalcButton("3", btnDark, Color.White, Modifier.weight(1f)) { onDigit("3") }
                CalcButton("+", btnAccent, btnAccentText, Modifier.weight(1f)) { onOperator("+") }
            }
            Spacer(Modifier.height(buttonSpacing))

            // Row 5: 0 (wide), ., =
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(buttonSpacing)) {
                CalcButton("0", btnDark, Color.White, Modifier.weight(2f), wide = true) { onDigit("0") }
                CalcButton(".", btnDark, Color.White, Modifier.weight(1f)) { onDigit(".") }
                CalcButton("=", btnAccent, btnAccentText, Modifier.weight(1f)) { onEquals() }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CalcButton(
    label: String,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(if (wide) 2.15f else 1f)
            .clip(if (wide) RoundedCornerShape(50) else CircleShape)
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            modifier = if (wide) Modifier.padding(start = 28.dp) else Modifier
        )
    }
}

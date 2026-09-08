package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.minecraft.network.chat.Component

/**
 * How much each step button moves the amount.
 *
 * A stack and a stack-of-stacks, plus one, because the amounts people actually type are overwhelmingly
 * multiples of those. Reaching 64 by pressing `+1` sixty-four times is the thing this exists to stop.
 */
private val DEFAULT_STEPS = listOf(1L, 10L, 64L)

/**
 * A number field flanked by symmetric decrement/increment buttons - `-64 -10 -1 [ 64 ] +1 +10 +64`.
 *
 * The field stays editable for an amount no combination of steps reaches conveniently; typing and
 * stepping stay in sync in both directions, and anything non-numeric is dropped as it is typed
 * rather than rejected at submission.
 *
 * Every result is clamped into [range], so a step that would overshoot lands on the bound instead
 * of being refused - pressing `+64` near the top is a request for "as much as possible", not a
 * mistake to punish.
 *
 * @param onAmountChange Invoked with the new, already-clamped amount.
 * @param steps Step sizes, ascending. Rendered mirrored either side of the field.
 */
@Composable
fun QuantityStepper(
	amount: Long,
	range: LongRange,
	onAmountChange: (Long) -> Unit,
	modifier: Modifier = Modifier,
	steps: List<Long> = DEFAULT_STEPS,
) {
	var text by remember { mutableStateOf(amount.toString()) }

	// Re-syncs the field when the amount moves from anywhere but the field itself (a step button, or
	// the caller resetting it). Guarded on the parsed value so it does not fight mid-edit typing.
	LaunchedEffect(amount) {
		if (text.toLongOrNull() != amount) text = amount.toString()
	}

	fun step(delta: Long) = onAmountChange((amount + delta).coerceIn(range.first, range.last))

	Row(horizontalArrangement = Arrangement.spacedBy(2), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
		for (size in steps.sortedDescending()) {
			Button(onClick = { step(-size) }, enabled = amount > range.first) {
				Text(Component.literal("-$size"), dropShadow = false)
			}
		}

		BasicTextField(
			value = text,
			onValueChange = { raw ->
				val digits = raw.filter { it.isDigit() }
				text = digits
				digits.toLongOrNull()?.let { onAmountChange(it.coerceIn(range.first, range.last)) }
			},
			modifier = Modifier.width(52),
		)

		for (size in steps) {
			Button(onClick = { step(size) }, enabled = amount < range.last) {
				Text(Component.literal("+$size"), dropShadow = false)
			}
		}
	}
}

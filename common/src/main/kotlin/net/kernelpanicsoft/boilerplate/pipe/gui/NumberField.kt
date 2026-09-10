package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.composables.input.textfield.TextFieldDefaults
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.input.onScroll
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.modifiers.sizeIn
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.util.KColor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.sign

/**
 * A number field with its own step buttons stacked at its right edge, and a wheel that steps it too.
 *
 * The compact way to set an amount. A [QuantityStepper] renders a button per rung either side of its
 * field and needs real width to do it; a slider needs a bounded range and a track long enough to
 * land on the value you meant, which is why the one this replaced had a ceiling chosen to keep the
 * track usable rather than because the number meant anything. This has neither constraint: the
 * range can run as far as the thing being edited actually allows, and the control stays the width of
 * a field plus a few pixels wherever it is used.
 *
 * The wheel is the reason it stays small and still reaches a large value. One notch moves by [step],
 * which by default reads the modifier keys - the same idea as
 * [scrollStepFor], and the same idea the ghost slots already use, so the wheel means the same thing
 * over an amount here as it does over an amount there.
 *
 * Every result is clamped into [range] before it reaches [onAmountChange], from the field, the
 * buttons and the wheel alike, and all three are inert while the control is not editable.
 *
 * The field's own appearance and input properties pass straight through, so one of these sits inside
 * a form beside a plain [BasicTextField] without the two disagreeing about what a disabled or
 * read-only field looks like.
 *
 * @param amount the current value; the field re-syncs to it whenever it moves from anywhere but the
 *   field itself, so typing is never fought over mid-edit.
 * @param range the settable bounds, inclusive.
 * @param onAmountChange invoked with the new, already-clamped amount.
 * @param modifier applied to the whole control, and so what the wheel is read over.
 * @param enabled whether the control takes input at all. Greys the field and both step buttons, and
 *   the wheel does nothing over it.
 * @param readOnly whether the value may be read but not changed. Unlike `!`[enabled] the control
 *   still draws as live and its text stays selectable; only the editing does nothing.
 * @param textColor colour of the number itself.
 * @param cursorColor colour of the field's caret.
 * @param selectionColor colour drawn behind selected text.
 * @param fieldWidth width of the number field itself, in pixels; the buttons sit outside it.
 * @param font the font the field measures itself by, and so what the step buttons are sized from.
 * @param step how far one notch or one button press moves. Consulted per press, so it may read the
 *   keyboard - see [decimalStep], the default.
 * @param leading drawn before the field - a caption, typically.
 * @param trailing drawn after the buttons - a unit, or a note about what the current value means.
 */
@Composable
fun NumberField(
	amount: Long,
	range: LongRange,
	onAmountChange: (Long) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	readOnly: Boolean = false,
	textColor: KColor = KColor.ofRgb(0xE0E0E0),
	cursorColor: KColor = KColor.ofRgb(0xFFD0D0D0.toInt()),
	selectionColor: KColor = KColor.ofRgb(-16776961),
	fieldWidth: Int = DEFAULT_FIELD_WIDTH,
	font: Font = Minecraft.getInstance().font,
	step: () -> Long = ::decimalStep,
	leading: @Composable () -> Unit = {},
	trailing: @Composable () -> Unit = {},
) {
	var text by remember { mutableStateOf(amount.toString()) }

	LaunchedEffect(amount) {
		if (text.toLongOrNull() != amount) text = amount.toString()
	}

	// The height the field beside them lays itself out at, which the two buttons split between them:
	// half each, so the pair stands exactly as tall as the field rather than overhanging it. Asked of
	// the field's own measurements rather than guessed - the padding is not derivable from the font,
	// so a number picked by eye lines up only until either changes.
	val spinnerSize = TextFieldDefaults.singleLineHeight(font)

	fun clamped(value: Long): Long = value.coerceIn(range.first, range.last)

	// Moves by the room actually left rather than by the step, so a press near a bound lands on it
	// instead of overshooting - and so a range reaching into Long's own extremes cannot be stepped
	// past the end into an overflow.
	fun nudge(notches: Int) {
		if (notches == 0) return
		val room = if (notches > 0) range.last - amount else amount - range.first
		if (room <= 0L) return
		val moved = minOf(step().coerceAtLeast(1L), room)
		onAmountChange(clamped(if (notches > 0) amount + moved else amount - moved))
	}

	Row(
		horizontalArrangement = Arrangement.spacedBy(2),
		verticalAlignment = Alignment.CenterVertically,
		// Gated exactly as the step buttons below are: a control that refuses a click and a keystroke
		// has no business still answering the wheel.
		modifier = modifier.onScroll<UINode> { _, event ->
			if (enabled && !readOnly) nudge(event.scrollY.sign.toInt())
		},
	) {
		leading()
		BasicTextField(
			value = text,
			// Non-digits are dropped as they are typed rather than rejected at submission, so the
			// field can only ever hold something parseable - and an emptied field is left empty
			// rather than snapping to a bound under the cursor.
			onValueChange = { raw ->
				val digits = raw.filter { it.isDigit() }
				text = digits
				digits.toLongOrNull()?.let { onAmountChange(clamped(it)) }
			},
			enabled = enabled,
			readOnly = readOnly,
			textColor = textColor,
			cursorColor = cursorColor,
			selectionColor = selectionColor,
			font = font,
			modifier = Modifier.width(fieldWidth),
		)
		Column(verticalArrangement = Arrangement.spacedBy(SPINNER_GAP), modifier = Modifier.sizeIn(maxHeight = spinnerSize)) {
			// Half the field's height apiece, with the glyph scaled to match - at this size a
			// full-size `+` is taller than the button drawn around it. `ignoreMinSize` is what lets
			// them be this small at all: the button theme declares a floor of twenty squared, and a
			// floor above the height they are being given is a constraint no node can satisfy - so
			// the pair silently did not draw rather than drawing small.
			Button(
				onClick = { if (enabled && !readOnly) nudge(1) },
				enabled = enabled && amount < range.last,
				modifier = Modifier.size(spinnerSize, (spinnerSize / 2) - 1),
				ignoreMinSize = true,
			) {
				Text(Component.literal("+"), dropShadow = false, fontScale = 0.5f)
			}
			Button(
				onClick = { if (enabled && !readOnly) nudge(-1) },
				enabled = enabled && amount > range.first,
				modifier = Modifier.size(spinnerSize, (spinnerSize / 2) - 1),
				ignoreMinSize = true,
			) {
				Text(Component.literal("-"), dropShadow = false, fontScale = 0.5f)
			}
		}
		trailing()
	}
}

/**
 * [NumberField]'s default notch: `1`, or `10` with shift, `100` with control, `1000` with both.
 *
 * A plain decimal ladder rather than a kind's own ([scrollStepFor]), because a field is not
 * always editing an amount of some one resource - a filter's batch size gates every kind that can
 * cross its face at once. A caller that *does* know its kind should pass that kind's ladder instead,
 * so the wheel moves by the amounts that kind is actually authored in.
 */
fun decimalStep(): Long = when {
	Screen.hasShiftDown() && Screen.hasControlDown() -> 1000L
	Screen.hasControlDown() -> 100L
	Screen.hasShiftDown() -> 10L
	else -> 1L
}

/** Wide enough for the five digits a bucket-scale amount needs. */
private const val DEFAULT_FIELD_WIDTH = 52

/**
 * Gap between the two step buttons - one pixel, so they read as a pair rather than as two buttons,
 * and so the pair plus the gap comes to the field's own height exactly.
 */
private const val SPINNER_GAP = 2

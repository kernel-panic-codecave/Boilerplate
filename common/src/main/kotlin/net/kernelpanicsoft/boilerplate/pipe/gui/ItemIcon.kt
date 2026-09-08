package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.MeasureResult
import net.kernelpanicsoft.archie.gui.layout.Renderer
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The label the shared font size is fitted to - the widest word this UI puts in a count corner.
 *
 * Every label draws at whatever size makes *this* string exactly fill [COUNT_MAX_WIDTH], measured
 * against the live font rather than an assumed glyph width. Sizing from a hardcoded scale is what
 * previously let `Craft` run off the left of its slot: the number was picked without measuring,
 * and a guess that is wrong by a pixel per glyph is wrong by five across a word. Fitting the
 * reference instead makes the text as large as it can be while every word label still fits, and
 * re-fits itself if the font ever changes.
 */
private const val COUNT_REFERENCE_TEXT = "Craft"

/** Never larger than vanilla's own count text, however much room a short label leaves spare. */
private const val COUNT_MAX_SCALE = 1f

/**
 * Horizontal room a label has, in unscaled pixels - the slot's own width, which the icon sits one
 * pixel inside of. Vanilla's count text already overhangs the icon box on the right, so a label
 * using the frame's own pixel on each side is in keeping rather than cramped.
 */
private const val COUNT_MAX_WIDTH = 18f

/** Glyph height of the vanilla font, used to keep a scaled label sitting on the same baseline as an unscaled one. */
private const val GLYPH_HEIGHT = 8f
/** Matches vanilla's own count-text Z bump ([GuiGraphics.renderItemDecorations]) so this doesn't lose a depth/paint-order tie against anything else drawn at the slot's own base Z. */
private const val COUNT_Z_OFFSET = 200f

/**
 * SI prefixes, largest first - deliberately stopping at tera.
 *
 * Consumer storage got people fluent in k/M/G/T and no further; peta and exa are datacenter
 * vocabulary that reads as noise in a slot corner. [formatCount] switches to scientific notation
 * past this list rather than reaching for prefixes nobody parses at a glance. Note `k` is
 * lowercase and giga is `G` - the SI spellings, where this previously wrote `K` and a
 * short-scale `B`.
 */
private val COUNT_TIERS = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "G", 1_000_000L to "M", 1_000L to "k")

/**
 * Rounds to the one-significant-decimal form a label shows: whole numbers from 10 up (`200G`),
 * a single decimal below that (`1.5k`).
 */
private fun roundMantissa(value: Double): Double =
	if (value >= 10) value.roundToInt().toDouble() else (value * 10).roundToInt() / 10.0

/** `2`, never `2.0` - a trailing zero costs a character the slot would rather spend elsewhere. */
private fun mantissaText(value: Double): String =
	if (value == floor(value)) value.toInt().toString() else value.toString()

/** `1.5E15`, `9.2E18` - the fallback for magnitudes past [COUNT_TIERS]. */
private fun scientificText(amount: Long): String {
	val exponent = floor(log10(amount.toDouble())).toInt()
	val mantissa = roundMantissa(amount.toDouble() / 10.0.pow(exponent))
	// Rounding can push the mantissa to 10, which would read "10E17" rather than "1E18".
	return if (mantissa >= 10) "1E${exponent + 1}" else "${mantissaText(mantissa)}E$exponent"
}

/**
 * Abbreviates [amount] for a slot's count corner: an SI prefix while one applies (`2k`, `20k`,
 * `1.5M`, `200G`), scientific notation beyond tera (`1.5E15`).
 *
 * A warehouse-aggregated total routinely runs into the thousands, and nineteen raw digits would
 * not fit at any size worth reading.
 */
internal fun formatCount(amount: Long): String {
	if (amount < 1000) return amount.toString()
	val tier = COUNT_TIERS.indexOfFirst { amount >= it.first }
	val (divisor, prefix) = COUNT_TIERS[tier]
	val mantissa = roundMantissa(amount.toDouble() / divisor)
	// Rounding can carry a mantissa up into the next tier: 999,999 would otherwise read "1000k"
	// instead of "1M". Carrying off the largest tier means tera has run out, so scientific takes it.
	if (mantissa >= 1000) return if (tier == 0) scientificText(amount) else "1${COUNT_TIERS[tier - 1].second}"
	return "${mantissaText(mantissa)}$prefix"
}

/**
 * The scale to draw a label at, given the measured widths of [COUNT_REFERENCE_TEXT] and of the
 * label itself, both in unscaled pixels.
 *
 * The shared size comes from [referenceWidth] alone, so every label is drawn at the same size
 * regardless of its own length - a `1` and a `Craft` in adjacent slots have to read as one UI
 * element. [textWidth] only matters for the backstop: something wider than the reference (the
 * six-character `9.2E18` end of [formatCount], say) narrows rather than bleeding over the
 * neighbouring slot.
 */
internal fun countScale(referenceWidth: Int, textWidth: Int): Float {
	val shared = if (referenceWidth <= 0) COUNT_MAX_SCALE else min(COUNT_MAX_SCALE, COUNT_MAX_WIDTH / referenceWidth)
	return if (textWidth <= 0 || textWidth * shared <= COUNT_MAX_WIDTH) shared else COUNT_MAX_WIDTH / textWidth
}

/**
 * Draws [text] right-aligned into the bottom-right of the icon at [x]/[y], where vanilla puts its
 * own count overlay, at the one size [countScale] gives for every label.
 *
 * The vertical offset grows as the scale shrinks so the label keeps sitting on vanilla's baseline
 * instead of floating mid-slot: vanilla's 1x text runs from `y + 9` to the bottom of the box, and
 * a half-height glyph starting at the same place would leave a visible gap beneath it.
 */
internal fun GuiGraphics.drawCount(font: Font, text: String, x: Int, y: Int) {
	if (text.isEmpty()) return
	val textWidth = font.width(text)
	val scale = countScale(font.width(COUNT_REFERENCE_TEXT), textWidth)
	val drawX = x + 19 - 2 - textWidth * scale
	val drawY = y + 9 + GLYPH_HEIGHT * (1 - scale)
	pose().pushPose()
	pose().translate(drawX, drawY, COUNT_Z_OFFSET)
	pose().scale(scale, scale, 1f)
	drawString(font, text, 0, 0, 0xFFFFFF, true)
	pose().popPose()
}

/**
 * Renders just [stack]'s real item icon plus a count overlay, natively 16x16 like vanilla's own
 * item rendering - no slot background of its own, see [SlotBackground] for that half and
 * [TerminalSlot] for the two combined.
 *
 * @param stack The item to render, icon plus [ItemStack.getCount] as the usual bottom-right count
 *   overlay - a warehouse terminal's aggregated total routinely exceeds what a real placed
 *   [ItemStack] would ever hold, and nothing here stops that (see [formatCount]).
 * @param countText Overrides the count overlay text entirely (still `null` by default, which
 *   falls back to [stack]'s own count, [formatCount]-abbreviated, exactly like vanilla's own item
 *   rendering falls back to the stack's raw count) - only worth setting when the displayed text
 *   needs to diverge from [stack]'s count itself. An empty string hides the overlay entirely.
 */
@Composable
fun ItemIcon(stack: ResourceStack<ItemResource>, countText: String? = null, modifier: Modifier = Modifier) {
	Layout(
		name = "ItemIcon",
		measurePolicy = { _, _, constraints -> MeasureResult(constraints.minWidth, constraints.minHeight) {} },
		renderer = object : Renderer {
			override fun render(
				node: UINode, x: Int, y: Int,
				guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
			) = guiGraphics {
				val itemStack = stack.resource.toStack(stack.amount.toInt())
				renderItem(itemStack, x, y)
				// Suppresses vanilla's own count text (an empty countText override still satisfies
				// its "count != 1 || countText != null" draw condition, so the durability
				// bar/cooldown overlay below it keeps rendering) - drawCount below replaces it
				// with one sized to fit this UI's labels rather than vanilla's 1-2 digits.
				renderItemDecorations(Minecraft.getInstance().font, itemStack, x, y, "")
				val text = countText ?: (if (stack.amount != 1L) formatCount(stack.amount) else "")
				drawCount(Minecraft.getInstance().font, text, x, y)
			}
		},
		modifier = Modifier.size(16, 16).then(modifier),
	)
}

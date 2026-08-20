package net.kernelpanicsoft.tubularstorage.pipe.gui

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
import kotlin.math.roundToInt

/** Text length at/above which [drawCount] shrinks the font - vanilla's own count text was never sized with anything past a plain 1-2 digit stack count in mind. */
private const val SHRINK_AT_LENGTH = 3
private const val SHRUNK_SCALE = 0.7f
/** Matches vanilla's own count-text Z bump ([GuiGraphics.renderItemDecorations]) so this doesn't lose a depth/paint-order tie against anything else drawn at the slot's own base Z. */
private const val COUNT_Z_OFFSET = 200f

private val COUNT_TIERS = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")

/**
 * Abbreviates [amount] with a metric-style suffix (`1k`, `1.1k`, `12k`, `1.2M`, ...) once it's
 * over 999 - a warehouse-aggregated total routinely runs into the thousands, where the raw digit
 * count would otherwise overflow a 16px slot's own count-text space regardless of [drawCount]'s
 * own shrink handling for merely-3-digit counts.
 */
internal fun formatCount(amount: Long): String {
	if (amount < 1000) return amount.toString()
	val (divisor, suffix) = COUNT_TIERS.first { amount >= it.first }
	val scaled = amount.toDouble() / divisor
	if (scaled >= 10) return "${scaled.roundToInt()}$suffix"
	val tenths = (scaled * 10).roundToInt()
	return if (tenths % 10 == 0) "${tenths / 10}$suffix" else "${tenths / 10.0}$suffix"
}

/**
 * Draws [text] right-aligned to [x]/[y] the same way vanilla's own item count overlay is
 * positioned, shrinking the font once [text] is [SHRINK_AT_LENGTH] characters or longer (an
 * abbreviated [formatCount] result, or just an ordinary 3-digit stack count) so it still fits
 * within the icon's own 16px box instead of overflowing past it at vanilla's fixed 1x size.
 */
private fun GuiGraphics.drawCount(font: Font, text: String, x: Int, y: Int) {
	if (text.isEmpty()) return
	val scale = if (text.length >= SHRINK_AT_LENGTH) SHRUNK_SCALE else 1f
	val drawX = x + 19 - 2 - font.width(text) * scale
	val drawY = y + 6 + 3
	pose().pushPose()
	pose().translate(drawX, drawY.toFloat(), COUNT_Z_OFFSET)
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
				// bar/cooldown overlay below it keeps rendering) - drawCount below replaces it with
				// one that can actually shrink to fit.
				renderItemDecorations(Minecraft.getInstance().font, itemStack, x, y, "")
				val text = countText ?: (if (stack.amount != 1L) formatCount(stack.amount) else "")
				drawCount(Minecraft.getInstance().font, text, x, y)
			}
		},
		modifier = Modifier.size(16, 16).then(modifier),
	)
}

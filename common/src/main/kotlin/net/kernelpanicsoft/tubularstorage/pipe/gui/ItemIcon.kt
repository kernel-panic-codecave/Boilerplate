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
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/**
 * Renders just [stack]'s real item icon plus a count overlay, natively 16x16 like vanilla's own
 * item rendering - no slot background of its own, see [SlotBackground] for that half and
 * [TerminalSlot] for the two combined.
 *
 * @param stack The item to render, icon plus [ItemStack.getCount] as the usual bottom-right count
 *   overlay - a warehouse terminal's aggregated total routinely exceeds what a real placed
 *   [ItemStack] would ever hold, and nothing here stops that.
 * @param countText Overrides the count overlay text entirely (still `null` by default, which
 *   falls back to [stack]'s own count exactly like vanilla's own item rendering does) - only worth
 *   setting when the displayed text needs to diverge from [stack]'s count itself.
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
				renderItem(stack.resource.toStack(stack.amount.toInt()), x, y)
				renderItemDecorations(Minecraft.getInstance().font, stack.resource.toStack(stack.amount.toInt()), x, y, countText)
			}
		},
		modifier = Modifier.size(16, 16).then(modifier),
	)
}

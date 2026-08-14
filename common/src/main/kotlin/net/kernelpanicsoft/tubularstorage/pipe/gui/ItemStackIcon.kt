package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.composables.theme.TextureStates
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.MeasureResult
import net.kernelpanicsoft.archie.gui.layout.Renderer
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.ThemeVariants
import net.kernelpanicsoft.archie.gui.util.extension.drawThemeState
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/**
 * Renders an arbitrary [stack]'s real item icon plus a count overlay on top of the same themed
 * slot background a real [net.kernelpanicsoft.archie.gui.Slot] draws, independent of the vanilla
 * `Slot`/`Container` system - for [WarehouseTerminalScreen]'s virtual, non-slot-backed result
 * list, where the displayed count (a whole warehouse's worth) routinely exceeds what a real placed
 * [ItemStack] would ever hold, and there's no backing `Slot`/`CommonStorage` to attach one to in
 * the first place.
 *
 * @param stack The item to render, icon plus [ItemStack.getCount] as the usual bottom-right count
 *   overlay - unlike a real placed stack, nothing stops [stack]'s own count being an aggregated
 *   warehouse total rather than something that ever fit in one slot.
 * @param countText Overrides the count overlay text entirely (still `null` by default, which
 *   falls back to [stack]'s own count exactly like vanilla's own item rendering does) - only worth
 *   setting when the displayed text needs to diverge from [stack]'s count itself.
 * @param size The icon's rendered width/height in pixels - vanilla item icons are natively 16x16.
 */
@Composable
fun ItemStackIcon(stack: ItemStack, countText: String? = null, size: Int = 16, modifier: Modifier = Modifier) {
	val theme = LocalTheme.current
	val slotState = theme.getComposableTheme("slot").getState(TextureStates.DEFAULT, ThemeVariants.DEFAULT)

	Layout(
		name = "ItemStackIcon",
		measurePolicy = { _, _, constraints -> MeasureResult(constraints.minWidth, constraints.minHeight) {} },
		renderer = object : Renderer {
			override fun render(
				node: UINode, x: Int, y: Int,
				guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
			) = guiGraphics {
				drawThemeState(slotState, x, y, node.width, node.height)
				renderItem(stack, x, y)
				renderItemDecorations(Minecraft.getInstance().font, stack, x, y, countText)
			}
		},
		modifier = Modifier.size(size, size).then(modifier),
	)
}

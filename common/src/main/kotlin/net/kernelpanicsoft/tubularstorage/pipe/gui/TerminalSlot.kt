package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.composables.theme.TextureStates
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.BoxMeasurePolicy
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.Renderer
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.util.extension.drawThemeState
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * One cell of a virtual (non-slot-backed) result grid, e.g. [TerminalHookScreen]'s - the same
 * 18x18 [SlotBackground] every cell shows regardless of whether it's occupied, an [ItemIcon]
 * layered on top only when [stack] is non-`null` (inset 1px, matching a real slot's own item
 * position), [SlotHighlight] on top of *that* while hovered - matching vanilla, where any active
 * slot highlights on hover regardless of whether it currently holds an item, not just occupied
 * ones. An empty cell still hover-highlights, then, but isn't clickable and shows no hand cursor -
 * there's nothing a click on it would do, unlike an occupied one.
 *
 * @param onHovered Told whenever this cell's own hovered state changes, so the caller (see
 *   [TerminalHookScreen.hoveredStack]/[TerminalHookScreen.renderTooltip]) can track which single stack (if
 *   any) across the whole grid should get a tooltip.
 */
@Composable
fun TerminalSlot(stack: ResourceStack<ItemResource>?, onClick: () -> Unit = {}, onHovered: (Boolean) -> Unit = {}, modifier: Modifier = Modifier) {
	Clickable(showHandCursor = stack != null, onClick = { onClick() }, modifier = modifier) { isHovered, _, _ ->
		LaunchedEffect(isHovered) { onHovered(isHovered) }
		FakeSlot(stack, isHovered)
	}
}

@Composable
fun FakeSlot(stack: ResourceStack<ItemResource>?, isHovered: Boolean, countText: String? = null)
{
	val theme = LocalTheme.current
	val slotState = theme.getComposableTheme("slot").getState(TextureStates.DEFAULT, theme.mode)

	Layout(
		name = "FakeSlot",
		measurePolicy = BoxMeasurePolicy(Alignment.Center),
		modifier = Modifier.size(18, 18),
		renderer = object : Renderer {
			override fun render(
				node: UINode,
				x: Int,
				y: Int,
				guiGraphics: GuiGraphics,
				mouseX: Int,
				mouseY: Int,
				partialTick: Float
			) = guiGraphics {
				drawThemeState(slotState, x, y, node.width, node.height)
			}

			override fun renderAfterChildren(
				node: UINode,
				x: Int,
				y: Int,
				guiGraphics: GuiGraphics,
				mouseX: Int,
				mouseY: Int,
				partialTick: Float
			)
			{
				if (isHovered)
					AbstractContainerScreen.renderSlotHighlight(guiGraphics, x + 1, y + 1, 0)
			}
		}
	) {
		if (stack != null) ItemIcon(stack, countText)
	}
}

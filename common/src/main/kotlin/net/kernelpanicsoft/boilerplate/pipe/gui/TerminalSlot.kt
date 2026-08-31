package net.kernelpanicsoft.boilerplate.pipe.gui

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
 * One cell of a virtual (non-slot-backed) result grid, e.g. [StoreResultsGrid]'s - the same
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
 * @param countText Overrides the vanilla count-label decoration (e.g. `""` to hide it entirely) -
 *   for a cell whose [stack]'s own `amount` is a display placeholder rather than a real count, like
 *   an autocraftable-but-out-of-stock entry showing "Craft" instead of `0`.
 * @param handleClick Offered a [clickHandler]-registered action only when [stack] is
 *   non-`null` and the caller passes one (e.g. [StoreResultsGrid]'s own "open the autocraft dialog
 *   for an in-stock, also-craftable entry" interaction) - see [ClickHandler]'s own KDoc for
 *   why detecting the actual click happens one level up, at the screen.
 * @param enabled When `false`, draws [TextureStates.DISABLED]'s own slot texture instead of
 *   [TextureStates.DEFAULT] and ignores clicks entirely - the whole-terminal pressure gate (see
 *   [StoreResultsGrid]) rather than a per-cell concept.
 */
@Composable
fun TerminalSlot(
	stack: ResourceStack<ItemResource>?,
	onClick: () -> Unit = {},
	onHovered: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
	countText: String? = null,
	clickHandler: ClickHandler? = null,
	handleClick: (() -> Unit)? = null,
	enabled: Boolean = true,
) {
	Clickable(showHandCursor = stack != null && enabled, enabled = enabled, onClick = { onClick() }, modifier = modifier) { isHovered, _, _ ->
		LaunchedEffect(isHovered, handleClick) {
			onHovered(isHovered)
			clickHandler?.setHovered(if (isHovered && enabled) handleClick else null)
		}
		FakeSlot(stack, isHovered, countText, enabled)
	}
}

@Composable
fun FakeSlot(stack: ResourceStack<ItemResource>?, isHovered: Boolean, countText: String? = null, enabled: Boolean = true)
{
	val theme = LocalTheme.current
	val slotState = theme.getComposableTheme("slot").getState(if (enabled) TextureStates.DEFAULT else TextureStates.DISABLED, theme.mode)

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
				if (isHovered && enabled)
					AbstractContainerScreen.renderSlotHighlight(guiGraphics, x + 1, y + 1, 0)
			}
		}
	) {
		if (stack != null) ItemIcon(stack, countText)
	}
}

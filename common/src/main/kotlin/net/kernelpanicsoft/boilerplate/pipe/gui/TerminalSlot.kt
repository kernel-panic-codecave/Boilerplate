package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
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
import androidx.compose.runtime.getValue

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
 * @param handleRightClick The same, for a right-click - [StoreResultsGrid] uses it for "empty the
 *   container I am holding into the network", the bundle's own gesture for the same idea. A
 *   left-click still means "store the item itself", because a filled bucket is a reasonable thing
 *   to want either done with.
 * @param enabled When `false`, draws [TextureStates.DISABLED]'s own slot texture instead of
 *   [TextureStates.DEFAULT] and ignores clicks entirely - the whole-terminal pressure gate (see
 *   [StoreResultsGrid]) rather than a per-cell concept.
 */
@Composable
fun TerminalSlot(
	stack: ResourceStack<*>?,
	onClick: () -> Unit = {},
	onHovered: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
	countText: String? = null,
	clickHandler: ClickHandler? = null,
	handleClick: (() -> Unit)? = null,
	rightClickHandler: ClickHandler? = null,
	handleRightClick: (() -> Unit)? = null,
	enabled: Boolean = true,
) {
	Clickable(showHandCursor = stack != null && enabled, enabled = enabled, onClick = { onClick() }, modifier = modifier) { isHovered, _, _ ->
		// Keyed on the hover state itself, never on [handleClick] - that lambda is freshly
		// allocated each recomposition, so keying on it restarted this effect on every pass and
		// re-reported hover for all 27 cells continuously. The registered action reads the latest
		// lambda through the wrapper rather than capturing whichever one was current when the
		// effect happened to run.
		val currentHandleClick by rememberUpdatedState(handleClick)
		val currentHandleRightClick by rememberUpdatedState(handleRightClick)
		LaunchedEffect(isHovered, enabled, handleClick != null) {
			onHovered(isHovered)
			clickHandler?.setHovered(if (isHovered && enabled && handleClick != null) ({ currentHandleClick?.invoke() }) else null)
		}
		// Its own effect rather than a second line in the one above: the two are keyed on different
		// things, and a cell may offer one without the other.
		LaunchedEffect(isHovered, enabled, handleRightClick != null) {
			rightClickHandler?.setHovered(if (isHovered && enabled && handleRightClick != null) ({ currentHandleRightClick?.invoke() }) else null)
		}
		// Whatever kind the row happens to hold draws its own face - a terminal lists everything the
		// network stores, which is no longer only items. A kind with no display of its own still
		// gets the empty frame rather than a hole in the grid.
		val resource = stack?.resource as? ResourceComponent
		val display = resource?.let { ResourceKindRegistry.forResource(it)?.display }
		if (resource == null || display == null) FakeSlot(null, isHovered, countText, enabled)
		else display.SlotFace(resource, stack.amount, isHovered, countText, enabled)
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

/**
 * [FakeSlot] for a resource of any registered kind - renders an item exactly as [FakeSlot] does and
 * a fluid as its still sprite, tinted, the way [FluidGhostSlot] draws one.
 *
 * Exists because the crafting UI shows whatever a job or pattern actually names, and that may now
 * be a fluid: a job crafting a fluid output, a pattern step consuming one. Dispatching here rather
 * than at each call site keeps every one of those surfaces a single call.
 */
@Composable
fun ResourceFakeSlot(resource: ResourceComponent, amount: Long, isHovered: Boolean = false) {
	// A kind with no display of its own still gets the empty slot frame rather than nothing at all,
	// so an addon resource leaves a visible hole in the layout instead of collapsing it.
	val display = ResourceKindRegistry.forResource(resource)?.display
	if (display == null) FakeSlot(null, isHovered)
	else display.SlotFace(resource, amount, isHovered, amountLabelFor(resource, amount), enabled = true)
}

/**
 * The corner label a cell should show for [amount] of [resource], or `null` for a kind whose face
 * writes its own.
 *
 * An item stack draws its count itself (see
 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind.drawsOwnAmount]); a sprite draws nothing, so
 * everything else needs the number handed to it. In the kind's own **authored** unit - millibuckets,
 * not the droplets Fabric counts fluids in - because this is a label a player reads.
 *
 * Every surface that draws a resource cell goes through here rather than deciding for itself, which
 * is what stopped fluids and chemicals from silently showing no amount at all: each caller happened
 * to pass `null` and nothing downstream could tell that apart from "this kind writes its own".
 */
internal fun amountLabelFor(resource: ResourceComponent?, amount: Long): String? {
	val kind = ResourceKindRegistry.forResource(resource ?: return null) ?: return null
	return authoredAmountLabelFor(resource, kind.toAuthored(amount))
}

/**
 * [amountLabelFor] for an amount that is *already* in the kind's authored unit.
 *
 * Both units are in play across the GUI and only the surface holding the number knows which one it
 * has: a warehouse's stock and a stocking row's target are platform amounts, while a pattern
 * terminal's ghost cells are authored all the way until
 * [net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu.encode] converts them. Running
 * an authored amount through [amountLabelFor] would convert it a second time, which on Fabric turns
 * a 1000mB cell into a "12" and on NeoForge silently looks right.
 */
internal fun authoredAmountLabelFor(resource: ResourceComponent?, authored: Long): String? {
	if (resource == null || resource.isBlank) return null
	val kind = ResourceKindRegistry.forResource(resource) ?: return null
	if (kind.drawsOwnAmount) return null
	return formatCount(authored)
}

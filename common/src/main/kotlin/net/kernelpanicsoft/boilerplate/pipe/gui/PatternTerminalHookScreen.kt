package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layout.*
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.RequestPatternGridPreviewPacket
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

/**
 * [TerminalHookScreen]'s own single Store tab, plus a ghost-grid pattern authoring area right
 * underneath the output slots (no dedicated "Encode" tab, matching [CraftingTerminalHookScreen]'s
 * own grid-in-Store placement): a [RadioGroup] toggles [PatternKind.CRAFTING] (a real vanilla
 * recipe match, single derived output - the ghost grid's own inputs matter positionally) against
 * [PatternKind.PROCESSING] (an unordered ingredient bag, up to 9 manually-specified ghost outputs,
 * each with a scroll-to-adjust amount), a real, persistent [Slots] row holds the blank
 * [net.kernelpanicsoft.boilerplate.crafting.PatternItem] stack encoding draws from, and an
 * "Encode" button drives [PatternTerminalHookMenu.requestEncode]. A near-duplicate of
 * [TerminalHookScreen] for the same reason [CraftingTerminalHookScreen] duplicates it - see that
 * class's own KDoc.
 */
class PatternTerminalHookScreen(menu: PatternTerminalHookMenu, playerInventory: Inventory, title: Component) :
	AbstractTerminalHookScreen<PatternTerminalHookMenu>(menu, playerInventory, title)
{
	override val mainTabId: String get() = "encode"
	override val mainTabLabel: Component get() = Component.literal("Encode")

	@Composable
	override fun additionalContent()
	{
		LaunchedEffect(Unit) {
			while (true)
			{
				BoilerplateNetworkChannel.toServer(RequestPatternGridPreviewPacket)
				delay(GRID_PREVIEW_POLL_MILLIS.milliseconds)
			}
		}
		var kind by remember { mutableStateOf(menu.currentPatternKind()) }
		var inputs by remember { mutableStateOf(menu.currentGhostInputs()) }
		var outputs by remember { mutableStateOf(menu.currentGhostOutputs()) }

		fun setInput(index: Int, resource: ResourceComponent, amount: Long)
		{
			inputs = inputs.toMutableList().also { it[index] = resource to amount }
			menu.setGhostInput(index, resource, amount)
		}

		fun setOutput(index: Int, resource: ResourceComponent, amount: Long)
		{
			outputs = outputs.toMutableList().also { it[index] = resource to amount }
			menu.setGhostOutput(index, resource, amount)
		}

		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			RadioGroup(
				options = listOf(
					RadioOption(PatternKind.CRAFTING, Component.literal("Crafting")),
					RadioOption(PatternKind.PROCESSING, Component.literal("Processing")),
				),
				selected = kind,
				onSelected = { kind = it; menu.setPatternKind(it) },
			)
			Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(contentWidth)) {
				Row(horizontalArrangement = Arrangement.spacedBy(18)) {
					Column {
						Text(Component.literal("Inputs"), dropShadow = false)
						// A CRAFTING pattern is matched against a real vanilla recipe, which knows
						// only items and only one per cell - so that mode keeps the item-only grid,
						// which simply cannot be handed a fluid, rather than accepting one and
						// failing at encode time. PROCESSING takes either kind, with a per-cell
						// count.
						if (kind == PatternKind.CRAFTING) {
							GhostSlotGrid(
								resources = inputs.map { it.first as? ItemResource ?: ItemResource.BLANK },
								columns = 3,
								carried = { menu.carried },
								onPlace = { index, resource -> setInput(index, resource, 1) },
								onClear = { index -> setInput(index, ItemResource.BLANK, 1) },
								clickHandler = clickHandler,
								handleClick = { null },
								amounts = inputs.map { it.second },
								onAmountScroll = null,
							)
						} else {
							ResourceGhostSlotGrid(
								resources = inputs.map { it.first },
								columns = 3,
								carried = { menu.carried },
								onPlace = { index, resource -> setInput(index, resource, defaultCellAmount(resource, inputs.getOrNull(index))) },
								onClear = { index -> setInput(index, ItemResource.BLANK, 1) },
								clickHandler = clickHandler,
								handleClick = { null },
								amounts = inputs.map { it.second },
								onAmountScroll = { index, delta ->
									val (resource, amount) = inputs.getOrNull(index) ?: return@ResourceGhostSlotGrid
									if (!resource.isBlank) setInput(index, resource, stepCellAmount(resource, amount, delta))
								},
							)
						}
					}
					Column {
						Text(Component.literal("Outputs"), dropShadow = false)

						when (kind)
						{
							PatternKind.CRAFTING ->
							{
								Box(modifier = Modifier.size(18 * 3), contentAlignment = Alignment.Center) {
									TerminalSlot(menu.gridPreview)
								}
							}

							PatternKind.PROCESSING ->
							{

								ResourceGhostSlotGrid(
									resources = outputs.map { it.first },
									columns = 3,
									carried = { menu.carried },
									onPlace = { index, resource -> setOutput(index, resource, defaultCellAmount(resource, outputs.getOrNull(index))) },
									onClear = { index -> setOutput(index, ItemResource.BLANK, 1) },
									clickHandler = clickHandler,
									handleClick = { null },
									amounts = outputs.map { it.second },
									onAmountScroll = { index, delta ->
										val (resource, amount) = outputs.getOrNull(index) ?: return@ResourceGhostSlotGrid
										if (!resource.isBlank) setOutput(index, resource, stepCellAmount(resource, amount, delta))
									},
								)
							}
						}
					}
					Column(verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
						Text(Component.literal(""), dropShadow = false)
						Slots("blankPatterns", 1, 1)
						Button(onClick = { menu.requestEncode() }) {
							Text(Component.literal("↓"), dropShadow = false)
						}
						Slots("patternOutput", 1, 1)
					}
				}
			}


		}
	}

	companion object
	{
		private const val GRID_PREVIEW_POLL_MILLIS = 150L

		/** How far one scroll notch moves an item cell's count, and its ceiling - a stack. */
		private const val ITEM_STEP = 1L
		private const val ITEM_MAX = 64L

		/**
		 * The same for a fluid cell, in **millibuckets** - the unit the player types and reads, and
		 * the unit a ghost amount is stored in for a fluid (converted to whatever the platform
		 * counts in only at encode time, see
		 * [net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu.encode]).
		 *
		 * A 100mB notch reaches every amount real recipes actually use - 100, 250 (as 200/300 in
		 * two notches from either side is close enough to be worth the coarser step), 500, 1000 -
		 * in a handful of scrolls, where a 1mB notch would need a thousand of them for one bucket.
		 */
		private const val FLUID_STEP_MILLIBUCKETS = 100L
		private const val FLUID_MAX_MILLIBUCKETS = 64_000L

		/**
		 * What a cell's amount should become when [resource] is dropped into it - [existing]'s own
		 * amount if that cell already held the same *kind* (so re-picking a fluid keeps the
		 * 250mB you dialled in), otherwise the kind's own sensible default: one item, or one
		 * bucket.
		 *
		 * Without the kind check, replacing an item cell with a fluid would inherit `1`, which for
		 * a fluid means one millibucket - a pattern that looks right and consumes nothing.
		 */
		private fun defaultCellAmount(resource: ResourceComponent, existing: Pair<ResourceComponent, Long>?): Long {
			val sameKind = existing != null && (existing.first is FluidResource) == (resource is FluidResource) && !existing.first.isBlank
			if (sameKind) return existing!!.second
			return if (resource is FluidResource) MILLIBUCKETS_PER_BUCKET else 1L
		}

		/** One bucket, in millibuckets - a fluid cell's own default amount. */
		private const val MILLIBUCKETS_PER_BUCKET = 1000L

		/** [amount] moved [delta] notches, at whatever step and ceiling [resource]'s own kind uses. */
		private fun stepCellAmount(resource: ResourceComponent, amount: Long, delta: Int): Long {
			val isFluid = resource is FluidResource
			val step = if (isFluid) FLUID_STEP_MILLIBUCKETS else ITEM_STEP
			val max = if (isFluid) FLUID_MAX_MILLIBUCKETS else ITEM_MAX
			return (amount + delta * step).coerceIn(step, max)
		}
	}
}

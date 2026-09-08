package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layout.*
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.RequestPatternGridPreviewPacket
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
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

		// Putting an already-encoded pattern into the result slot loads it back into the grid, so a
		// pattern can be corrected without authoring it again from scratch - and, since
		// [net.kernelpanicsoft.boilerplate.crafting.PatternEncoder.encodeAndConsume] rewrites an
		// occupied result slot in place rather than needing a fresh blank, encoding afterwards
		// overwrites that same pattern item.
		//
		// Driven from the slot's own contents rather than from a click, so it also fires for a
		// pattern that arrives any other way (a shift-click, a hopper). [loaded] is what stops it
		// re-applying once loaded; the grid stays editable after a load precisely because the slot's
		// pattern does not change while you edit.
		var loaded by remember { mutableStateOf<Pattern?>(null) }
		fun loadPattern(pattern: Pattern) {
			loaded = pattern

			kind = pattern.kind
			menu.setPatternKind(pattern.kind)
			for ((index, cell) in menu.inputCellsOf(pattern).withIndex()) setInput(index, cell.first, cell.second)
			// A CRAFTING pattern's outputs are the vanilla recipe's own assembled result, which this
			// screen derives live rather than letting anyone author - so only a PROCESSING pattern
			// restores them, and the other kind clears them rather than leaving a stale row behind.
			val restored = if (pattern.kind == PatternKind.PROCESSING) menu.outputCellsOf(pattern) else null
			for (index in outputs.indices) {
				val cell = restored?.getOrNull(index) ?: (ItemResource.BLANK to 1L)
				setOutput(index, cell.first, cell.second)
			}
		}

		// Polled rather than read during composition: the result slot's contents live in an ordinary
		// storage, not in Compose state, so reading them while composing subscribes to nothing and
		// the load would only ever fire on some unrelated recomposition - in practice, on reopening
		// the screen. The grid preview above polls for the same reason.
		LaunchedEffect(Unit) {
			while (true) {
				val pattern = menu.encodedPatternInOutput()
				if (pattern != null && pattern != loaded) loadPattern(pattern)
				delay(PATTERN_LOAD_POLL_MILLIS.milliseconds)
			}
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
						Label(Component.literal("Inputs"))
						// A CRAFTING pattern is matched against a real vanilla recipe, which knows
						// only one per cell and only kinds a vanilla grid can hold - so that mode
						// refuses a cell of any other kind on the way in, rather than accepting one
						// and failing at encode time. PROCESSING takes any kind, with a per-cell
						// count.
						if (kind == PatternKind.CRAFTING) {
							ResourceGhostSlotGrid(
								resources = inputs.map { it.first },
								columns = 3,
								carried = { menu.carried },
								onPlace = { index, resource ->
									if (ResourceKindRegistry.forResource(resource)?.vanillaCraftable == true) {
										setInput(index, resource, 1)
									}
								},
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
						Label(Component.literal("Outputs"))

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
						Label(Component.literal(""))
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

		/** How often the result slot is checked for a pattern to load back into the grid - a purely local read, so this is only ever bounded by how quickly the load should feel. */
		private const val PATTERN_LOAD_POLL_MILLIS = 150L

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
			val kind = ResourceKindRegistry.forResource(resource)
			val sameKind = existing != null && !existing.first.isBlank &&
				ResourceKindRegistry.forResource(existing.first) === kind
			if (sameKind) return existing!!.second
			return kind?.defaultAuthored ?: 1L
		}

		/** [amount] moved [delta] notches, at whatever step and ceiling [resource]'s own kind uses. */
		private fun stepCellAmount(resource: ResourceComponent, amount: Long, delta: Int): Long {
			val kind = ResourceKindRegistry.forResource(resource)
			val step = kind?.authoredStep ?: 1L
			val max = kind?.maxAuthored ?: 64L
			return (amount + delta * step).coerceIn(step, max)
		}
	}
}

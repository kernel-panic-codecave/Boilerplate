package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
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

		fun setInput(index: Int, resource: ItemResource, amount: Long)
		{
			inputs = inputs.toMutableList().also { it[index] = resource to amount }
			menu.setGhostInput(index, resource, amount)
		}

		fun setOutput(index: Int, resource: ItemResource, amount: Long)
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
						GhostSlotGrid(
							resources = inputs.map { it.first },
							columns = 3,
							carried = { menu.carried },
							onPlace = { index, resource -> setInput(index, resource, inputs.getOrNull(index)?.second ?: 1) },
							onClear = { index -> setInput(index, ItemResource.BLANK, 1) },
							clickHandler = clickHandler,
							handleClick = { null },
							amounts = inputs.map { it.second },
							// Scrollable only for PROCESSING: a CRAFTING pattern's grid is matched
							// against a real vanilla recipe and is one-item-per-cell, so a per-cell
							// count there would be meaningless (and is ignored at encode time).
							onAmountScroll = if (kind != PatternKind.PROCESSING) null else { index, delta ->
								val (resource, amount) = inputs.getOrNull(index) ?: return@GhostSlotGrid
								if (!resource.isBlank) setInput(index, resource, (amount + delta).coerceIn(1, 64))
							},
						)
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

								GhostSlotGrid(
									resources = outputs.map { it.first },
									columns = 3,
									carried = { menu.carried },
									onPlace = { index, resource ->
										setOutput(
											index,
											resource,
											outputs.getOrNull(index)?.second ?: 1
										)
									},
									onClear = { index -> setOutput(index, ItemResource.BLANK, 1) },
									clickHandler = clickHandler,
									handleClick = { null },
									amounts = outputs.map { it.second },
									onAmountScroll = { index, delta ->
										val (resource, amount) = outputs.getOrNull(index) ?: return@GhostSlotGrid
										if (!resource.isBlank) setOutput(
											index,
											resource,
											(amount + delta).coerceIn(1, 64)
										)
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
	}
}

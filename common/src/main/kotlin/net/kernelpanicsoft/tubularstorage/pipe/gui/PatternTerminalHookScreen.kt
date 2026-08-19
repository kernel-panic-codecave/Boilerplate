package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.network.RequestCraftJobTreePacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftableListPacket
import net.kernelpanicsoft.tubularstorage.network.RequestTerminalSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.RequestWarehouseDefragPacket
import net.kernelpanicsoft.tubularstorage.network.SItemResource
import net.kernelpanicsoft.tubularstorage.network.SResourceStack
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.util.itemStack
import net.kernelpanicsoft.tubularstorage.util.resourceStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * [TerminalHookScreen]'s own single Store tab, plus a ghost-grid pattern authoring area right
 * underneath the output slots (no dedicated "Encode" tab, matching [CraftingTerminalHookScreen]'s
 * own grid-in-Store placement): a [RadioGroup] toggles [PatternKind.CRAFTING] (a real vanilla
 * recipe match, single derived output - the ghost grid's own inputs matter positionally) against
 * [PatternKind.PROCESSING] (an unordered ingredient bag, up to 9 manually-specified ghost outputs,
 * each with a scroll-to-adjust amount), a real, persistent [Slots] row holds the blank
 * [net.kernelpanicsoft.tubularstorage.crafting.PatternItem] stack encoding draws from, and an
 * "Encode" button drives [PatternTerminalHookMenu.requestEncode]. A near-duplicate of
 * [TerminalHookScreen] for the same reason [CraftingTerminalHookScreen] duplicates it - see that
 * class's own KDoc.
 */
class PatternTerminalHookScreen(private val menu: PatternTerminalHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<PatternTerminalHookMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * COLUMNS
	private val middleClickHandler = MiddleClickHandler()

	private var hoveredStack: SResourceStack<SItemResource>? = null
	private var sidebarTooltip: String? = null

	init {
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (middleClickHandler.tryHandle(button)) return true
		return super.mouseClicked(mouseX, mouseY, button)
	}

	@Composable
	fun content() {
		Theme {
			Box(contentAlignment = Alignment.Center) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(2),
					verticalAlignment = Alignment.Top
				) {
					var viewMode by remember { mutableStateOf(StoreViewMode.BOTH) }
					Column {
						SidebarButton("↻", "Refresh", { sidebarTooltip = it }) {
							TubularStorageNetworkChannel.toServer(RequestTerminalSearchResultsPacket)
							TubularStorageNetworkChannel.toServer(RequestCraftableListPacket)
						}
						SidebarButton("⥮", "Defragment warehouses", { sidebarTooltip = it }) {
							TubularStorageNetworkChannel.toServer(RequestWarehouseDefragPacket)
						}
						StoreViewModeButton(viewMode, { sidebarTooltip = it }) { viewMode = it }
					}
					TabContainerPanel(contentWidth = contentWidth) {
						tab(id = "store", title = Component.literal("Store")) { storeTab(viewMode) }
						tab(id = "tree", title = Component.literal("Tree")) { treeTab() }
					}
				}
			}
		}
	}

	@Composable
	private fun storeTab(viewMode: StoreViewMode) {
		val layers = LocalLayerManager.current
		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			StoreResultsGrid(
				results = menu.results,
				craftable = menu.craftableResources,
				mode = viewMode,
				contentWidth = contentWidth,
				carried = { menu.carried },
				onDepositCarried = { menu.requestDeposit(menu.carried.resourceStack, true) },
				onRequestWithdraw = { stack -> layers.requestQuantityDialog(stack) { amount -> menu.requestWithdraw(stack.withCount(amount)) } },
				onRequestCraft = { resource -> layers.requestCraftQuantityDialog(menu, resource) { amount -> menu.requestCraft(ResourceStack(resource, amount)) } },
				middleClickHandler = middleClickHandler,
				onHoveredStackChanged = { hoveredStack = it },
			)
			Slots("output", COLUMNS, 1)
			patternAuthoringArea()
		}
	}

	@Composable
	private fun patternAuthoringArea() {
		var kind by remember { mutableStateOf(menu.currentPatternKind()) }
		var inputs by remember { mutableStateOf(menu.currentGhostInputs()) }
		var outputs by remember { mutableStateOf(menu.currentGhostOutputs()) }

		fun setInput(index: Int, resource: ItemResource) {
			inputs = inputs.toMutableList().also { it[index] = resource }
			menu.setGhostInput(index, resource)
		}

		fun setOutput(index: Int, resource: ItemResource, amount: Long) {
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

			Row(horizontalArrangement = Arrangement.spacedBy(18)) {
				Column {
					Text(Component.literal("Inputs"), dropShadow = false)
					GhostSlotGrid(
						resources = inputs,
						columns = 3,
						carried = { menu.carried },
						onPlace = { index, resource -> setInput(index, resource) },
						onClear = { index -> setInput(index, ItemResource.BLANK) },
						middleClickHandler = middleClickHandler,
						onMiddleClick = { null },
					)
				}

				if (kind == PatternKind.PROCESSING) {
					Column {
						Text(Component.literal("Outputs (scroll to adjust)"), dropShadow = false)
						GhostSlotGrid(
							resources = outputs.map { it.first },
							columns = 3,
							carried = { menu.carried },
							onPlace = { index, resource -> setOutput(index, resource, outputs.getOrNull(index)?.second ?: 1) },
							onClear = { index -> setOutput(index, ItemResource.BLANK, 1) },
							middleClickHandler = middleClickHandler,
							onMiddleClick = { null },
							amounts = outputs.map { it.second },
							onAmountScroll = { index, delta ->
								val (resource, amount) = outputs.getOrNull(index) ?: return@GhostSlotGrid
								if (!resource.isBlank) setOutput(index, resource, (amount + delta).coerceIn(1, 64))
							},
						)
					}
				} else {
					Text(
						Component.literal("Encodes the vanilla recipe matching the input grid."),
						dropShadow = false,
						color = LocalTheme.current.darkTextColor,
					)
				}
			}

			Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = Alignment.CenterVertically) {
				Text(Component.literal("Blanks"), dropShadow = false)
				Slots("blankPatterns", 1, 1)
				Button(onClick = { menu.requestEncode() }) {
					Text(Component.literal("Encode"), dropShadow = false)
				}
			}
		}
	}

	@Composable
	private fun treeTab() {
		LaunchedEffect(Unit) {
			while (true) {
				TubularStorageNetworkChannel.toServer(RequestCraftJobTreePacket)
				delay(STATUS_POLL_MILLIS)
			}
		}
		CraftingTreeView(menu.craftTree, modifier = Modifier.size(contentWidth, 18 * VISIBLE_ROWS))
	}

	override fun renderTooltip(guiGraphics: GuiGraphics, x: Int, y: Int) {
		super.renderTooltip(guiGraphics, x, y)
		sidebarTooltip?.let { guiGraphics.renderTooltip(font, Component.literal(it), x, y) }
			?: hoveredStack?.let { guiGraphics.renderTooltip(font, it.itemStack, x, y) }
	}

	companion object {
		private const val COLUMNS = 9
		private const val VISIBLE_ROWS = 3
		private const val STATUS_POLL_MILLIS = 250L
	}
}

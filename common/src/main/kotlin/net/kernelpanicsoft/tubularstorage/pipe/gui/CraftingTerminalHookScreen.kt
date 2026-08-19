package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.Theme
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
import net.minecraft.world.item.ItemStack

/**
 * [TerminalHookScreen]'s own three tabs, plus a fourth "Assemble" one: a real 3x3 grid + result
 * slot ([net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookState.grid]/`.result`),
 * filled by hand or by anything physically piped to this terminal's own position, with a "Craft"
 * button triggering [CraftingTerminalHookMenu.requestCraftGrid] - instant, manual network-backed
 * crafting, distinct from the Craft tab's slow pattern-driven on-demand requests. A near-duplicate
 * of [TerminalHookScreen] for the same reason [CraftingTerminalHookMenu] duplicates
 * [TerminalHookMenu] - see its own KDoc.
 */
class CraftingTerminalHookScreen(private val menu: CraftingTerminalHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<CraftingTerminalHookMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * COLUMNS

	private var hoveredStack: SResourceStack<SItemResource>? = null

	init {
		start { content() }
	}

	@Composable
	fun content() {
		Theme {
			Box(contentAlignment = Alignment.Center) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(2),
					verticalAlignment = Alignment.Top
				) {
					Column {
						Button(onClick = {
							TubularStorageNetworkChannel.toServer(RequestTerminalSearchResultsPacket)
							TubularStorageNetworkChannel.toServer(RequestCraftableListPacket)
						}) {
							Text(Component.literal("↻"), dropShadow = false)
						}
						Button(onClick = { TubularStorageNetworkChannel.toServer(RequestWarehouseDefragPacket) }) {
							Text(Component.literal("⥮"), dropShadow = false)
						}
					}
					TabContainerPanel(contentWidth = contentWidth) {
						tab(id = "store", title = Component.literal("Store")) { storeTab() }
						tab(id = "craft", title = Component.literal("Craft")) { craftTab() }
						tab(id = "assemble", title = Component.literal("Assemble")) { assembleTab() }
					}
				}
			}
		}
	}

	@Composable
	private fun storeTab() {
		val layers = LocalLayerManager.current
		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			ResultsGrid(
				results = menu.results,
				onSelect = { s ->
					hoveredStack = null
					layers.requestQuantityDialog(s) { amount -> menu.requestWithdraw(s.withCount(amount)) }
				},
			)
			Slots("output", COLUMNS, 1)
		}
	}

	@Composable
	private fun craftTab() {
		val layers = LocalLayerManager.current
		var status by remember { mutableStateOf(menu.craftJobStatus) }
		LaunchedEffect(Unit) {
			while (true) {
				status = menu.craftJobStatus
				delay(STATUS_POLL_MILLIS)
			}
		}

		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			ResultsGrid(
				results = menu.craftableResources.map { ResourceStack(it, 1L) },
				countText = "",
				onSelect = { s ->
					hoveredStack = null
					layers.requestCraftQuantityDialog(menu, s.resource) { amount -> menu.requestCraft(ResourceStack(s.resource, amount)) }
				},
			)
			Slots("output", COLUMNS, 1)
			Text(Component.literal(status.ifBlank { "No crafting job in progress" }), dropShadow = false)
		}
	}

	@Composable
	private fun assembleTab() {
		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			Row(horizontalArrangement = Arrangement.spacedBy(18)) {
				Slots("grid", 3, 3)
				Slots("result", 1, 1)
			}
			Button(onClick = { menu.requestCraftGrid() }) {
				Text(Component.literal("Craft"), dropShadow = false)
			}
		}
	}

	/** Shared search box + result grid, reused by [storeTab]/[craftTab]. */
	@Composable
	private fun ResultsGrid(results: List<SResourceStack<SItemResource>>, onSelect: (SResourceStack<SItemResource>) -> Unit, countText: String? = null) {
		var query by remember { mutableStateOf("") }
		val filtered = results.filter { query.isBlank() || it.resource.cachedStack.hoverName.string.contains(query, ignoreCase = true) }
		val rows = maxOf(VISIBLE_ROWS, (filtered.size + COLUMNS - 1) / COLUMNS)

		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			BasicTextField(
				value = query,
				onValueChange = { query = it },
				modifier = Modifier.width(contentWidth),
			)

			Scrollable(modifier = Modifier.width(contentWidth).height(18 * VISIBLE_ROWS)) {
				Column {
					for (row in 0 until rows)
					{
						Row {
							for (column in 0 until COLUMNS)
							{
								val stack = filtered.getOrNull(row * COLUMNS + column)
								TerminalSlot(
									stack = stack,
									countText = countText,
									onClick = {
										if (menu.carried == ItemStack.EMPTY)
										{
											stack?.let(onSelect)
										} else
										{
											menu.requestDeposit(menu.carried.resourceStack, true)
										}
									},
									onHovered = { hovered ->
										hoveredStack =
											if (hovered) stack else if (hoveredStack === stack) null else hoveredStack
									},
								)
							}
						}
					}
				}
			}
		}
	}

	override fun renderTooltip(guiGraphics: GuiGraphics, x: Int, y: Int) {
		super.renderTooltip(guiGraphics, x, y)
		hoveredStack?.let { guiGraphics.renderTooltip(font, it.itemStack, x, y) }
	}

	companion object {
		private const val COLUMNS = 9
		private const val VISIBLE_ROWS = 3
		private const val STATUS_POLL_MILLIS = 250L
	}
}

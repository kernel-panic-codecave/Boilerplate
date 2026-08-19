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
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.network.RequestCraftGridPreviewPacket
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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * [TerminalHookScreen]'s own single Store tab (see its KDoc for why there's no dedicated
 * autocraft-browsing tab), plus a real 3x3 grid ([CraftingTerminalHookState.grid][net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookState.grid])
 * right underneath the output slots, its own virtual result slot ([LiveResultSlot]) filled by hand
 * or by anything physically piped to this terminal's own position - clicking it (or shift-clicking
 * for a vanilla-style quick-craft) crafts, exactly like a real crafting table's own result slot,
 * via [CraftingTerminalHookMenu.craftOnce]. A near-duplicate of [TerminalHookScreen] for the same
 * reason [CraftingTerminalHookMenu] duplicates [TerminalHookMenu] - see its own KDoc.
 */
class CraftingTerminalHookScreen(private val menu: CraftingTerminalHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<CraftingTerminalHookMenu>(menu, playerInventory, title) {

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
		LaunchedEffect(Unit) {
			while (true) {
				TubularStorageNetworkChannel.toServer(RequestCraftGridPreviewPacket)
				delay(GRID_PREVIEW_POLL_MILLIS)
			}
		}

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
			Row(horizontalArrangement = Arrangement.spacedBy(18), verticalAlignment = Alignment.CenterVertically) {
				Slots("grid", 3, 3)
				LiveResultSlot(preview = menu.gridPreview) {
					menu.requestCraftOnce(Screen.hasShiftDown())
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

	/**
	 * The crafting terminal's own virtual result slot - a [FakeSlot] over [preview], never a real
	 * vanilla [net.minecraft.world.inventory.Slot] (nothing to actually hold; [preview] is
	 * recomputed server-side every time the grid changes, not stored). [onClick] fires on any
	 * click; [CraftingTerminalHookMenu.craftOnce] itself is what tells a plain click from a
	 * shift-click apart (`Screen.hasShiftDown()`, read at click time).
	 */
	@Composable
	private fun LiveResultSlot(preview: ItemStack?, onClick: () -> Unit) {
		val stack = preview?.let { it.resourceStack }
		TerminalSlot(stack = stack, onClick = onClick)
	}

	companion object {
		private const val COLUMNS = 9
		private const val VISIBLE_ROWS = 3
		private const val STATUS_POLL_MILLIS = 250L
		private const val GRID_PREVIEW_POLL_MILLIS = 150L
	}
}

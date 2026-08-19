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
 * A single "Store" tab over everything [menu] can currently reach (see [TerminalHookMenu]), plus a
 * "Tree" tab for the front-of-queue crafting job - see `docs/design/m3-warehouse-storage.md`/
 * `docs/design/m4-crafting-automation.md`. Built on Archie's `TabContainerPanel` (a fixed-width
 * `TabPanel` that wraps each tab's own content in its own `ContainerPanel`, tabs poking out of the
 * top the same way vanilla's own Create World screen looks) rather than the lower-level
 * `TabContainer` DSL, which has no width of its own and left the whole screen mismeasured/
 * left-pinned when tried first.
 *
 * There's no dedicated tab for browsing what's autocraftable - [StoreResultsGrid]'s own sidebar
 * [StoreViewModeButton] (Stock/Craft/All, AE2-terminal-style) filters the *same* grid instead,
 * with an autocraftable-but-empty entry showing "Craft" as its count and a click opening the
 * autocraft dialog directly, or a middle-click on an in-stock-and-craftable entry opening it
 * alongside the normal withdraw click - see [StoreResultsGrid]'s own KDoc. A real,
 * [Slots]-backed row of nine `"output"` slots sits underneath either way - a withdrawal or
 * finished crafting job delivers straight into this terminal's own
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState.output], not an external chest
 * wired to some other face.
 *
 * Hovering a result cell or a sidebar icon tracks [hoveredStack]/[sidebarTooltip], read back by
 * the overridden [renderTooltip] - the same hook `ComposeContainerScreen.render` already calls
 * vanilla's own `AbstractContainerScreen.renderTooltip` through, genuinely *after* the whole
 * Compose tree finishes rendering rather than nested inside it, which is the only reliable way to
 * guarantee a tooltip paints above everything else (a hovered [TerminalSlot]'s own
 * `SlotHighlight` included).
 */
class TerminalHookScreen(private val menu: TerminalHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<TerminalHookMenu>(menu, playerInventory, title) {

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

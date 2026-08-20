package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceStack
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerScope
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.*
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.network.*
import net.kernelpanicsoft.tubularstorage.util.itemStack
import net.kernelpanicsoft.tubularstorage.util.resourceStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

abstract class AbstractTerminalHookScreen<T : AbstractTerminalHookMenu<T>>(protected val menu: T, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<T>(menu, playerInventory, title)
{

	protected val contentWidth = 18 * COLUMNS
	protected val middleClickHandler = MiddleClickHandler()

	private var hoveredStack: SResourceStack<SItemResource>? = null
	private var sidebarTooltip: String? = null

	init
	{
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean
	{
		return middleClickHandler.tryHandle(button) || super.mouseClicked(mouseX, mouseY, button)
	}

	@Composable
	fun content()
	{
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
						StoreViewModeButton(viewMode, { sidebarTooltip = it }) {
							viewMode = it
							sidebarTooltip = it.tooltip
						}
					}
					TabContainerPanel(contentWidth = contentWidth) {
						tab(id = "store", title = Component.literal("Store")) { storeTab(viewMode) }
						tab(id = "tree", title = Component.literal("Tree")) { treeTab() }
						additionalTabs()
					}
				}
			}
		}
	}

	@Composable
	private fun storeTab(viewMode: StoreViewMode)
	{
		val layers = LocalLayerManager.current

		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			StoreResultsGrid(
				results = menu.results,
				craftable = menu.craftableResources,
				mode = viewMode,
				contentWidth = contentWidth,
				carried = { menu.carried },
				onDepositCarried = { menu.requestDeposit(menu.carried.resourceStack, true) },
				onRequestWithdraw = { stack ->
					hoveredStack = null
					layers.requestQuantityDialog(stack) { amount ->
						menu.requestWithdraw(
							stack.withCount(amount)
						)
					}
				},
				onRequestCraft = { resource ->
					hoveredStack = null
					layers.requestCraftQuantityDialog(
						menu,
						resource
					) { amount -> menu.requestCraft(ResourceStack(resource, amount)) }
				},
				middleClickHandler = middleClickHandler,
				onHoveredStackChanged = { hoveredStack = it },
			)
			Slots("output", COLUMNS, 1)
			additionalContent()
		}
	}

	@Composable
	open fun additionalContent() = Unit

	open fun TabContainerScope.additionalTabs() = Unit

	@Composable
	private fun treeTab()
	{
		LaunchedEffect(Unit) {
			while (true)
			{
				TubularStorageNetworkChannel.toServer(RequestCraftJobTreePacket)
				delay(STATUS_POLL_MILLIS.milliseconds)
			}
		}
		CraftingTreeView(
			root = menu.craftTree,

			modifier = Modifier.size(contentWidth, 18 * VISIBLE_ROWS * 2)
		)
	}

	/**
	 * Clears whatever's tracked as hovered before rendering anything, if [x]/[y] (this frame's own
	 * mouse position) has drifted outside the screen's own bounds - hovering a cell then moving the
	 * mouse straight off the window edge fires no further `mouseMoved` event for that cell (nothing
	 * to fire it *to*, since the cursor is no longer over anything in-window), so the ENTER/EXIT
	 * pair [TerminalSlot]/[SidebarButton] rely on to clear their own hover state never completes and
	 * the tooltip would otherwise stay stuck showing whatever was last hovered. This runs every
	 * frame regardless (unlike the event-driven hover tracking), so it self-corrects the instant the
	 * reported position genuinely leaves the window - assuming the platform keeps reporting a
	 * cursor position past the window edge rather than freezing at the last in-bounds one.
	 */
	override fun renderTooltip(guiGraphics: GuiGraphics, x: Int, y: Int)
	{
		if (x < 0 || y < 0 || x > width || y > height)
		{
			hoveredStack = null
			sidebarTooltip = null
		}
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
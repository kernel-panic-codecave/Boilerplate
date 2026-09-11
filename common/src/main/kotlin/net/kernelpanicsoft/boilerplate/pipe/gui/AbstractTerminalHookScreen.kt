package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slot
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerScope
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.*
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.boilerplate.client.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.*
import net.kernelpanicsoft.boilerplate.resource.*
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.resourceStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

abstract class AbstractTerminalHookScreen<T : AbstractTerminalHookMenu<T>>(protected val menu: T, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<T>(menu, playerInventory, title)
{

	protected val contentWidth = 18 * COLUMNS

	/** Right-click, for "empty the container I am holding into the network" - see [StoreResultsGrid]. */

	/** The [StoreResultsGrid] row currently under the mouse, if any - not a real vanilla [net.minecraft.world.inventory.Slot], so a recipe viewer (JEI/REI/EMI) can't discover it the normal hovered-slot way; exposed publicly for exactly that lookup (see `compat/rei/BoilerplateREIPlugin`'s own `registerScreens`). */
	var hoveredStack: SResourceStack<*>? = null
		private set

	/** This screen's own on-window rectangle - [leftPos]/[topPos]/[imageWidth]/[imageHeight] are `protected` on vanilla's own [AbstractContainerScreen], exposed publicly here since a recipe viewer's exclusion-zone registration needs the real occupied bounds to avoid overlapping its own item panel with this screen's fully custom (non-[net.minecraft.world.inventory.Slot]) content. */
	val screenLeft get() = leftPos
	val screenTop get() = topPos
	val screenWidth get() = imageWidth
	val screenHeight get() = imageHeight

	init
	{
		start { content() }
	}

	abstract val mainTabId: String
	abstract val mainTabLabel: Component

	@Composable
	fun content()
	{
		BoilerplateTheme {
			Box(contentAlignment = Alignment.Center) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(2),
					verticalAlignment = Alignment.Top
				) {
					// Seeded from the client config and written straight back to it, so a terminal
					// opens the way the player last left one. The defaults live there too - name,
					// ascending, being the only ordering that holds still while the network's
					// contents move underneath it, so a row does not slide out from under the
					// cursor between a player seeing it and clicking it.
					var viewMode by remember { mutableStateOf(TerminalPreferences.viewMode) }
					var sortMode by remember { mutableStateOf(TerminalPreferences.sortMode) }
					var sortDirection by remember { mutableStateOf(TerminalPreferences.sortDirection) }
					Column {
						SidebarButton("↻", "Refresh") {
							BoilerplateNetworkChannel.toServer(RequestTerminalSearchResultsPacket)
							BoilerplateNetworkChannel.toServer(RequestCraftableListPacket)
						}
						SidebarButton("⥮", "Defragment warehouses") {
							BoilerplateNetworkChannel.toServer(RequestWarehouseDefragPacket)
						}
						StoreViewModeButton(viewMode) { viewMode = it; TerminalPreferences.viewMode = it }
						StoreSortModeButton(sortMode) { sortMode = it; TerminalPreferences.sortMode = it }
						StoreSortDirectionButton(sortDirection) { sortDirection = it; TerminalPreferences.sortDirection = it }
					}
					TabContainerPanel(contentWidth = contentWidth) {
						tab(id = mainTabId, title = mainTabLabel) { mainTab(viewMode, sortMode, sortDirection) }
						tab(id = "jobs", title = Component.literal("Jobs")) { treeTab() }
						additionalTabs()
					}
				}
			}
		}
	}

	@Composable
	private fun mainTab(viewMode: StoreViewMode, sortMode: StoreSortMode, sortDirection: StoreSortDirection)
	{
		val layers = LocalLayerManager.current

		LaunchedEffect(Unit) {
			while (true)
			{
				BoilerplateNetworkChannel.toServer(RequestPendingDeliveriesPacket)
				delay(STATUS_POLL_MILLIS.milliseconds)
			}
		}

		Column(verticalArrangement = Arrangement.spacedBy(6)) {
			if (!menu.hasPressure) {
				Label(Component.literal("No pressure reachable"))
			}
			StoreResultsGrid(
				results = menu.results,
				craftable = menu.craftableResources,
				mode = viewMode,
				sortMode = sortMode,
				sortDirection = sortDirection,
				contentWidth = contentWidth,
				carried = { menu.carried },
				// The carried stack is an item by definition, so this widens rather than converts.
				onDepositCarried = { drainContainer ->
					menu.requestDeposit(
						menu.carried.resourceStack.let { ResourceStack(it.resource as ResourceComponent, it.amount) },
						clearCarried = true,
						drainContainer = drainContainer,
					)
				},
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
					// Submits itself on finish - the amount is only half of what the flow collects,
					// the chosen Crafting CPU being the other half.
					layers.craftRequestWizard(menu, resource)
				},
				onHoveredStackChanged = { hoveredStack = it },
				enabled = menu.hasPressure,
			)
			// The inbox: one mixed row of real vanilla slots. An item column *is* a real slot and
			// behaves like one; a column holding a fluid-like kind has no stack for vanilla to draw,
			// so its own kind draws the face over the top and AbstractTerminalHookMenu.clicked
			// handles the click. The transfer slot this replaced is gone - a container the player is
			// already holding does the same job without a withdrawal having to wait for one.
			Slots("output", COLUMNS, 1) {
				Row {
					repeat(COLUMNS) { i ->
						Box {
							Slot()
							menu.inboxFaceFor(i)?.let { (kind, stack) ->
								kind.display?.SlotFace(stack.resource, stack.amount, isHovered = false, countText = amountLabelFor(stack.resource, stack.amount), enabled = true)
							}
							menu.pendingDeliveryFor(i)?.let { delivery ->
								// The face of what is *on its way*, under the progress arc. The
								// overlay fake-renders an item stack itself and has nothing to draw
								// for any other kind, so without this an in-flight fluid or chemical
								// is a bare countdown over an empty cell - which reads as a request
								// that went nowhere.
								ResourceKindRegistry.forResource(delivery.resource)
									?.takeIf { it !== ResourceKindRegistry.Item }
									?.display
									?.SlotFace(delivery.resource, delivery.amount, isHovered = false, countText = amountLabelFor(delivery.resource, delivery.amount), enabled = true)
								PendingDeliveryOverlay(delivery)
							}
						}
					}
				}
			}
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
				BoilerplateNetworkChannel.toServer(RequestCraftJobTreePacket)
				delay(STATUS_POLL_MILLIS.milliseconds)
			}
		}
		CraftingTreeView(
			roots = menu.craftTrees,

			modifier = Modifier.size(contentWidth, 18 * VISIBLE_ROWS * 2)
		)
	}

	/**
	 * Clears [hoveredStack] before rendering anything, if [x]/[y] (this frame's own mouse position)
	 * has drifted outside the screen's own bounds - hovering a cell then moving the mouse straight
	 * off the window edge fires no further `mouseMoved` event for that cell (nothing to fire it
	 * *to*, since the cursor is no longer over anything in-window), so the ENTER/EXIT pair
	 * [TerminalSlot] relies on never completes and a recipe viewer would keep reading whatever was
	 * last hovered as its focused stack. This runs every frame regardless (unlike the event-driven
	 * hover tracking), so it self-corrects the instant the reported position genuinely leaves the
	 * window - assuming the platform keeps reporting a cursor position past the window edge rather
	 * than freezing at the last in-bounds one.
	 *
	 * Tooltips need none of this: each cell declares its own
	 * ([net.kernelpanicsoft.archie.gui.modifiers.appearance.tooltip]) and the renderer re-finds the
	 * hovered node from the live mouse position every frame.
	 */
	override fun renderTooltip(guiGraphics: GuiGraphics, x: Int, y: Int)
	{
		if (x < 0 || y < 0 || x > width || y > height) hoveredStack = null
		super.renderTooltip(guiGraphics, x, y)
	}

	companion object {
		private const val COLUMNS = 9
		private const val VISIBLE_ROWS = 3
		private const val STATUS_POLL_MILLIS = 250L
	}
}
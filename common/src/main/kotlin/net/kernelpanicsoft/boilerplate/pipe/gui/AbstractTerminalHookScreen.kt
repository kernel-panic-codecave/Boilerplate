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
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.*
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.util.resourceStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

abstract class AbstractTerminalHookScreen<T : AbstractTerminalHookMenu<T>>(protected val menu: T, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<T>(menu, playerInventory, title)
{

	protected val contentWidth = 18 * COLUMNS
	protected val clickHandler = ClickHandler(2)

	/** Right-click, for "empty the container I am holding into the network" - see [StoreResultsGrid]. */
	protected val rightClickHandler = ClickHandler(1)

	/** The [StoreResultsGrid] row currently under the mouse, if any - not a real vanilla [net.minecraft.world.inventory.Slot], so a recipe viewer (JEI/REI/EMI) can't discover it the normal hovered-slot way; exposed publicly for exactly that lookup (see `compat/rei/BoilerplateREIPlugin`'s own `registerScreens`). */
	var hoveredStack: SResourceStack<*>? = null
		private set
	private var sidebarTooltip: String? = null

	/** This screen's own on-window rectangle - [leftPos]/[topPos]/[imageWidth]/[imageHeight] are `protected` on vanilla's own [AbstractContainerScreen], exposed publicly here since a recipe viewer's exclusion-zone registration needs the real occupied bounds to avoid overlapping its own item panel with this screen's fully custom (non-[net.minecraft.world.inventory.Slot]) content. */
	val screenLeft get() = leftPos
	val screenTop get() = topPos
	val screenWidth get() = imageWidth
	val screenHeight get() = imageHeight

	init
	{
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean
	{
		return clickHandler.tryHandle(button) || rightClickHandler.tryHandle(button) || super.mouseClicked(mouseX, mouseY, button)
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
					var viewMode by remember { mutableStateOf(StoreViewMode.BOTH) }
					Column {
						SidebarButton("↻", "Refresh", { sidebarTooltip = it }) {
							BoilerplateNetworkChannel.toServer(RequestTerminalSearchResultsPacket)
							BoilerplateNetworkChannel.toServer(RequestCraftableListPacket)
						}
						SidebarButton("⥮", "Defragment warehouses", { sidebarTooltip = it }) {
							BoilerplateNetworkChannel.toServer(RequestWarehouseDefragPacket)
						}
						StoreViewModeButton(viewMode, { sidebarTooltip = it }) {
							viewMode = it
							sidebarTooltip = it.tooltip
						}
					}
					TabContainerPanel(contentWidth = contentWidth) {
						tab(id = mainTabId, title = mainTabLabel) { mainTab(viewMode) }
						tab(id = "jobs", title = Component.literal("Jobs")) { treeTab() }
						additionalTabs()
					}
				}
			}
		}
	}

	@Composable
	private fun mainTab(viewMode: StoreViewMode)
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
				clickHandler = clickHandler,
				rightClickHandler = rightClickHandler,
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
			// Every line the resource's own kind has to offer, not just its name - an item's tooltip
			// is enchantments, durability, lore and whatever other mods attached to it, and drawing
			// only the name threw all of it away.
			?: hoveredStack?.let { stack ->
				val resource = stack.resource as ResourceComponent
				val kind = ResourceKindRegistry.forResource(resource)
				val lines = kind?.display?.tooltipLines(resource, stack.amount) ?: listOf(resource.displayName())
				guiGraphics.renderComponentTooltip(font, lines, x, y)
			}
	}

	companion object {
		private const val COLUMNS = 9
		private const val VISIBLE_ROWS = 3
		private const val STATUS_POLL_MILLIS = 250L
	}
}
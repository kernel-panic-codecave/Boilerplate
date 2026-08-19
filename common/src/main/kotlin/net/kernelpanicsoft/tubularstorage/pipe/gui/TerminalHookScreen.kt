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
 * Two tabs over everything [menu] can currently reach (see [TerminalHookMenu]) - see
 * `docs/design/m3-warehouse-storage.md`/`docs/design/m4-crafting-automation.md`. Built on Archie's
 * `TabContainerPanel` (a fixed-width `TabPanel` that wraps each tab's own content in its own
 * `ContainerPanel`, tabs poking out of the top the same way vanilla's own Create World screen
 * looks) rather than the lower-level `TabContainer` DSL, which has no width of its own and left the
 * whole screen mismeasured/left-pinned when tried first. Both tabs share [ResultsGrid] (search box
 * + scrollable, non-slot-backed result grid over [TerminalHookMenu.results], filtered locally by
 * display name) - "Store" clicking a cell opens [requestQuantityDialog] and withdraws, "Craft"
 * clicking a cell opens [requestCraftQuantityDialog] and submits a [TerminalHookMenu.requestCraft]
 * instead, with a status line underneath reading
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity.craftJobStatus] (polled into
 * Compose state - see [craftTab] - rather than pushed, since that field is synced onto the
 * client's own [menu]`.tile` the ordinary block-entity way, not through a dedicated packet). Both
 * also show a real, [Slots]-backed row of nine `"output"` slots underneath - a withdrawal or
 * finished crafting job delivers straight into this terminal's own
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState.output], not an external chest
 * wired to some other face.
 *
 * [COLUMNS] wide, always at least [VISIBLE_ROWS] tall - the same 9x3 a chest's own grid shows -
 * padded out with empty [TerminalSlot]s when there aren't enough results to fill it, rather than
 * collapsing down to however many results actually exist. More results than that add further rows
 * below rather than a fourth visible row, scrolled into view the same way a real inventory with
 * more slots than fit on screen would be.
 *
 * Hovering a cell tracks [hoveredStack], read back by the overridden [renderTooltip] - the same
 * hook `ComposeContainerScreen.render` already calls vanilla's own
 * `AbstractContainerScreen.renderTooltip` through, genuinely *after* the whole Compose tree
 * finishes rendering rather than nested inside it. A tooltip composed inline as just another node
 * in that tree has no reliable way to guarantee it paints above everything else in it (a hovered
 * [TerminalSlot]'s own `SlotHighlight` included) - this hook is the same one vanilla's own tooltip
 * relies on for exactly that guarantee.
 */
class TerminalHookScreen(private val menu: TerminalHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<TerminalHookMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * COLUMNS

	/** The currently-hovered result's stack, if any - written by [TerminalSlot]'s `onHovered` callbacks during composition, read back imperatively by [renderTooltip] rather than through Compose state, since nothing here needs recomposition to react to it. */
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
							Text(
								Component.literal("↻"),
								dropShadow = false
							)
						}
						Button(onClick = { TubularStorageNetworkChannel.toServer(RequestWarehouseDefragPacket) }) {
							Text(
								Component.literal("⥮"),
								dropShadow = false
							)
						}
					}
					TabContainerPanel(contentWidth = contentWidth) {
						tab(id = "store", title = Component.literal("Store")) { storeTab() }
						tab(id = "craft", title = Component.literal("Craft")) { craftTab() }
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

	/**
	 * A catalog of what's craftable, not what's in stock - [menu.craftableResources][TerminalHookMenu.craftableResources]
	 * (the distinct outputs of every reachable pattern provider's own held patterns), shown as `amount = 1`
	 * placeholder stacks with the vanilla count decoration suppressed ([TerminalSlot]'s `countText`)
	 * so an item you currently have none of still shows its icon without a misleading "1" - the
	 * quantity dialog this opens ([requestCraftQuantityDialog]) never reads that placeholder amount
	 * anyway, only the resource itself.
	 */
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

	/** Shared search box + result grid, reused by [storeTab]/[craftTab] - see this class's own KDoc. */
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

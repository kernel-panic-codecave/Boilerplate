package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.Theme
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
 * Search box + scrollable, non-slot-backed result grid over everything [menu] can currently reach
 * (see [TerminalHookMenu]) - see `docs/design/m3-warehouse-storage.md`. Filters
 * [TerminalHookMenu.results] locally by the search text (matched against each result's own
 * display name) rather than round-tripping a query to the server; the server only ever pushes the
 * *full* unfiltered aggregate, on open, after a withdrawal, or in response to the refresh button
 * ([RequestTerminalSearchResultsPacket]).
 *
 * [COLUMNS] wide, always at least [VISIBLE_ROWS] tall - the same 9x3 a chest's own grid shows -
 * padded out with empty [TerminalSlot]s when there aren't enough results to fill it, rather than
 * collapsing down to however many results actually exist. More results than that add further rows
 * below rather than a fourth visible row, scrolled into view the same way a real inventory with
 * more slots than fit on screen would be.
 *
 * Clicking an occupied cell opens a [requestQuantityDialog] asking how many to request rather than
 * instantly withdrawing a full stack - see [TerminalHookMenu.requestWithdraw]. Hovering one tracks
 * [hoveredStack], read back by the overridden [renderTooltip] - the same hook
 * `ComposeContainerScreen.render` already calls vanilla's own `AbstractContainerScreen.renderTooltip`
 * through, genuinely *after* the whole Compose tree finishes rendering rather than nested inside
 * it. A tooltip composed inline as just another node in that tree has no reliable way to guarantee
 * it paints above everything else in it (a hovered [TerminalSlot]'s own [SlotHighlight] included) -
 * this hook is the same one vanilla's own tooltip relies on for exactly that guarantee.
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
		val layers = LocalLayerManager.current
		var query by remember { mutableStateOf("") }
		val filtered = menu.results.filter { query.isBlank() || it.resource.cachedStack.hoverName.string.contains(query, ignoreCase = true) }
		val rows = maxOf(VISIBLE_ROWS, (filtered.size + COLUMNS - 1) / COLUMNS)

		Theme {
			Row(
				horizontalArrangement = Arrangement.spacedBy(2),
				verticalAlignment = Alignment.Top
			) {
				Column {
					Button(onClick = { TubularStorageNetworkChannel.toServer(RequestTerminalSearchResultsPacket) }) {
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
				ContainerPanel(contentWidth) {
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
												onClick = {
													if (menu.carried == ItemStack.EMPTY)
													{
														stack?.let { s ->
															hoveredStack = null
															layers.requestQuantityDialog(s) { amount ->
																menu.requestWithdraw(s.withCount(amount))
															}
														}
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
	}
}

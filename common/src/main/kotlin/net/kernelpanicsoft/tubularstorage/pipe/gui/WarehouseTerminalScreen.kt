package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.position.padding
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.network.RequestWarehouseSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.network.WithdrawFromWarehousePacket
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Search box + scrollable, non-slot-backed result list over everything [menu] can currently reach
 * (see [WarehouseTerminalMenu]) - see `docs/design/m3-warehouse-storage.md`. Filters [WarehouseTerminalMenu.results]
 * locally by the search text (matched against each result's own display name) rather than
 * round-tripping a query to the server; the server only ever pushes the *full* unfiltered
 * aggregate, on open, after a withdrawal, or in response to the refresh button
 * ([RequestWarehouseSearchResultsPacket]).
 *
 * Clicking a result row withdraws up to one stack of it - see [WarehouseTerminalMenu.withdraw].
 */
class WarehouseTerminalScreen(private val menu: WarehouseTerminalMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<WarehouseTerminalMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9

	init {
		start { content() }
	}

	@Composable
	fun content() {
		var query by remember { mutableStateOf("") }
		val filtered = menu.results.filter { query.isBlank() || it.hoverName.string.contains(query, ignoreCase = true) }

		Theme {
			Box(modifier = Modifier.width(contentWidth + 16)) {
				Panel(modifier = Modifier.width(contentWidth)) {
					Column(verticalArrangement = Arrangement.spacedBy(6)) {
						Row(horizontalArrangement = Arrangement.spacedBy(4)) {
							BasicTextField(
								value = query,
								onValueChange = { query = it },
								modifier = Modifier.width(contentWidth - 20),
							)
							Clickable(onClick = { TubularStorageNetworkChannel.toServer(RequestWarehouseSearchResultsPacket) }) { _, _, _ ->
								Text(Component.literal("↻"), dropShadow = false)
							}
						}

						Scrollable(modifier = Modifier.width(contentWidth).height(18 * 6)) {
							Column {
								for (stack in filtered) {
									ResultRow(stack)
								}
							}
						}
					}
				}
			}
		}
	}

	@Composable
	private fun ResultRow(stack: ItemStack) {
		Clickable(
			onClick = { withdraw(stack) },
			modifier = Modifier.width(contentWidth).height(18),
		) { _, _, _ ->
			Row(modifier = Modifier.padding(left = 2), horizontalArrangement = Arrangement.spacedBy(4)) {
				ItemStackIcon(stack)
				Text(stack.hoverName, dropShadow = false)
			}
		}
	}

	private fun withdraw(stack: ItemStack) {
		val amount = stack.count.coerceAtMost(stack.item.defaultMaxStackSize)
		TubularStorageNetworkChannel.toServer(WithdrawFromWarehousePacket(ItemStack(stack.item, amount)))
	}
}

package net.kernelpanicsoft.boilerplate.warehouse

import androidx.compose.runtime.*
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.composables.input.Slider
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.UpdateWarehouseRoutingPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.math.roundToInt

/**
 * Inbound acceptance configuration for a [WarehouseControllerBlockEntity] - a filter-card slot,
 * whitelist/blacklist mode, and pipe-routing priority (down to [RoutingModule.DEFAULT_ROUTE_PRIORITY],
 * where the warehouse acts as the network's catch-all sink - a natural fit for bulk storage, per
 * `docs/design/m2-sorting-routing.md`). Mirrors the racks' filter + priority shape; unlike a
 * sorting hook there's no color, since the warehouse's own acceptance ([inboundBuffer]'s predicate)
 * and routing rank are destination-side, not consignment-side.
 *
 * Reads [WarehouseControllerMenu.routing] once into local Compose state. Edits update that local
 * state immediately (optimistic UI) and push an [UpdateWarehouseRoutingPacket] to persist them
 * server-side. The filter is a real vanilla slot (see
 * [WarehouseControllerMenu.registerSlotHandlers]), so vanilla's own container syncing carries it.
 */
class WarehouseControllerScreen(private val menu: WarehouseControllerMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<WarehouseControllerMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9

	init {
		start { content() }
	}

	@Composable
	fun content() {
		var module by remember { mutableStateOf(menu.routing) }

		fun update(next: RoutingModule) {
			module = next
			BoilerplateNetworkChannel.toServer(UpdateWarehouseRoutingPacket(menu.pos, next))
		}

		BoilerplateTheme {
			ContainerPanel(contentWidth = contentWidth) {
				Column(verticalArrangement = Arrangement.spacedBy(6)) {
					Text(Component.literal("Filter"), dropShadow = false)
					Slots("filter")

					Text(Component.literal("Mode"), dropShadow = false)
					RadioGroup(
						options = listOf(
							RadioOption(FilterMode.WHITELIST, Component.literal("Whitelist")),
							RadioOption(FilterMode.BLACKLIST, Component.literal("Blacklist")),
						),
						selected = module.mode,
						onSelected = { update(module.copy(mode = it)) },
					)

					val priorityLabel = if (module.priority == RoutingModule.DEFAULT_ROUTE_PRIORITY) "Default Route" else module.priority.toString()
					Text(Component.literal("Priority: $priorityLabel"), dropShadow = false)
					Slider(
						value = (module.priority - PRIORITY_MIN).toFloat() / PRIORITY_RANGE,
						onValueChange = { update(module.copy(priority = PRIORITY_MIN + (it * PRIORITY_RANGE).roundToInt())) },
						steps = PRIORITY_RANGE,
						modifier = Modifier.width(contentWidth),
					)
				}
			}
		}
	}

	companion object {
		private const val PRIORITY_MIN = RoutingModule.DEFAULT_ROUTE_PRIORITY
		private const val PRIORITY_MAX = 10
		private const val PRIORITY_RANGE = PRIORITY_MAX - PRIORITY_MIN
	}
}
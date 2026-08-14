package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.composables.input.Slider
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.network.UpdateSortingRoutingPacket
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor
import kotlin.math.roundToInt

/**
 * Sorting configuration for [SortingPipeMenu.direction]'s hook: filter grid, whitelist/blacklist
 * mode, priority, and consignment color. Color is a discrete [DyeColor] pick (not Archie's
 * continuous [net.kernelpanicsoft.archie.gui.composables.input.ColorPicker]) since routing
 * compares it by exact equality against a traveling item's color - see
 * `docs/design/m2-sorting-routing.md`.
 *
 * Reads [SortingPipeMenu.currentRouting] once, into local Compose state, rather than observing
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity.hooks] live - a nested
 * [net.kernelpanicsoft.archie.serialization.NBTHolder] field isn't wired into
 * [net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStateManager] for that the way a
 * top-level `@Sync` field is. Edits update that local state immediately (optimistic UI) and push
 * an [UpdateSortingRoutingPacket] to persist them server-side.
 */
class SortingPipeScreen(private val menu: SortingPipeMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<SortingPipeMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9

	init {
		start { content() }
	}

	@Composable
	fun content() {
		var module by remember { mutableStateOf(menu.currentRouting()) }

		fun update(next: RoutingModule) {
			module = next
			TubularStorageNetworkChannel.toServer(UpdateSortingRoutingPacket(menu.pos, menu.direction, next))
		}

		Theme {
			Box(modifier = Modifier.width(contentWidth + 16)) {
				Panel(modifier = Modifier.width(contentWidth)) {
					Column(verticalArrangement = Arrangement.spacedBy(6)) {
						Text(Component.literal("Filter"), dropShadow = false)
						Slots("filter", 3, 3)

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

						Text(Component.literal("Color"), dropShadow = false)
						RadioGroup<DyeColor?>(
							options = buildList {
								add(RadioOption(null, Component.literal("Any")))
								for (dyeColor in DyeColor.entries) {
									add(RadioOption(dyeColor, Component.literal(dyeColor.name.lowercase())))
								}
							},
							selected = module.color,
							onSelected = { update(module.copy(color = it)) },
						)
					}
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

package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.composables.input.Slider
import net.kernelpanicsoft.archie.gui.composables.theme.TextureStates
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.SimpleThemeState
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.UpdateSortingRoutingPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor
import kotlin.math.roundToInt

/**
 * Sorting configuration for [SortingHookMenu.direction]'s hook: filter grid, whitelist/blacklist
 * mode, priority, and consignment color. Color is a discrete [DyeColor] pick (not Archie's
 * continuous [net.kernelpanicsoft.archie.gui.composables.input.ColorPicker]) since routing
 * compares it by exact equality against a traveling item's color - see
 * `docs/design/m2-sorting-routing.md`.
 *
 * Reads [SortingHookMenu.currentRouting] once, into local Compose state, rather than observing
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity.hooks] live - a nested
 * [net.kernelpanicsoft.archie.serialization.NBTHolder] field isn't wired into
 * [net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStateManager] for that the way a
 * top-level `@Sync` field is. Edits update that local state immediately (optimistic UI) and push
 * an [UpdateSortingRoutingPacket] to persist them server-side. The filter is a real vanilla slot
 * (see [SortingHookMenu.registerSlotHandlers]), so vanilla's own container syncing carries it.
 */
class SortingHookScreen(private val menu: SortingHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<SortingHookMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9
	private val clickHandler = ClickHandler(1)

	init {
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
		return clickHandler.tryHandle(button) || super.mouseClicked(mouseX, mouseY, button)
	}

	@Composable
	fun content() {
		var module by remember { mutableStateOf(menu.currentRouting()) }

		fun update(next: RoutingModule) {
			module = next
			BoilerplateNetworkChannel.toServer(UpdateSortingRoutingPacket(menu.pos, menu.direction, next))
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

					Text(Component.literal("Color"), dropShadow = false)
					val theme = LocalTheme.current
					val composableTheme = theme.getComposableTheme("radio")
					val default = composableTheme.states[TextureStates.DEFAULT] as SimpleThemeState
					Scrollable(modifier = Modifier.height(default.height)) {
						RadioGroup<DyeColor?>(
							options = buildList {
								add(RadioOption(null, Component.literal("Any")))
								for (dyeColor in DyeColor.entries)
								{
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

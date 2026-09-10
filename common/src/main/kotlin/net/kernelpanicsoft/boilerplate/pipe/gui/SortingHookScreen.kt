package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.Checkbox
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.composables.input.Slider
import net.kernelpanicsoft.archie.gui.composables.theme.TextureStates
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.SimpleThemeState
import net.kernelpanicsoft.boilerplate.client.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.UpdateFilterBatchPacket
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState
import net.kernelpanicsoft.boilerplate.network.UpdateProviderRecursionPacket
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
		var batch by remember { mutableStateOf(menu.batchSize()) }
		var recursive by remember { mutableStateOf(menu.isRecursive()) }

		fun updateRecursive(next: Boolean) {
			recursive = next
			BoilerplateNetworkChannel.toServer(UpdateProviderRecursionPacket(menu.pos, menu.direction, next))
		}

		fun updateBatch(next: Long) {
			batch = next
			BoilerplateNetworkChannel.toServer(UpdateFilterBatchPacket(menu.pos, menu.direction, next))
		}

		fun update(next: RoutingModule) {
			module = next
			BoilerplateNetworkChannel.toServer(UpdateSortingRoutingPacket(menu.pos, menu.direction, next))
		}

		BoilerplateTheme {
			ContainerPanel(contentWidth = contentWidth) {
				Column(verticalArrangement = Arrangement.spacedBy(6)) {
					Label(Component.literal("Filter"))
					Slots("filter")

					Label(Component.literal("Mode"))
					RadioGroup(
						options = listOf(
							RadioOption(FilterMode.WHITELIST, Component.literal("Whitelist")),
							RadioOption(FilterMode.BLACKLIST, Component.literal("Blacklist")),
						),
						selected = module.mode,
						onSelected = { update(module.copy(mode = it)) },
					)

					// Priority and colour are destination-side settings; a source-only hook is never
					// routed *to* and has no use for either - see SortingHookMenu.supportsRouting.
					if (menu.supportsRouting()) {
						val priorityLabel = if (module.priority == RoutingModule.DEFAULT_ROUTE_PRIORITY) "Default Route" else module.priority.toString()
						Label(Component.literal("Priority: $priorityLabel"))
						Slider(
							value = (module.priority - PRIORITY_MIN).toFloat() / PRIORITY_RANGE,
							onValueChange = { update(module.copy(priority = PRIORITY_MIN + (it * PRIORITY_RANGE).roundToInt())) },
							steps = PRIORITY_RANGE,
							modifier = Modifier.width(contentWidth),
						)
					}

					if (menu.supportsBatching()) {
						// The filter already says *what* may pass; this says *how much at a time*, so a
						// destination that consumes in fixed multiples is never handed a partial batch
						// it cannot use.
						//
						// A [NumberField] rather than a [QuantityStepper]: a stepper's rung buttons do
						// not fit beside a field in a panel this wide, and the range has to reach one
						// whole of the largest kind that can cross the face - which is a great deal
						// more than a stack.
						NumberField(
							amount = batch,
							range = FilterHookState.NOT_BATCHED..menu.maxBatchSize(),
							onAmountChange = { updateBatch(it) },
							leading = { Label(Component.literal("Batch:")) },
							// The off state is a real value (one), so it is said beside the number
							// rather than instead of it - a caption reading "off" over a field reading
							// "1" is two answers to the same question.
							trailing = { if (batch <= FilterHookState.NOT_BATCHED) Label(Component.literal("(off)")) },
						)
					}

					if (menu.supportsRecursion()) {
						// Only a provider has this, and it only means anything facing an interface -
						// see ProviderHookState.recursive.
						Label(Component.literal(if (recursive) "Reaches into the far network" else "Reads the interface only"))
						Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
							Checkbox(checked = recursive, onCheckedChange = { updateRecursive(it) })
							Label(Component.literal("Recursive"))
						}
					}

					if (menu.supportsRouting()) {
						Label(Component.literal("Color"))
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
	}

	companion object {

		private const val PRIORITY_MIN = RoutingModule.DEFAULT_ROUTE_PRIORITY
		private const val PRIORITY_MAX = 10
		private const val PRIORITY_RANGE = PRIORITY_MAX - PRIORITY_MIN
	}
}

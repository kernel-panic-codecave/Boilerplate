package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.blockentity.observeProperty
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
import net.kernelpanicsoft.tubularstorage.pipe.entity.FaceRouting
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
 */
class SortingPipeScreen(menu: SortingPipeMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<SortingPipeMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9
	private val direction = menu.direction

	init {
		start { content() }
	}

	@Composable
	fun content() {
		var synced by observeProperty("routing", FaceRouting())
		val faceRouting = synced ?: FaceRouting()
		val module = faceRouting[direction]

		fun update(next: RoutingModule) {
			synced = faceRouting.with(direction, next)
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

						Text(Component.literal("Priority: ${module.priority}"), dropShadow = false)
						Slider(
							value = module.priority.toFloat() / PRIORITY_MAX,
							onValueChange = { update(module.copy(priority = (it * PRIORITY_MAX).roundToInt())) },
							steps = PRIORITY_MAX,
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
		private const val PRIORITY_MAX = 10
	}
}

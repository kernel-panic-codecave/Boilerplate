package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Two rows of 9 - `ghost` targets [Slots] on top, `stock` [Slots] beneath - the whole GUI,
 * chest-simple, matching [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType]'s "stock
 * what the ghost row asks for" role.
 *
 * The [Column] is load-bearing, not styling: [ContainerPanel] hands its content to a `Box`, which
 * overlays children rather than stacking them, so two bare [Slots] groups occupy the same space and
 * only the upper one is visible or clickable.
 */
class InterfaceHookScreen(private val menu: InterfaceHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<InterfaceHookMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		BoilerplateTheme {
			ContainerPanel {
				// Explicitly stacked. ContainerPanel places its content inside a Box, which
				// overlays its children - two bare Slots groups there render on top of each other
				// and read as a single row, with only the upper one reachable.
				Column(verticalArrangement = Arrangement.spacedBy(2)) {
					Text(Component.literal("Targets"), dropShadow = false)
					Slots("ghost", 9, 1)
					Text(Component.literal("Stock"), dropShadow = false)
					Slots("stock", 9, 1)
				}
			}
		}
	}
}

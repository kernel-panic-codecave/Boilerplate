package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** Two packed rows of 9 - `ghost` targets [Slots] on top, `stock` [Slots] beneath - the whole GUI, chest-simple, matching [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType]'s "stock what the ghost row asks for" role. */
class InterfaceHookScreen(private val menu: InterfaceHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<InterfaceHookMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		BoilerplateTheme {
			ContainerPanel {
				Slots("ghost", 9, 1)
				Slots("stock", 9, 1)
			}
		}
	}
}

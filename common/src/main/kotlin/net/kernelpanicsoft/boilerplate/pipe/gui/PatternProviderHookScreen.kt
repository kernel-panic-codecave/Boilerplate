package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** A plain 3x3 [Slots] grid over [PatternProviderHookMenu]'s `patterns` group - drop encoded [net.kernelpanicsoft.boilerplate.crafting.PatternItem]s in, the hook does the rest. */
class PatternProviderHookScreen(private val menu: PatternProviderHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<PatternProviderHookMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		Theme {
			ContainerPanel {
				Slots("patterns", 3, 3)
			}
		}
	}
}

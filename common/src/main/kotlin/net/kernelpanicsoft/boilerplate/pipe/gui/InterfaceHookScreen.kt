package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.boilerplate.client.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Two rows of 9 - the [StockingRowGrid] of targets on top, the real `stock` [Slots] beneath -
 * matching [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType]'s "stock what the target
 * row asks for" role.
 *
 * The target row is the same editor a requester uses, so a target here can be any amount, ∞ (hold
 * whatever arrives, never drain it), or a configured filter card standing for a whole class of
 * resources - none of which the real-stack row it replaced could express.
 *
 * The [Column] is load-bearing, not styling: [ContainerPanel] hands its content to a `Box`, which
 * overlays children rather than stacking them, so two bare groups would occupy the same space and
 * only the upper one would be visible or clickable.
 */
class InterfaceHookScreen(private val menu: InterfaceHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<InterfaceHookMenu>(menu, playerInventory, title) {

	private val clickHandler = ClickHandler(1)

	init {
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
		return clickHandler.tryHandle(button) || super.mouseClicked(mouseX, mouseY, button)
	}

	@Composable
	fun content() {
		var targets by remember { mutableStateOf(menu.currentStockingTargets()) }

		fun set(index: Int, resource: ResourceComponent, amount: Long) {
			targets = targets.first.toMutableList().also { it[index] = resource } to
				targets.second.toMutableList().also { it[index] = amount }
			menu.setStockingTarget(index, resource, amount)
		}

		BoilerplateTheme {
			ContainerPanel {
				Column(verticalArrangement = Arrangement.spacedBy(2)) {
					Label(Component.literal("Targets"))
					StockingRowGrid(
						targets = targets.first,
						amounts = targets.second,
						columns = InterfaceHookState.SLOTS,
						carried = { menu.carried },
						onSet = ::set,
						clickHandler = clickHandler,
					)
					Label(Component.literal("Stock"))
					Slots("stock", InterfaceHookState.SLOTS, 1)
				}
			}
		}
	}
}

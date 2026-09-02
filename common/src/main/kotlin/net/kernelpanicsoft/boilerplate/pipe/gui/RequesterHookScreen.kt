package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.RequesterStatusPacket
import net.kernelpanicsoft.boilerplate.network.displayName
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.UNBOUNDED_STOCK
import net.kernelpanicsoft.boilerplate.pipe.hook.configuredFilterOn
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration and live status for a requester hook: its
 * [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow] of standing orders, and what the hook is
 * actually doing with them.
 *
 * The row is the same editor an interface uses - any amount, ∞ for "just keep exporting", and a
 * configured filter card in a cell to stand for a whole class of resources.
 *
 * The status panel is the part that earns its place. A requester's role flips when it faces an
 * [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType] hook - it keeps that interface
 * stocked for the far subnet rather than the inventory next door - and nothing about the hook's
 * appearance says which it is doing, or how far along it is. Polled rather than pushed, matching
 * every other live value in this GUI family: what the destination holds is server-side knowledge the
 * client has no view of.
 */
class RequesterHookScreen(private val menu: RequesterHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<RequesterHookMenu>(menu, playerInventory, title)
{
	private val clickHandler = ClickHandler(1)

	init
	{
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
		return clickHandler.tryHandle(button) || super.mouseClicked(mouseX, mouseY, button)
	}

	@Composable
	fun content() {
		LaunchedEffect(Unit) {
			while (true) {
				menu.requestStatus()
				delay(STATUS_POLL_MILLIS.milliseconds)
			}
		}

		var targets by remember { mutableStateOf(menu.currentStockingTargets()) }

		fun set(index: Int, resource: ResourceComponent, amount: Long) {
			targets = targets.first.toMutableList().also { it[index] = resource } to
				targets.second.toMutableList().also { it[index] = amount }
			menu.setStockingTarget(index, resource, amount)
		}

		BoilerplateTheme {
			ContainerPanel {
				Column(verticalArrangement = Arrangement.spacedBy(4)) {
					Text(Component.literal("Keep stocked"), dropShadow = false)
					StockingRowGrid(
						targets = targets.first,
						amounts = targets.second,
						columns = RequesterHookState.SLOTS,
						carried = { menu.carried },
						onSet = ::set,
						clickHandler = clickHandler,
					)
					Text(
						Component.literal("Scroll a target to set how many; below 1 is ∞ (just export)"),
						dropShadow = false,
						color = LocalTheme.current.darkTextColor,
					)

					StatusPanel(menu.status, targets.first, targets.second)
				}
			}
		}
	}

	/** The live half - see this class's own KDoc. Renders a neutral "checking" line until the first reply lands, rather than an empty panel that reads as "nothing is happening". */
	@Composable
	private fun StatusPanel(status: RequesterStatusPacket?, targets: List<ResourceComponent>, amounts: List<Long>) {
		Panel(variant = "inset") {
			Column(verticalArrangement = Arrangement.spacedBy(2)) {
				if (status == null) {
					Text(Component.literal("Checking…"), dropShadow = false, color = LocalTheme.current.darkTextColor)
					return@Column
				}

				// The mode line comes first and is never omitted: which of the two things this hook is
				// doing is otherwise invisible, and it decides where the orders are being served.
				val mode = if (status.supplyingInterface) "Supplying the interface next door" else "Stocking the inventory next door"
				Text(Component.literal(mode), dropShadow = false)

				if (status.detail.isNotEmpty()) {
					Text(Component.literal(status.detail), dropShadow = false, color = LocalTheme.current.darkTextColor)
					return@Column
				}

				// One line per configured target, so a row with several orders says which of them are
				// actually satisfied rather than collapsing to a single number that hides the laggard.
				for (index in targets.indices) {
					val target = targets[index]
					if (target.isBlank) continue
					val held = status.held.getOrNull(index) ?: continue
					val amount = amounts.getOrNull(index) ?: 1L
					val name = if (configuredFilterOn(target) != null) "filtered" else target.displayName().string
					val line = if (amount == UNBOUNDED_STOCK) "$name: $held (∞)" else "$name: $held / $amount"
					Text(Component.literal(line), dropShadow = false)
				}
			}
		}
	}

	companion object {
		/** How often the screen re-asks the server what this hook is doing - the same cadence the pattern terminal polls its own live preview at. */
		private const val STATUS_POLL_MILLIS = 250L
	}
}

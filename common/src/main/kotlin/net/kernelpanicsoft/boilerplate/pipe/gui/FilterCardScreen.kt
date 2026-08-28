package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.UpdateFilterCardModePacket
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.registry.FilterConditionTypeRegistry
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Editor for one [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem] stack: its
 * whitelist/blacklist mode, plus whichever fields the selected
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType] itself renders via its
 * own [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType.Content] - this
 * screen dispatches to it generically via [FilterCardMenu.type], the same reasoning
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.matches] already dispatches
 * through the registry instead of a hardcoded `when`, and doesn't otherwise know or care what
 * fields a given condition kind edits - each `Content` pushes its own edits directly via
 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.pushFieldUpdate]. [type] itself is fixed by
 * which item this is (see [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem]'s
 * own KDoc) - not shown as an editable control. [mode] stays local "optimistic edit + resend"
 * state (via [UpdateFilterCardModePacket]), the same pattern [SortingHookScreen] already uses for
 * the identical reason (a nested holder's fields aren't wired into live `@Sync` push).
 */
class FilterCardScreen(private val menu: FilterCardMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<FilterCardMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9
	private val middleClickHandler = MiddleClickHandler()

	init {
		start { content() }
	}

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (middleClickHandler.tryHandle(button)) return true
		return super.mouseClicked(mouseX, mouseY, button)
	}

	@Composable
	fun content() {
		var mode by remember { mutableStateOf(menu.mode) }

		Theme {
			ContainerPanel(contentWidth = contentWidth) {
				Column(verticalArrangement = Arrangement.spacedBy(6)) {
					Text(Component.literal("Mode"), dropShadow = false)
					RadioGroup(
						options = listOf(
							RadioOption(FilterMode.WHITELIST, Component.literal("Whitelist")),
							RadioOption(FilterMode.BLACKLIST, Component.literal("Blacklist")),
						),
						selected = mode,
						onSelected = {
							mode = it
							BoilerplateNetworkChannel.toServer(UpdateFilterCardModePacket(it))
						},
					)

					val conditionType = FilterConditionTypeRegistry.byId(menu.type)
					val state = menu.currentConditionState()
					if (conditionType != null && state != null) {
						conditionType.Content(menu, state, middleClickHandler)
					}
				}
			}
		}
	}
}

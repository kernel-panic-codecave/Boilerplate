package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.boilerplate.network.RequestCraftGridPreviewPacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftJobTreePacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftableListPacket
import net.kernelpanicsoft.boilerplate.network.RequestTerminalSearchResultsPacket
import net.kernelpanicsoft.boilerplate.network.RequestWarehouseDefragPacket
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.network.SResourceStack
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.kernelpanicsoft.boilerplate.util.resourceStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import kotlin.time.Duration.Companion.milliseconds

/**
 * [TerminalHookScreen]'s own single Store tab (see its KDoc for why there's no dedicated
 * autocraft-browsing tab), plus a real 3x3 grid ([CraftingTerminalHookState.grid][net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState.grid])
 * right underneath the output slots, its own virtual result slot ([LiveResultSlot]) filled by hand
 * or by anything physically piped to this terminal's own position - clicking it (or shift-clicking
 * for a vanilla-style quick-craft) crafts, exactly like a real crafting table's own result slot,
 * via [CraftingTerminalHookMenu.craftOnce]. A near-duplicate of [TerminalHookScreen] for the same
 * reason [CraftingTerminalHookMenu] duplicates [AbstractTerminalHookMenu] - see its own KDoc.
 */
class CraftingTerminalHookScreen(menu: CraftingTerminalHookMenu, playerInventory: Inventory, title: Component) :
	AbstractTerminalHookScreen<CraftingTerminalHookMenu>(menu, playerInventory, title) {

	override val mainTabId: String get() = "crafting"
	override val mainTabLabel: Component get() = Component.literal("Crafting")

	@Composable
	override fun additionalContent()
	{
		LaunchedEffect(Unit) {
			while (true) {
				BoilerplateNetworkChannel.toServer(RequestCraftGridPreviewPacket)
				delay(GRID_PREVIEW_POLL_MILLIS.milliseconds)
			}
		}
		Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(contentWidth)) {
			Row(horizontalArrangement = Arrangement.spacedBy(18), verticalAlignment = Alignment.CenterVertically) {
				Slots("grid", 3, 3)
				TerminalSlot(stack = menu.gridPreview, onClick =  {
					menu.requestCraftOnce(hasShiftDown(), hasControlDown())
					menu.requestGridPreview()
				})
			}
		}
	}


	companion object {
		private const val GRID_PREVIEW_POLL_MILLIS = 150L
	}
}

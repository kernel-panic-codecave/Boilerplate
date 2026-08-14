package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.WarehouseTerminalMenu
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Turns the attached face into a search/withdraw window over every
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity] reachable on the
 * network - see `docs/design/m3-warehouse-storage.md`. Purely a menu, like [SortingHookType] -
 * no per-tick behavior, the search itself runs on demand
 * ([WarehouseTerminalMenu.sendSearchResults]) rather than continuously.
 */
object WarehouseTerminalHookType : PipeHookType<WarehouseTerminalHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "warehouse_terminal"

	override fun createState(): WarehouseTerminalHookState = WarehouseTerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		WarehouseTerminalMenu(id, inventory, tile)
}

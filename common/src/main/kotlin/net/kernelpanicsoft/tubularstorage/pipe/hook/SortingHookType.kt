package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeMenu
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Turns the attached face into a filtered/prioritized/color-matched routing candidate - see
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter] and
 * `docs/design/m2-sorting-routing.md`. Purely declarative: all the actual filter/priority/color
 * evaluation happens in [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter.search] when a
 * route is resolved, not on a per-tick basis.
 */
object SortingHookType : PipeHookType<SortingHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "sorting"

	override fun createState(): SortingHookState = SortingHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingPipeMenu(id, inventory, tile, direction)
}

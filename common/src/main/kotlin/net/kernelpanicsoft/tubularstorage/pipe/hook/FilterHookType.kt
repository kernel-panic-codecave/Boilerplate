package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Turns the attached face into a filtered/prioritized/color-matched routing candidate - see
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter] and
 * `docs/design/m2-sorting-routing.md`. Purely declarative: all the actual filter/priority/color
 * evaluation happens in [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter.search] when a
 * route is resolved, not on a per-tick basis.
 */
object FilterHookType : PipeHookType<FilterHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "filter"

	override fun createState(): FilterHookState = FilterHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.FilterHook
}

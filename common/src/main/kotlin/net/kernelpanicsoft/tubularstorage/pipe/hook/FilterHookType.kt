package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.registry.NetworkTypeRegistry
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

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - A pure routing config - no active per-tick work of its own, just the lightest idle draw to stay online. */
	override val basePressureCost: Long = 1L

	override fun createState(): FilterHookState = FilterHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.FilterHook
}

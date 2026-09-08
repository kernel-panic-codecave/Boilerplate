package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.SortingHookMenu
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Turns the attached face into a filtered/prioritized/color-matched routing candidate - see
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter] and
 * `docs/design/m2-sorting-routing.md`. Filter/priority/color evaluation is purely declarative and
 * happens in [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.search] when a route is
 * resolved, not per tick.
 *
 * [FilterHookState.batchSize] is declarative too: it constrains the *amount* a transfer across this
 * face may carry, enforced where that amount is chosen (see
 * [net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType.extractRoutable]), so this hook
 * still holds nothing and does no per-tick work.
 */
object FilterHookType : PipeHookType<FilterHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "filter"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Item) }

	/** [PipeHookType.basePressureCost] - A pure routing config - no active per-tick work of its own, just the lightest idle draw to stay online. */
	override val basePressureCost: Long = 1L

	override fun createState(): FilterHookState = FilterHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.FilterHook
}

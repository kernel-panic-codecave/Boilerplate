package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.ResourceMeasure
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.state.BlockState

/**
 * The counted half of the pooled racks: one stack-denominated pool shared by every
 * [ResourceMeasure.DISCRETE] kind - items, and anything else registered that comes in whole things.
 *
 * Where [UnstackableRackBlockEntity] pools *item counts* and so charges a shulker box the same as a
 * cobblestone, this charges each resource its share of a stack: 64 cobblestone, 16 ender pearls or
 * one shulker box each cost one of its stacks. That is what makes it a buffer for a process handling
 * a great many different items in small amounts - a hundred distinct single items cost a hundred
 * sixty-fourths of a stack between them, not a hundred slots.
 */
class DistributedMultiBufferBlockEntity(pos: BlockPos, state: BlockState) :
	PooledRackBlockEntity(TileRegistry.DistributedMultiBuffer, pos, state) {

	override val capacityWholes: Long get() = BoilerplateConfig.Gameplay.Capacities.distributedMultiBufferStacks

	override val wholeName: String get() = "stacks"

	override val label: String get() = "Distributed Multi Buffer"

	override fun holds(kind: ResourceKind): Boolean = kind.measure == ResourceMeasure.DISCRETE

	override fun createMenu(i: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		DistributedMultiBufferMenu(i, inventory, this)
}

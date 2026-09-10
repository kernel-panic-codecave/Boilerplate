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
 * The measured half of the pooled racks: one bucket-denominated pool shared by every
 * [ResourceMeasure.UNIT] kind at once - fluids, an addon's chemicals, anything else registered that
 * is counted in units of a larger whole.
 *
 * A bucket of capacity is a bucket however finely it is divided, which is the point: a millibucket
 * each of a thousand fluids fills exactly as much of it as one bucket of water, where a thousand
 * ordinary tanks would be needed to hold the same thing. See [PooledRackBlockEntity] for the shape
 * all three share and [PooledResourceStorage] for the pool.
 */
class DistributedMultiTankBlockEntity(pos: BlockPos, state: BlockState) :
	PooledRackBlockEntity(TileRegistry.DistributedMultiTank, pos, state) {

	override val capacityWholes: Long get() = BoilerplateConfig.Gameplay.Capacities.distributedMultiTankBuckets

	override val wholeName: String get() = "buckets"

	override val label: String get() = "Distributed Multi Tank"

	override fun holds(kind: ResourceKind): Boolean = kind.measure == ResourceMeasure.UNIT

	override fun createMenu(i: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		DistributedMultiTankMenu(i, inventory, this)
}

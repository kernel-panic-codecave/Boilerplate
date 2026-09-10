package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.state.BlockState

/**
 * Both of the other two at once: one pool that every registered kind shares, whatever its
 * [net.kernelpanicsoft.boilerplate.resource.ResourceMeasure].
 *
 * A stack and a bucket are the same amount of room here (see [PooledResourceStorage] for why that
 * rate is the only one that makes items and fluids comparable at all), so a single block buffers a
 * process whose inputs and outputs are a mixture of both - which is the case a
 * [DistributedMultiTankBlockEntity] beside a [DistributedMultiBufferBlockEntity] handles only by
 * dividing the room up in advance and hoping the split was right.
 *
 * Takes every kind rather than naming the two measures that exist today, so a kind registered later
 * - including a [net.kernelpanicsoft.boilerplate.resource.ResourceMeasure.SCALAR] one, if any kind
 * ever declares itself storable and scalar at once - lands here without an edit.
 */
class OmnibufferBlockEntity(pos: BlockPos, state: BlockState) :
	PooledRackBlockEntity(TileRegistry.Omnibuffer, pos, state) {

	override val capacityWholes: Long get() = BoilerplateConfig.Gameplay.Capacities.omnibufferWholes

	override val wholeName: String get() = "wholes"

	override val label: String get() = "Omnibuffer"

	/** Everything, so long as it can be stored at all - a kind with no storage has nothing to put here. */
	override fun holds(kind: ResourceKind): Boolean = kind.storage != null

	override fun createMenu(i: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		OmnibufferMenu(i, inventory, this)
}

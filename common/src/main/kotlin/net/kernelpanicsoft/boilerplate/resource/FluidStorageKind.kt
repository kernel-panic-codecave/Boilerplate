package net.kernelpanicsoft.boilerplate.resource

import earth.terrarium.common_storage_lib.context.impl.SimpleItemContext
import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.archie.transfer.exposeFluidStorage
import net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind
import net.kernelpanicsoft.boilerplate.resource.roomFor
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * The fluid kind's storage surface - `FluidApi.BLOCK` for racks (which is what makes a
 * [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity], or any other mod's tank,
 * an ordinary warehouse rack), [ArchieFluidStorage] for buffers.
 *
 * Reached through [FluidKind], never named by a call site - see [ItemStorageKind].
 */
object FluidStorageKind : ResourceStorageKind {
	override val blank: ResourceComponent get() = FluidResource.BLANK

	override fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>? =
		FluidApi.BLOCK.find(level, pos, direction)

	override fun <T : BlockEntity> BlockEntityType<T>.exposeStorage(
		selector: (tile: T, direction: Direction?) -> CommonStorage<*>?
	) = exposeFluidStorage { tile, direction -> selector(tile, direction)?.cast() }

	override fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val fluid = resource as? FluidResource ?: return 0
		return storage.cast<FluidResource>().insert(fluid, amount, simulate)
	}

	override fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val fluid = resource as? FluidResource ?: return 0
		return storage.cast<FluidResource>().extract(fluid, amount, simulate)
	}

	override fun roomFor(storage: CommonStorage<*>, resource: ResourceComponent, limit: Long): Long {
		val fluid = resource as? FluidResource ?: return 0
		return storage.cast<FluidResource>().roomFor(fluid, limit)
	}

	override fun insertInto(storage: CommonStorage<*>, index: Int, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val fluid = resource as? FluidResource ?: return 0
		return storage.cast<FluidResource>().insert(index, fluid, amount, simulate)
	}

	/**
	 * A bucket, a tank, or anything else exposing `FluidApi.ITEM` - resolved through the capability
	 * rather than by special-casing [net.minecraft.world.item.BucketItem], so every mod's own
	 * containers work.
	 */
	override fun findInItem(holder: CommonStorage<ItemResource>, slot: Int): CommonStorage<*>? {
		val resource = holder.getResource(slot)
		if (resource.isBlank) return null
		val stack = resource.toStack(holder.getAmount(slot).toInt().coerceAtLeast(1))
		return FluidApi.ITEM.find(stack, SimpleItemContext.of(holder, slot))
	}

	/**
	 * [capacity] arrives in millibuckets - the unit a player authors in - and is converted through
	 * [FluidAmounts.toPlatformAmount] rather than any `FluidAmounts` constant, for the reason
	 * [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity.getCapacity] documents:
	 * those constants all read `0` in Common Storage Lib 0.0.5, and a zero-capacity tank silently
	 * accepts nothing, which here would look like the gantry losing every fluid it ever picked up.
	 */
	override fun createBuffer(slots: Int, capacity: Long): CommonStorage<*> =
		ArchieFluidStorage(FluidAmounts.toPlatformAmount(capacity), slots)
}

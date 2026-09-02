package net.kernelpanicsoft.boilerplate.registry

import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.benwoodworth.knbt.NbtTag
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.network.ResourceStorageKind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * [ResourceStorageKind] implementations for the two kinds Boilerplate ships. Kept beside the
 * registry that hands them out rather than inside [ResourceKindRegistry]'s own anonymous objects,
 * which are already dense.
 */

/** The item kind's storage surface - `ItemApi.BLOCK` for racks, [ArchieItemStorage] for buffers. */
object ItemStorageKind : ResourceStorageKind {
	override fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>? =
		ItemApi.BLOCK.find(level, pos, direction)

	@Suppress("UNCHECKED_CAST")
	override fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val item = resource as? ItemResource ?: return 0
		return (storage as CommonStorage<ItemResource>).insert(item, amount, simulate)
	}

	@Suppress("UNCHECKED_CAST")
	override fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val item = resource as? ItemResource ?: return 0
		return (storage as CommonStorage<ItemResource>).extract(item, amount, simulate)
	}

	override fun createBuffer(slots: Int, accepts: (ResourceComponent) -> Boolean, onChange: () -> Unit): CommonStorage<*> =
		ArchieItemStorage(slots, { accepts(it) }) { onChange() }

	override fun encodeBuffer(buffer: CommonStorage<*>): NbtTag =
		SerializationManager.nbt.encodeToNbtTag(ArchieItemStorage.serializer(), buffer as ArchieItemStorage)

	override fun decodeBuffer(buffer: CommonStorage<*>, tag: NbtTag) {
		val target = buffer as? ArchieItemStorage ?: return
		val restored = runCatching { SerializationManager.nbt.decodeFromNbtTag(ArchieItemStorage.serializer(), tag) }.getOrNull() ?: return
		for (i in 0 until minOf(target.size(), restored.size())) target[i].set(restored[i].getItem())
	}
}

/**
 * The fluid kind's storage surface - `FluidApi.BLOCK` for racks (which is what makes a
 * [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity], or any other mod's tank,
 * an ordinary warehouse rack), [ArchieFluidStorage] for buffers.
 */
object FluidStorageKind : ResourceStorageKind {
	/**
	 * One buffer tank's capacity, in millibuckets.
	 *
	 * Routed through [FluidAmounts.toPlatformAmount] rather than any `FluidAmounts` constant, for
	 * the reason [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity.capacity]
	 * documents: those constants all read `0` in Common Storage Lib 0.0.5, and a zero-capacity
	 * buffer silently accepts nothing, which here would look like the gantry losing every fluid it
	 * ever picked up.
	 */
	private const val BUFFER_TANK_MILLIBUCKETS = 16_000L

	override fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>? =
		FluidApi.BLOCK.find(level, pos, direction)

	@Suppress("UNCHECKED_CAST")
	override fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val fluid = resource as? FluidResource ?: return 0
		return (storage as CommonStorage<FluidResource>).insert(fluid, amount, simulate)
	}

	@Suppress("UNCHECKED_CAST")
	override fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val fluid = resource as? FluidResource ?: return 0
		return (storage as CommonStorage<FluidResource>).extract(fluid, amount, simulate)
	}

	override fun createBuffer(slots: Int, accepts: (ResourceComponent) -> Boolean, onChange: () -> Unit): CommonStorage<*> =
		ArchieFluidStorage(FluidAmounts.toPlatformAmount(BUFFER_TANK_MILLIBUCKETS), slots, { accepts(it) }) { onChange() }

	override fun encodeBuffer(buffer: CommonStorage<*>): NbtTag =
		SerializationManager.nbt.encodeToNbtTag(ArchieFluidStorage.serializer(), buffer as ArchieFluidStorage)

	override fun decodeBuffer(buffer: CommonStorage<*>, tag: NbtTag) {
		val target = buffer as? ArchieFluidStorage ?: return
		val restored = runCatching { SerializationManager.nbt.decodeFromNbtTag(ArchieFluidStorage.serializer(), tag) }.getOrNull() ?: return
		for (i in 0 until minOf(target.size(), restored.size())) {
			val resource = restored.getResource(i)
			if (resource.isBlank) continue
			target.insert(resource, restored.getAmount(i), false)
		}
	}
}

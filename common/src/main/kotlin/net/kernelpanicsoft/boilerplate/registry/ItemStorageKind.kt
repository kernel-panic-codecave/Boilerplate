package net.kernelpanicsoft.boilerplate.registry

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.archie.transfer.exposeItemStorage
import net.kernelpanicsoft.boilerplate.network.ResourceStorageKind
import net.kernelpanicsoft.boilerplate.network.roomFor
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * The item kind's storage surface - `ItemApi.BLOCK` for racks, [ArchieItemStorage] for buffers.
 *
 * Reached through [ItemKind], never named by a call site: a caller holding a resource of unknown
 * kind asks the registry which kind it is and uses that kind's own surface.
 */
object ItemStorageKind : ResourceStorageKind {
	override fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>? =
		ItemApi.BLOCK.find(level, pos, direction)

	override fun <T : BlockEntity> BlockEntityType<T>.exposeStorage(
		selector: (tile: T, direction: Direction?) -> CommonStorage<*>?
	) = exposeItemStorage { tile, direction -> selector(tile, direction)?.cast() }

	override fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val item = resource as? ItemResource ?: return 0
		return storage.cast<ItemResource>().insert(item, amount, simulate)
	}

	override fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val item = resource as? ItemResource ?: return 0
		return storage.cast<ItemResource>().extract(item, amount, simulate)
	}

	override fun roomFor(storage: CommonStorage<*>, resource: ResourceComponent, limit: Long): Long {
		val item = resource as? ItemResource ?: return 0
		return storage.cast<ItemResource>().roomFor(item, limit)
	}

	override fun insertInto(storage: CommonStorage<*>, index: Int, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val item = resource as? ItemResource ?: return 0
		return storage.cast<ItemResource>().insert(index, item, amount, simulate)
	}

	/** [capacity] is ignored: an item slot's limit is the stack it holds, not a number this kind is told. */
	override fun createBuffer(slots: Int, capacity: Long): CommonStorage<*> = ArchieItemStorage(slots)
}

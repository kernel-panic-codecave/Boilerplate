package net.kernelpanicsoft.tubularstorage.pipe.entity

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.tubularstorage.network.PipeContentsSyncPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.client.PipeContentsClientCache
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.power.PressureConsumer
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * A plain pipe segment: holds and advances [TravelingItem]s in transit, and participates in the
 * [PipeNetworkManager] network. Carries no hooks - see
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeMountBlockEntity] for the (heavier, hook-
 * carrying) variant a plain pipe promotes into the moment it gets its first hook attached. Kept
 * separate rather than folding hooks onto every pipe unconditionally: hooks bring six always-
 * allocated 9-slot filter grids plus a synced map, real per-instance memory/NBT/tick cost a plain
 * pipe (the overwhelming majority of a build) shouldn't pay for.
 */
open class PipeBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
	NBTBlockEntity(type, pos, state), PressureConsumer {

	constructor(pos: BlockPos, state: BlockState) : this(TileRegistry.Pipe, pos, state)

	val travelingItems by listField(TravelingItem.serializer()) { emptyList() }

	private var ticksSinceSync = 0

	override fun setRemoved() {
		super.setRemoved()
		val serverLevel = level as? ServerLevel ?: return
		PipeNetworkManager.get(serverLevel).onRemoved(blockPos)
		PipeContentsClientCache.remove(blockPos)
	}

	/**
	 * Archie's [listField] decodes a fresh list from storage on every property access; only the
	 * structural operations [net.kernelpanicsoft.archie.serialization.ObservableList] actually
	 * intercepts (`add`/`removeAt`/`set`/`clear`, ...) persist. [travelingItems] is fetched exactly
	 * once here and touched only through index-based mutation, with [TravelingItem.copy] standing
	 * in for field mutation - in-place mutation of an element already in the list, or removal via
	 * an `Iterator`, silently affects only a throwaway copy.
	 */
	open fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		// Idempotent - this is also how a pipe re-registers after a chunk (re)load, there being no
		// dedicated "block entity now active" hook to call it from instead.
		PipeNetworkManager.get(serverLevel).ensureRegistered(serverLevel, pos)
		var hopped = false

		val items = travelingItems
		var index = 0
		while (index < items.size) {
			val item = items[index]
			val progress = item.progress + SEGMENT_SPEED
			if (progress < 1f) {
				items[index] = item.copy(progress = progress)
				index++
				continue
			}

			val nextPos = item.path.firstOrNull()
			if (nextPos == null) {
				jam(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			if (isPipe(serverLevel, nextPos)) {
				val nextTile = serverLevel.getBlockEntity(nextPos) as? PipeBlockEntity
				if (nextTile == null) {
					jam(serverLevel, pos, item)
					items.removeAt(index)
					hopped = true
					continue
				}
				val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
				nextTile.travelingItems += TravelingItem(item.stack, direction?.opposite ?: item.fromDirection, 0f, item.path.drop(1), item.color)
				items.removeAt(index)
				hopped = true
				continue
			}

			val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
			val storage = ItemApi.BLOCK.find(serverLevel, nextPos, direction?.opposite)
			if (storage == null) {
				jam(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			val resource = ItemResource.of(item.stack)
			val inserted = storage.insert(resource, item.stack.count.toLong(), false)
			when {
				inserted >= item.stack.count -> {
					items.removeAt(index)
					hopped = true
				}
				inserted > 0 -> {
					item.stack.shrink(inserted.toInt())
					items[index] = item.copy(progress = 1f)
					hopped = true
					index++
				}
				else -> {
					items[index] = item.copy(progress = 1f) // stall, retry next tick
					index++
				}
			}
		}

		ticksSinceSync++
		if (hopped || (items.isNotEmpty() && ticksSinceSync >= SYNC_INTERVAL_TICKS)) {
			ticksSinceSync = 0
			syncToNearbyPlayers(serverLevel, pos, items)
		}
	}

	private fun jam(level: ServerLevel, pos: BlockPos, item: TravelingItem) {
		ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, item.stack)
			.also { level.addFreshEntity(it) }
	}

	private fun syncToNearbyPlayers(level: ServerLevel, pos: BlockPos, items: List<TravelingItem>) {
		TubularStorageNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SYNC_RADIUS,
			PipeContentsSyncPacket(pos, items.toList()),
		)
	}

	protected fun isPipe(level: Level, pos: BlockPos): Boolean = level.getBlockState(pos).block is PipeBlock

	companion object {
		/** Progress gained per tick; 1f / SEGMENT_SPEED ticks to cross one pipe segment. */
		const val SEGMENT_SPEED = 1f / 20f
		const val SYNC_INTERVAL_TICKS = 4
		const val SYNC_RADIUS = 32.0

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: PipeBlockEntity) = tile.tick(level, pos, state)
	}
}

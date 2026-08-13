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
 * A plain pipe segment. Holds and advances [TravelingItem]s in transit; never initiates a pull
 * itself (see [net.kernelpanicsoft.tubularstorage.pipe.entity.ExtractorPipeBlockEntity]).
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

	open fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		// Idempotent - this is also how a pipe re-registers after a chunk (re)load, there being no
		// dedicated "block entity now active" hook to call it from instead.
		PipeNetworkManager.get(serverLevel).ensureRegistered(serverLevel, pos)
		var hopped = false

		val iterator = travelingItems.iterator()
		while (iterator.hasNext()) {
			val item = iterator.next()
			item.progress += SEGMENT_SPEED
			if (item.progress < 1f) continue

			val nextPos = item.path.firstOrNull()
			if (nextPos == null) {
				jam(serverLevel, pos, item)
				iterator.remove()
				hopped = true
				continue
			}

			if (isPipe(serverLevel, nextPos)) {
				val nextTile = serverLevel.getBlockEntity(nextPos) as? PipeBlockEntity
				if (nextTile == null) {
					jam(serverLevel, pos, item)
					iterator.remove()
					hopped = true
					continue
				}
				val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
				nextTile.travelingItems += TravelingItem(item.stack, direction?.opposite ?: item.fromDirection, 0f, item.path.drop(1))
				iterator.remove()
				hopped = true
				continue
			}

			val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
			val storage = ItemApi.BLOCK.find(serverLevel, nextPos, direction?.opposite)
			if (storage == null) {
				jam(serverLevel, pos, item)
				iterator.remove()
				hopped = true
				continue
			}

			val resource = ItemResource.of(item.stack)
			val inserted = storage.insert(resource, item.stack.count.toLong(), false)
			when {
				inserted >= item.stack.count -> {
					iterator.remove()
					hopped = true
				}
				inserted > 0 -> {
					item.stack.shrink(inserted.toInt())
					item.progress = 1f
					hopped = true
				}
				else -> item.progress = 1f // stall, retry next tick
			}
		}

		ticksSinceSync++
		if (hopped || ticksSinceSync >= SYNC_INTERVAL_TICKS) {
			ticksSinceSync = 0
			syncToNearbyPlayers(serverLevel, pos)
		}
	}

	private fun jam(level: ServerLevel, pos: BlockPos, item: TravelingItem) {
		ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, item.stack)
			.also { level.addFreshEntity(it) }
	}

	private fun syncToNearbyPlayers(level: ServerLevel, pos: BlockPos) {
		TubularStorageNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SYNC_RADIUS,
			PipeContentsSyncPacket(pos, travelingItems.toList()),
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

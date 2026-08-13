package net.kernelpanicsoft.tubularstorage.pipe.entity

import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * A pipe that periodically pulls a resource from an adjacent (non-pipe) inventory and, if the
 * network can route it somewhere that will accept it, spawns it as a [TravelingItem]. The only
 * self-initiating block in the pipe network - plain [PipeBlock]s never pull on their own.
 */
class ExtractorPipeBlockEntity(pos: BlockPos, state: BlockState) : PipeBlockEntity(TileRegistry.ExtractorPipe, pos, state) {
	private var ticksSinceExtraction = 0

	override fun tick(level: Level, pos: BlockPos, state: BlockState) {
		super.tick(level, pos, state)
		if (level.isClientSide) return
		if (++ticksSinceExtraction < EXTRACTION_INTERVAL_TICKS) return
		ticksSinceExtraction = 0
		tryExtract(level as ServerLevel, pos)
	}

	private fun tryExtract(level: ServerLevel, pos: BlockPos) {
		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			if (level.getBlockState(neighborPos).block is PipeBlock) continue
			val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: continue

			for (slotIndex in 0 until storage.size()) {
				val resource = storage.get(slotIndex).resource
				if (resource.isBlank) continue

				val available = storage.extract(resource, EXTRACTION_AMOUNT, true)
				if (available <= 0) continue

				val route = PipeRouter.findRoute(level, pos, resource, exclude = neighborPos) ?: continue

				val extracted = storage.extract(resource, available, false)
				if (extracted <= 0) continue

				travelingItems += TravelingItem(resource.toStack(extracted.toInt()), direction, 0f, route)
				return
			}
		}
	}

	companion object {
		const val EXTRACTION_INTERVAL_TICKS = 10
		const val EXTRACTION_AMOUNT = 64L
	}
}

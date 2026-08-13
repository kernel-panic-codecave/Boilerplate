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
		// Not a filter on what's pulled (see docs/design/m2-sorting-routing.md) - just the color
		// tag a sorting module (if applied) stamps on whatever this extractor sends out.
		val color = if (hasSortingModule) routing.color else null

		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			if (level.getBlockState(neighborPos).block is PipeBlock) continue
			val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: continue

			for (slotIndex in 0 until storage.size()) {
				val resource = storage.get(slotIndex).resource
				if (resource.isBlank) continue

				val available = storage.extract(resource, EXTRACTION_AMOUNT, true)
				if (available <= 0) continue

				val route = PipeRouter.findRoute(level, pos, resource, color, exclude = neighborPos) ?: continue

				val extracted = storage.extract(resource, available, false)
				if (extracted <= 0) continue

				travelingItems += TravelingItem(resource.toStack(extracted.toInt()), direction, 0f, route, color)
				return
			}
		}
	}

	companion object {
		const val EXTRACTION_INTERVAL_TICKS = 10
		const val EXTRACTION_AMOUNT = 64L
	}
}

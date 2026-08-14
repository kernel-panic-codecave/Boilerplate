package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/**
 * Periodically pulls a resource from the (non-pipe) inventory on the attached face and, if the
 * network can route it somewhere that will accept it, spawns it as a [TravelingItem]. The only
 * self-initiating hook - a [SortingHookType] hook never pulls on its own.
 */
object ExtractionHookType : PipeHookType() {
	val ID: ResourceLocation = TubularStorage.MOD % "extraction"

	override fun createState(): ExtractionHookState = ExtractionHookState()

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: HookHolderState) {
		state as ExtractionHookState
		state.ticksSinceExtraction++
		if (state.ticksSinceExtraction < EXTRACTION_INTERVAL_TICKS) return
		state.ticksSinceExtraction = 0
		tryExtract(level, pos, direction, tile, state)
	}

	private fun tryExtract(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: ExtractionHookState) {
		// Not a filter on what's pulled (see docs/design/m2-sorting-routing.md) - just the color
		// tag this hook stamps on whatever it sends out.
		val color = state.color

		val neighborPos = pos.relative(direction)
		if (level.getBlockState(neighborPos).block is PipeBlock) return
		val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: return

		for (slotIndex in 0 until storage.size()) {
			val resource = storage.get(slotIndex).resource
			if (resource.isBlank) continue

			val available = storage.extract(resource, EXTRACTION_AMOUNT, true)
			if (available <= 0) continue

			val route = PipeRouter.findRoute(level, pos, resource, color, exclude = neighborPos) ?: continue

			val extracted = storage.extract(resource, available, false)
			if (extracted <= 0) continue

			tile.travelingItems += TravelingItem(resource.toStack(extracted.toInt()), direction, 0f, route, color)
			return
		}
	}

	const val EXTRACTION_INTERVAL_TICKS = 10
	const val EXTRACTION_AMOUNT = 64L
}

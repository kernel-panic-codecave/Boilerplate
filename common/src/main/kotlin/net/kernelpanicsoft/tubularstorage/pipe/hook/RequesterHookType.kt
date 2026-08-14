package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/**
 * Periodically checks the attached (non-pipe) inventory against [RequesterHookState.request]'s
 * standing order and, if it's short, asks [RequestFulfillment] to top it back up - see
 * `docs/design/m3-warehouse-storage.md`.
 */
object RequesterHookType : PipeHookType<RequesterHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "requester"

	override fun createState(): RequesterHookState = RequesterHookState()

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: RequesterHookState) {
		state.ticksSinceRequest++
		if (state.ticksSinceRequest < REQUEST_INTERVAL_TICKS) return
		state.ticksSinceRequest = 0
		tryRequest(level, pos, direction, state)
	}

	private fun tryRequest(level: ServerLevel, pos: BlockPos, direction: Direction, state: RequesterHookState) {
		val order = state.request.get(0)
		if (order.resource.isBlank) return

		val neighborPos = pos.relative(direction)
		val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: return
		var current = 0L
		for (i in 0 until storage.size()) if (storage.getResource(i) == order.resource) current += storage.getAmount(i)

		val shortfall = order.amount - current
		if (shortfall <= 0) return
		RequestFulfillment.request(level, pos, order.resource, shortfall, neighborPos)
	}

	const val REQUEST_INTERVAL_TICKS = 40
}

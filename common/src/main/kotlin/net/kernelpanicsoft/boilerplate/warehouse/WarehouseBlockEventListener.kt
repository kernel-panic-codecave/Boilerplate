package net.kernelpanicsoft.boilerplate.warehouse

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

object WarehouseBlockEventListener {
	val activeControllers = mutableSetOf<BlockPos>()
	fun register() {
		BlockEvent.PLACE.register { level, pos, state, placer ->
			if (level is ServerLevel) {
				onBlockChanged(level, pos, isRemoval = false)
			}
			EventResult.pass()
		}

		BlockEvent.BREAK.register { level, pos, state, player, xp ->
			if (level is ServerLevel) {
				onBlockChanged(level, pos, isRemoval = true)
			}
			EventResult.pass()
		}
	}

	private fun onBlockChanged(level: ServerLevel, pos: BlockPos, isRemoval: Boolean) {
		val chunkX = pos.x shr 4
		val chunkZ = pos.z shr 4
		if (!level.hasChunk(chunkX, chunkZ)) return

		for (controllerPos in activeControllers) {
			val cChunkX = controllerPos.x shr 4
			val cChunkZ = controllerPos.z shr 4
			if (!level.hasChunk(cChunkX, cChunkZ)) continue

			val controller = level.getBlockEntity(controllerPos) as? WarehouseControllerBlockEntity ?: continue
			val bounds = controller.bounds ?: continue

			if (bounds.contains(pos)) {
				if (isRemoval) {
					controller.onBlockRemovedFromIndex(pos)
				} else {
					controller.onBlockInsertedAtIndex(pos)
				}
				break
			}
		}
	}
}
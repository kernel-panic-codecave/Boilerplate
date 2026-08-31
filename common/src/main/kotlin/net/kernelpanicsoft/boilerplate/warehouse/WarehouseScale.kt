package net.kernelpanicsoft.boilerplate.warehouse

import net.minecraft.server.level.ServerLevel

/**
 * A tier's own gantry throughput, both halves of it: [baseSpeedPerTick] (the [GantryState.tick]
 * rate before [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick]'s multiplier -
 * live once a pressure line reaches the controller, e.g. a well-fed one roughly doubles a
 * COMPACT/REGIONAL gantry's speed per its own draw ratios) and [basePressureCost]/[maxPressureDraw]
 * (this tier's own [PressureConsumer][net.kernelpanicsoft.boilerplate.power.PressureConsumer]
 * values - what gates the multiplier at all: no reachable line hard-gates the gantry to `0.0`).
 * A bigger warehouse's gantry needs to physically cover far more ground per job on average, so it
 * moves faster - and costs more to run that fast - the bigger the tier; exact unit/cost balance is
 * explicitly deferred to playtesting (`docs/design/m5-pressure-power.md`), not meant to be
 * load-bearing yet.
 */
enum class WarehouseScale(
	val maxBlockCount: Long,
	val tickBudgetMs: Double,
	val baseSpeedPerTick: Double,
	val basePressureCost: Long,
	val maxPressureDraw: Long,
) {
	COMPACT(500, 0.25, 0.2, 10, 20) {
		override fun placeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds) {
			entity.placeFrameSync(level, bounds)
		}

		override fun removeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds) {
			entity.removeFrameSync(level, bounds)
		}

		override fun createScanTask(level: ServerLevel, bounds: Bounds, index: WarehouseIndex): ScanTask {
			return ImmediateScanTask(level, bounds, index)
		}
	},

	REGIONAL(2500, 1.0, 0.5, 40, 80) {
		override fun placeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds) {
			entity.placeFrameAsync(level, bounds)
		}

		override fun removeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds) {
			entity.removeFrameAsync(level, bounds)
		}

		override fun createScanTask(level: ServerLevel, bounds: Bounds, index: WarehouseIndex): ScanTask {
			return ChunkParallelScanTask(level, bounds, index)
		}
	},

	MEGA(Long.MAX_VALUE, 5.0, 1.0, 160, 320) {
		override fun placeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds) {
			entity.queueFrameOperation(bounds, isRemoval = false)
		}

		override fun removeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds) {
			entity.queueFrameOperation(bounds, isRemoval = true)
		}

		override fun createScanTask(level: ServerLevel, bounds: Bounds, index: WarehouseIndex): ScanTask {
			return IncrementalTickScanTask(level, bounds, index, tickBudgetMs)
		}
	};

	abstract fun placeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds)
	abstract fun removeFrame(entity: WarehouseControllerBlockEntity, level: ServerLevel, bounds: Bounds)
	abstract fun createScanTask(level: ServerLevel, bounds: Bounds, index: WarehouseIndex): ScanTask

	companion object {
		fun fromBounds(bounds: Bounds): WarehouseScale {
			val count = bounds.railStructure().size
			return entries.first { count <= it.maxBlockCount }
		}
	}
}
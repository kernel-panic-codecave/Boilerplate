package net.kernelpanicsoft.tubularstorage.warehouse

import net.minecraft.server.level.ServerLevel

/**
 * A tier's own gantry throughput, both halves of it: [baseSpeedPerTick] (the [GantryState.tick]
 * rate before [net.kernelpanicsoft.tubularstorage.power.PressureConsumer.onPressureTick]'s
 * multiplier, currently always `1.0` - a no-op until M5 gives that hook a real pressure network to
 * draw from) and [basePressureCost]/[maxPressureDraw] (this tier's own [PressureConsumer][net.kernelpanicsoft.tubularstorage.power.PressureConsumer]
 * values, wired now even though nothing draws pressure from a real network yet, same as every other
 * M1-M4 system's [PressureConsumer][net.kernelpanicsoft.tubularstorage.power.PressureConsumer]
 * conformance). A bigger warehouse's gantry needs to physically cover far more ground per job on
 * average, so it moves faster - and costs more to run that fast - the bigger the tier; exact
 * unit/cost balance is explicitly deferred to playtesting (`docs/design/m5-pressure-power.md`), not
 * meant to be load-bearing yet.
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
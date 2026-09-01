package net.kernelpanicsoft.boilerplate.warehouse

import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

interface ScanTask {
	/**
	 * One tick's slice of work, called every tick by [WarehouseIndex]. Returns `true` once the scan
	 * is fully finished (whether completed or cancelled), `false` to be driven again next tick.
	 */
	fun tick(): Boolean

	/** Cancels any background operations if a new scan is scheduled before completion. */
	fun cancel()
}

/** Immediate single-tick scan: the whole bounds walked in one call, synchronous - used when there's no reason to spread the scan out. */
class ImmediateScanTask(
	private val level: ServerLevel,
	private val bounds: Bounds,
	private val index: WarehouseIndex
) : ScanTask {
	private var completed = false

	override fun tick(): Boolean {
		if (completed) return true

		val results = mutableMapOf<ResourceIdentity, MutableList<WarehouseIndex.RackSlotRef>>()
		val discoveredContainers = mutableSetOf<BlockPos>()

		val mutablePos = BlockPos.MutableBlockPos()
		val min = bounds.min
		val max = bounds.max

		for (x in min.x..max.x) {
			for (y in min.y..max.y) {
				for (z in min.z..max.z) {
					if (!level.hasChunk(x shr 4, z shr 4)) continue

					mutablePos.set(x, y, z)
					val state = level.getBlockState(mutablePos)
					if (state.isAir || !state.hasBlockEntity()) continue

					val immutablePos = mutablePos.immutable()
					if (index.scanPosition(level, immutablePos, results)) {
						discoveredContainers.add(immutablePos)
					}
				}
			}
		}

		index.replaceAll(level, results, discoveredContainers)
		completed = true
		return true
	}

	override fun cancel() {}
}

/** Main-thread chunk-wise batching: walks the bounds one chunk at a time, processing up to 4 loaded chunks per tick so even a huge warehouse only ever costs a bounded slice of a single tick. */
class ChunkParallelScanTask(
	private val level: ServerLevel,
	private val bounds: Bounds,
	private val index: WarehouseIndex
) : ScanTask {
	private val pendingLocations = mutableMapOf<ResourceIdentity, MutableList<WarehouseIndex.RackSlotRef>>()
	private val discoveredContainers = mutableSetOf<BlockPos>()

	private val minChunkX = bounds.min.x shr 4
	private val maxChunkX = bounds.max.x shr 4
	private val minChunkZ = bounds.min.z shr 4
	private val maxChunkZ = bounds.max.z shr 4

	private var currentChunkX = minChunkX
	private var currentChunkZ = minChunkZ
	private var cancelled = false

	override fun tick(): Boolean {
		if (cancelled) return true

		var chunksProcessedThisTick = 0
		val maxChunksPerTick = 4

		val mutablePos = BlockPos.MutableBlockPos()

		while (currentChunkX <= maxChunkX) {
			while (currentChunkZ <= maxChunkZ) {
				if (chunksProcessedThisTick >= maxChunksPerTick) {
					return false
				}

				if (level.hasChunk(currentChunkX, currentChunkZ)) {
					val startX = maxOf(bounds.min.x, currentChunkX shl 4)
					val endX = minOf(bounds.max.x, (currentChunkX shl 4) + 15)
					val startZ = maxOf(bounds.min.z, currentChunkZ shl 4)
					val endZ = minOf(bounds.max.z, (currentChunkZ shl 4) + 15)

					for (x in startX..endX) {
						for (y in bounds.min.y..bounds.max.y) {
							for (z in startZ..endZ) {
								mutablePos.set(x, y, z)
								val state = level.getBlockState(mutablePos)
								if (state.isAir || !state.hasBlockEntity()) continue

								val immutablePos = mutablePos.immutable()
								if (index.scanPosition(level, immutablePos, pendingLocations)) {
									discoveredContainers.add(immutablePos)
								}
							}
						}
					}
				}

				currentChunkZ++
				chunksProcessedThisTick++
			}
			currentChunkZ = minChunkZ
			currentChunkX++
		}

		index.replaceAll(level, pendingLocations, discoveredContainers)
		return true
	}

	override fun cancel() {
		cancelled = true
	}
}

/** Time-budgeted incremental main-thread tick scan: resumes where the previous tick left off, bounded by [tickBudgetMs], so a massive bounds costs several ticks of small slices rather than one long stall. */
class IncrementalTickScanTask(
	private val level: ServerLevel,
	private val bounds: Bounds,
	private val index: WarehouseIndex,
	private val tickBudgetMs: Double
) : ScanTask {
	companion object {
		private const val BUDGET_CHECK_INTERVAL = 256
	}

	private val pendingLocations = mutableMapOf<ResourceIdentity, MutableList<WarehouseIndex.RackSlotRef>>()
	private val discoveredContainers = mutableSetOf<BlockPos>()

	private var currX = bounds.min.x
	private var currY = bounds.min.y
	private var currZ = bounds.min.z

	private var cancelled = false

	override fun tick(): Boolean {
		if (cancelled) return true

		val startTime = System.nanoTime()
		val budgetNanos = (tickBudgetMs * 1_000_000).toLong()

		val min = bounds.min
		val max = bounds.max
		val mutablePos = BlockPos.MutableBlockPos()

		var lastChunkX = Int.MIN_VALUE
		var lastChunkZ = Int.MIN_VALUE
		var lastChunkLoaded = false
		var sinceLastBudgetCheck = 0

		while (currY <= max.y) {
			while (currZ <= max.z) {
				while (currX <= max.x) {
					// System.nanoTime() itself isn't free - polling it every single position (the
					// overwhelming majority of which, post block-entity filter, cost barely anything
					// to actually process) spent a real share of the budget on the clock call alone.
					// Checking every BUDGET_CHECK_INTERVAL positions instead trades a slightly late
					// yield (at most that many positions' worth) for a lot more real progress per tick.
					sinceLastBudgetCheck++
					if (sinceLastBudgetCheck >= BUDGET_CHECK_INTERVAL) {
						sinceLastBudgetCheck = 0
						if (System.nanoTime() - startTime >= budgetNanos) {
							return false
						}
					}

					val chunkX = currX shr 4
					val chunkZ = currZ shr 4

					if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
						lastChunkX = chunkX
						lastChunkZ = chunkZ
						lastChunkLoaded = level.hasChunk(chunkX, chunkZ)
					}

					if (lastChunkLoaded) {
						mutablePos.set(currX, currY, currZ)
						val state = level.getBlockState(mutablePos)

						if (!state.isAir && state.hasBlockEntity()) {
							val immutablePos = mutablePos.immutable()
							if (index.scanPosition(level, immutablePos, pendingLocations)) {
								discoveredContainers.add(immutablePos)
							}
						}
					}

					currX++
				}
				currX = min.x
				currZ++
			}
			currZ = min.z
			currY++
		}

		index.replaceAll(level, pendingLocations, discoveredContainers)
		return true
	}

	override fun cancel() {
		cancelled = true
	}
}
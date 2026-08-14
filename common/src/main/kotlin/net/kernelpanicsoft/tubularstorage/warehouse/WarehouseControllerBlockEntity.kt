package net.kernelpanicsoft.tubularstorage.warehouse

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * A warehouse's single binding point - holds the [Bounds] volume a [WarehouseWandItem] defines for
 * it (see `docs/design/m3-warehouse-storage.md`) and the [WarehouseIndex] built over that volume,
 * and, from later M3 phases, the job queue against that index.
 */
class WarehouseControllerBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.WarehouseController, pos, state) {

	@Sync
	private var boundsSlot: BoundsSlot by field(BoundsSlot.serializer()) { BoundsSlot() }

	/** The bound warehouse volume, or `null` until a [WarehouseWandItem] binds one. */
	var bounds: Bounds?
		get() = boundsSlot.bounds
		set(value) {
			boundsSlot = BoundsSlot(value)
		}

	val index: WarehouseIndex = WarehouseIndex()

	private var lastScannedBounds: Bounds? = null
	private var ticksSinceAudit: Int = 0

	fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel

		if (index.isRescanning) {
			index.tick(serverLevel)
			return
		}

		val currentBounds = bounds
		if (currentBounds != lastScannedBounds) {
			lastScannedBounds = currentBounds
			ticksSinceAudit = 0
			if (currentBounds != null) index.scheduleRescan(currentBounds) else index.clear()
			return
		}

		if (currentBounds == null) return
		ticksSinceAudit++
		if (ticksSinceAudit < AUDIT_INTERVAL_TICKS) return
		ticksSinceAudit = 0
		index.scheduleRescan(currentBounds)
	}

	companion object {
		/** How often the background audit rescan runs to correct drift from racks touched by hand - 5 minutes at 20 TPS. */
		private const val AUDIT_INTERVAL_TICKS = 6000

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: WarehouseControllerBlockEntity) = tile.tick(level, pos, state)
	}
}

/**
 * Wraps [Bounds] so [WarehouseControllerBlockEntity.bounds] can round-trip as `null` while unbound
 * - a bare nullable [net.kernelpanicsoft.archie.serialization.NBTHolder.field] encodes rootless
 * (outside any structure), and knbt can't represent a bare `null` there; see
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookState]'s `ColorSlot` for the same
 * workaround - and, like that one, this must stay a plain data class, not a `@JvmInline value
 * class`, or the same encoding failure comes back.
 */
@Serializable
private data class BoundsSlot(val bounds: Bounds? = null)

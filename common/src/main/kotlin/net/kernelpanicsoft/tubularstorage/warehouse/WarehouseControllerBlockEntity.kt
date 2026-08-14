package net.kernelpanicsoft.tubularstorage.warehouse

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.tubularstorage.network.GantrySyncPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * A warehouse's single binding point - holds the [Bounds] volume a [WarehouseWandItem] defines for
 * it (see `docs/design/m3-warehouse-storage.md`), the [WarehouseIndex] built over that volume, and
 * the [GantryState] crane head that moves within it, and, from later M3 phases, the job queue
 * driving that gantry against the index.
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
	val gantry: GantryState = GantryState(Vec3.atCenterOf(pos))

	private var lastScannedBounds: Bounds? = null
	private var ticksSinceAudit: Int = 0
	private var ticksSinceGantrySync: Int = 0

	/** Queues [gantry] motion to [target] via the current [bounds]' rail height - a no-op while unbound. */
	fun moveGantryTo(target: BlockPos) {
		val railY = bounds?.max?.y ?: return
		gantry.moveTo(target, railY)
	}

	fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		tickIndex(serverLevel)
		tickGantry(serverLevel, pos)
	}

	private fun tickIndex(level: ServerLevel) {
		if (index.isRescanning) {
			index.tick(level)
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

	private fun tickGantry(level: ServerLevel, pos: BlockPos) {
		if (!gantry.isMoving) return
		gantry.tick()

		ticksSinceGantrySync++
		if (ticksSinceGantrySync < GANTRY_SYNC_INTERVAL_TICKS) return
		ticksSinceGantrySync = 0
		TubularStorageNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, GANTRY_SYNC_RADIUS,
			GantrySyncPacket(pos, gantry.pos, gantry.remainingPath),
		)
	}

	companion object {
		/** How often the background audit rescan runs to correct drift from racks touched by hand - 5 minutes at 20 TPS. */
		private const val AUDIT_INTERVAL_TICKS = 6000

		private const val GANTRY_SYNC_INTERVAL_TICKS = 4
		private const val GANTRY_SYNC_RADIUS = 64.0

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

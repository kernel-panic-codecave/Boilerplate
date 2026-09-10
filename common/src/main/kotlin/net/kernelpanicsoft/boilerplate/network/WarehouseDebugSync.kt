package net.kernelpanicsoft.boilerplate.network

import net.kernelpanicsoft.boilerplate.debug.DebugFlag
import net.kernelpanicsoft.boilerplate.debug.DebugOverlayViewers
import net.kernelpanicsoft.boilerplate.network.WarehouseDebugSnapshotPacket.JobKind
import net.kernelpanicsoft.boilerplate.network.WarehouseDebugSnapshotPacket.JobStage
import net.kernelpanicsoft.boilerplate.warehouse.GantryJob
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseBlockEventListener
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Drains every loaded warehouse controller's bound volume, rack index and crane queues into a
 * [WarehouseDebugSnapshotPacket] a few ticks apart, broadcasting it to every player in the level.
 * Wired up from `Boilerplate.init`'s `SERVER_LEVEL_POST` listener and gated on
 * [DebugOverlayViewers], so on servers with nobody viewing the overlay this is a single boolean
 * read per tick - the same shape (and the same toggle) as [DebugNetworkSync].
 *
 * Controllers are found through [WarehouseBlockEventListener.activeControllers] rather than by
 * walking the level's block entities: that set is already maintained for exactly this "every live
 * controller" question, and a warehouse bound over a million blocks is not something to go looking
 * for by scanning.
 */
object WarehouseDebugSync {
	private const val SEND_INTERVAL_TICKS = 10

	/**
	 * Per-warehouse caps on what one snapshot carries. A mature warehouse can index thousands of
	 * racks, and the overlay stops being readable long before a packet that size stops being
	 * sensible to broadcast twice a second - [WarehouseDebugSnapshotPacket.SWarehouse.racksTruncated]
	 * tells the client a cap actually bit so it can say so.
	 */
	private const val MAX_RACKS = 2048
	private const val MAX_JOBS = 256

	fun tickLevel(level: ServerLevel) {
		if (!DebugOverlayViewers.enabled(DebugFlag.WAREHOUSE)) return
		if (level.players().isEmpty()) return
		if (level.gameTime % SEND_INTERVAL_TICKS != 0L) return
		push(level)
	}

	/** Immediately broadcasts one snapshot, so a player toggling the overlay on doesn't wait a full send interval for the first frame. */
	fun pushNow(level: ServerLevel) {
		if (!DebugOverlayViewers.enabled(DebugFlag.WAREHOUSE)) return
		if (level.players().isEmpty()) return
		push(level)
	}

	private fun push(level: ServerLevel) {
		val warehouses = ArrayList<WarehouseDebugSnapshotPacket.SWarehouse>()
		// Copied before iterating: a controller loading or unloading mid-pass would otherwise
		// concurrently modify the set this is walking.
		for (controllerPos in WarehouseBlockEventListener.activeControllers.toList()) {
			if (!level.hasChunk(controllerPos.x shr 4, controllerPos.z shr 4)) continue
			val controller = level.getBlockEntity(controllerPos) as? WarehouseControllerBlockEntity ?: continue
			warehouses += snapshot(controller) ?: continue
		}
		if (warehouses.isEmpty()) return

		BoilerplateNetworkChannel.toPlayersInDimension(level, WarehouseDebugSnapshotPacket(warehouses))
	}

	/** One controller's snapshot, or `null` for an unbound one - there is nothing to draw for a controller with no volume. */
	private fun snapshot(controller: WarehouseControllerBlockEntity): WarehouseDebugSnapshotPacket.SWarehouse? {
		val bounds = controller.bounds ?: return null
		val racks = racksOf(controller)
		val jobs = jobsOf(controller)

		return WarehouseDebugSnapshotPacket.SWarehouse(
			controller = controller.blockPos,
			min = bounds.min,
			max = bounds.max,
			visualState = controller.visualState,
			scale = controller.scale.name,
			rescanning = controller.index.isRescanning,
			headPos = controller.gantry.pos,
			path = controller.gantry.remainingPath,
			racks = racks.take(MAX_RACKS),
			jobs = jobs.take(MAX_JOBS),
			racksTruncated = racks.size > MAX_RACKS,
			jobsTruncated = jobs.size > MAX_JOBS,
		)
	}

	/**
	 * Every position the index knows about, folded into one entry each. Built from
	 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex.knownContainers] rather than from
	 * `locations` alone, so a rack the index found but that currently holds nothing still shows up -
	 * an empty rack the crane can stow into is exactly as interesting as a full one, and a rack
	 * that is neither known-empty nor available is the shape a "why is put-away ignoring this
	 * chest" bug actually takes.
	 */
	private fun racksOf(controller: WarehouseControllerBlockEntity): List<WarehouseDebugSnapshotPacket.SRack> {
		val index = controller.index
		val stored = HashMap<BlockPos, LongArray>()
		for ((_, refs) in index.locations) {
			for (ref in refs) {
				val entry = stored.getOrPut(ref.pos) { longArrayOf(0L, 0L) }
				entry[0] += ref.amount
				entry[1]++
			}
		}
		val available = index.availableSlots.mapTo(HashSet()) { (pos, _) -> pos }

		return index.knownContainers.map { pos ->
			val entry = stored[pos]
			WarehouseDebugSnapshotPacket.SRack(
				pos = pos,
				stored = entry?.get(0) ?: 0L,
				distinctResources = (entry?.get(1) ?: 0L).toInt(),
				available = pos in available,
			)
		}
	}

	/** Every queued and in-flight crane hop, most-imminent first, each resolved to the same source/destination the controller itself would move to. */
	private fun jobsOf(controller: WarehouseControllerBlockEntity): List<WarehouseDebugSnapshotPacket.SJob> {
		val pos = controller.blockPos
		val jobs = ArrayList<WarehouseDebugSnapshotPacket.SJob>()
		fun collect(source: List<GantryJob>, stage: JobStage) {
			for (job in source) {
				jobs += WarehouseDebugSnapshotPacket.SJob(
					from = controller.sourcePos(job, pos),
					to = controller.destinationPos(job, pos),
					kind = kindOf(job).ordinal,
					stage = stage.ordinal,
				)
			}
		}
		collect(controller.carriedJobs, JobStage.CARRIED)
		collect(controller.fetchingJobs, JobStage.FETCHING)
		collect(controller.pendingJobs, JobStage.PENDING)
		collect(controller.defragBacklog, JobStage.DEFRAG)
		return jobs
	}

	private fun kindOf(job: GantryJob): JobKind = when (job) {
		is GantryJob.Retrieve -> JobKind.RETRIEVE
		is GantryJob.Stow -> JobKind.STOW
		is GantryJob.Move -> JobKind.MOVE
	}
}

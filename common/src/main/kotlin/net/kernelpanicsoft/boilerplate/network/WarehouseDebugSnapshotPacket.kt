package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.archie.serialization.serializers.SVec3
import net.kernelpanicsoft.boilerplate.warehouse.GantryVisualState
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseDebugCache

/**
 * Server -> client sync of everything the warehouse debug overlay draws that a client cannot see
 * for itself (see [net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseDebugRenderer]): each
 * bound warehouse's volume, the racks its index actually found and what they hold, the crane's own
 * queued/in-flight work, and where the head currently is.
 *
 * The gantry's *position* is deliberately not what this is for - the head moves every tick and
 * [net.kernelpanicsoft.boilerplate.warehouse.GantryClientCache] already dead-reckons it smoothly
 * from [GantrySyncPacket]. [SWarehouse.headPos]/[SWarehouse.path] are only the fallback for a
 * controller that has never sent one of those (nothing has moved since it loaded), the same
 * fallback [net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerBlockEntityRenderer]
 * already makes.
 *
 * Broadcast a few ticks apart rather than per-tick, and only while somebody has the overlay on
 * (see [net.kernelpanicsoft.boilerplate.debug.DebugOverlayViewers]) - the overlay is diagnostic,
 * missing a frame costs nothing, and a warehouse index can run to thousands of racks.
 */
@Serializable
data class WarehouseDebugSnapshotPacket(val warehouses: List<SWarehouse> = emptyList()) {
	fun handleOnClient() {
		WarehouseDebugCache.update(this)
	}

	/**
	 * One bound warehouse controller. [racks] and [jobs] are capped by the sender (see
	 * [WarehouseDebugSync]) with [racksTruncated]/[jobsTruncated] recording that a cap actually
	 * bit, so the overlay can say so rather than silently drawing a partial warehouse as if it
	 * were the whole one.
	 */
	@Serializable
	data class SWarehouse(
		val controller: SBlockPos,
		val min: SBlockPos,
		val max: SBlockPos,
		val visualState: GantryVisualState,
		val scale: String,
		val rescanning: Boolean,
		val headPos: SVec3,
		val path: List<SVec3> = emptyList(),
		val racks: List<SRack> = emptyList(),
		val jobs: List<SJob> = emptyList(),
		val racksTruncated: Boolean = false,
		val jobsTruncated: Boolean = false,
	)

	/**
	 * One indexed rack. [stored] is everything [net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex.locations]
	 * attributes to this position across [distinctResources] different resources; a position the
	 * index knows about but that currently holds nothing reports zero for both. [available] mirrors
	 * membership of [net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex.availableSlots] - the
	 * proximity-sorted list put-away actually searches, which is not the same thing as "known", and
	 * telling the two apart is most of the point of drawing racks at all.
	 */
	@Serializable
	data class SRack(val pos: SBlockPos, val stored: Long, val distinctResources: Int, val available: Boolean)

	/** One piece of crane work as a source -> destination hop, [kind] being a [JobKind] ordinal and [stage] a [JobStage] ordinal. */
	@Serializable
	data class SJob(val from: SBlockPos, val to: SBlockPos, val kind: Int, val stage: Int)

	/** What a crane hop is moving, which is what the overlay colors a job line by. Lives on the packet rather than on [WarehouseDebugSync] because the ordinal is the wire encoding, which both ends need. */
	enum class JobKind {
		/** [net.kernelpanicsoft.boilerplate.warehouse.GantryJob.Retrieve] - rack to the controller's outbound buffer. */
		RETRIEVE,

		/** [net.kernelpanicsoft.boilerplate.warehouse.GantryJob.Stow] - the controller's staging buffer to a rack. */
		STOW,

		/** [net.kernelpanicsoft.boilerplate.warehouse.GantryJob.Move] - rack straight to rack, [net.kernelpanicsoft.boilerplate.warehouse.WarehouseDefragPlanner]'s own job kind. */
		MOVE,
	}

	/** How far along a job is, which is what the overlay varies a job line's opacity by. */
	enum class JobStage {
		/** Queued on the controller, not yet promoted into a batch. */
		PENDING,

		/** In the current batch, being fetched - the crane is on its way to the source. */
		FETCHING,

		/** Picked up and in hand, awaiting drop-off. */
		CARRIED,

		/** Still in the defrag backlog, behind everything else. */
		DEFRAG,
	}
}

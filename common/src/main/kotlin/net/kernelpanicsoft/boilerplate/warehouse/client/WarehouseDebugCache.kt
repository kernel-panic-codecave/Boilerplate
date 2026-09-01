package net.kernelpanicsoft.boilerplate.warehouse.client

import net.kernelpanicsoft.boilerplate.network.WarehouseDebugSnapshotPacket
import net.kernelpanicsoft.boilerplate.warehouse.GantryVisualState
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Client-side mirror of the server's [WarehouseDebugSnapshotPacket], keyed to the client level it
 * was received for so a dimension switch (or world load) never draws the previous world's
 * warehouses over the new one. Updated from the network thread, read from the render thread, so all
 * state is [Volatile] and entirely copy-on-write - the renderer keys its baked geometry off the
 * list's identity, which a fresh snapshot always replaces.
 *
 * Deliberately the same shape as [net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkCache],
 * down to [onLevel] being the reset: both mirror a periodic debug broadcast, and there is no reason
 * for the two to have different lifecycles.
 */
object WarehouseDebugCache {
	/** One warehouse as the renderer wants it - decoded once here rather than re-derived per frame. */
	data class Warehouse(
		val controller: BlockPos,
		val min: BlockPos,
		val max: BlockPos,
		val visualState: GantryVisualState,
		val scale: String,
		val rescanning: Boolean,
		val headPos: Vec3,
		val path: List<Vec3>,
		val racks: List<Rack>,
		val jobs: List<Job>,
		val racksTruncated: Boolean,
		val jobsTruncated: Boolean,
	)

	data class Rack(val pos: BlockPos, val stored: Long, val distinctResources: Int, val available: Boolean)

	data class Job(val from: BlockPos, val to: BlockPos, val kind: Int, val stage: Int)

	@Volatile
	private var warehouses: List<Warehouse> = emptyList()

	@Volatile
	private var level: ClientLevel? = null

	fun update(packet: WarehouseDebugSnapshotPacket) {
		warehouses = packet.warehouses.map { warehouse ->
			Warehouse(
				controller = warehouse.controller,
				min = warehouse.min,
				max = warehouse.max,
				visualState = warehouse.visualState,
				scale = warehouse.scale,
				rescanning = warehouse.rescanning,
				headPos = warehouse.headPos,
				path = warehouse.path,
				racks = warehouse.racks.map { Rack(it.pos, it.stored, it.distinctResources, it.available) },
				jobs = warehouse.jobs.map { Job(it.from, it.to, it.kind, it.stage) },
				racksTruncated = warehouse.racksTruncated,
				jobsTruncated = warehouse.jobsTruncated,
			)
		}
	}

	fun clear() {
		warehouses = emptyList()
	}

	/** Drops the cached snapshot whenever the client's current level changes - the first call for a fresh level is the reset. */
	fun onLevel(level: ClientLevel?) {
		if (level !== this.level) {
			this.level = level
			if (level != null) clear()
		}
	}

	fun snapshot(): List<Warehouse> = warehouses
}

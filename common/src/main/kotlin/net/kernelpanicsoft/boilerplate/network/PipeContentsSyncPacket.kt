package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache

/**
 * Server -> client sync of one pipe's [TravelingItem]s, broadcast periodically rather than every
 * tick.
 *
 * [speedMultiplier] is that segment's own current pressure-derived speed scaling (see
 * [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.speedMultiplier]) - carried so the
 * client's dead reckoning advances at the same rate the server actually is. Without it a
 * well-pressurised pipe visibly drifts: the client interpolates at the `1.0`x baseline, falls
 * further behind for the whole gap between syncs, then snaps forward when the next one lands.
 */
@Serializable
data class PipeContentsSyncPacket(val pos: SBlockPos, val items: List<TravelingItem>, val speedMultiplier: Float = 1f) {
	fun handleOnClient() {
		PipeContentsClientCache.update(pos, items, speedMultiplier)
	}
}

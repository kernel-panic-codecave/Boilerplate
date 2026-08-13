package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.client.PipeContentsClientCache

/** Server -> client sync of one pipe's [TravelingItem]s, broadcast periodically rather than every tick. */
@Serializable
data class PipeContentsSyncPacket(val pos: SBlockPos, val items: List<TravelingItem>) {
	fun handleOnClient() {
		PipeContentsClientCache.update(pos, items)
	}
}

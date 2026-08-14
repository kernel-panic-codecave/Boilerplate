package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.archie.serialization.serializers.SVec3
import net.kernelpanicsoft.tubularstorage.warehouse.GantryClientCache

/** Server -> client sync of one warehouse controller's gantry position/path, broadcast periodically rather than every tick. */
@Serializable
data class GantrySyncPacket(val controllerPos: SBlockPos, val gantryPos: SVec3, val path: List<SVec3>) {
	fun handleOnClient() {
		GantryClientCache.update(controllerPos, gantryPos, path)
	}
}

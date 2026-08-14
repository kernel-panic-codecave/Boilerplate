package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.archie.serialization.serializers.SVec3
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.GantryClientCache

/**
 * Server -> client sync of one warehouse controller's gantry position/path, broadcast periodically
 * rather than every tick. Carries the full bound volume ([bounds]) alongside the path rather than
 * leaving the client to infer it from the current position alone - the renderer needs both the
 * fixed rail height (for a drop rod that actually grows as the head descends) and the footprint's
 * full extent (to draw crossbeams spanning the whole rail envelope, not just the segment the head
 * happens to be crossing).
 */
@Serializable
data class GantrySyncPacket(val controllerPos: SBlockPos, val gantryPos: SVec3, val path: List<SVec3>, val bounds: Bounds) {
	fun handleOnClient() {
		GantryClientCache.update(controllerPos, gantryPos, path, bounds)
	}
}

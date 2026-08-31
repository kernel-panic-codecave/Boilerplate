package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.archie.serialization.serializers.SVec3
import net.kernelpanicsoft.boilerplate.warehouse.GantryClientCache

/**
 * Server -> client sync of one warehouse controller's gantry position/path and currently-carried
 * items, broadcast periodically (and only while actually moving) rather than every tick. Doesn't
 * carry the bound volume itself -
 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity.bounds] is already
 * `@Sync`'d independently, so [WarehouseControllerBlockEntityRenderer][net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerBlockEntityRenderer]
 * reads it straight off the block entity instead.
 *
 * [speedPerTick] is the server's own *effective* gantry speed at sampling time - the
 * [WarehouseScale] tier's base rate scaled by the controller's pressure-derived multiplier
 * ([WarehouseControllerBlockEntity.effectiveGantrySpeed]), which the client would otherwise have to
 * guess. Same principle as [PipeContentsSyncPacket.speedMultiplier]: dead reckoning can't track the
 * real gantry at the wrong rate, and a pressure-fed controller zips along at multiples of the base
 * while a client replaying the base rate falls behind and visibly snaps forward on every re-anchor.
 *
 * [serverTick] is the sender's server-world game time when [gantryPos]/[path] were sampled - the
 * client's dead reckoning (see [net.kernelpanicsoft.boilerplate.warehouse.GantryClientCache]) runs
 * from this tick rather than from each packet's receipt time, so a render-frame hiccup can't carry
 * the head ahead of the server and back-snap it at the next sync.
 */
@Serializable
data class GantrySyncPacket(
	val controllerPos: SBlockPos,
	val gantryPos: SVec3,
	val path: List<SVec3>,
	val carriedItems: List<SResourceStack<SItemResource>> = emptyList(),
	val speedPerTick: Double = 1.0,
	val serverTick: Long = 0L,
) {
	fun handleOnClient() {
		GantryClientCache.update(controllerPos, gantryPos, path, carriedItems, speedPerTick, serverTick)
	}
}

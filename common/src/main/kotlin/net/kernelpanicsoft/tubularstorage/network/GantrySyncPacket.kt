package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.archie.serialization.serializers.SVec3
import net.kernelpanicsoft.tubularstorage.warehouse.GantryClientCache

/**
 * Server -> client sync of one warehouse controller's gantry position/path and currently-carried
 * items, broadcast periodically (and only while actually moving) rather than every tick. Doesn't
 * carry the bound volume itself -
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity.bounds] is already
 * `@Sync`'d independently, so [WarehouseControllerBlockEntityRenderer][net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer]
 * reads it straight off the block entity instead.
 */
@Serializable
data class GantrySyncPacket(
	val controllerPos: SBlockPos,
	val gantryPos: SVec3,
	val path: List<SVec3>,
	val carriedItems: List<SResourceStack<SItemResource>> = emptyList(),
) {
	fun handleOnClient() {
		GantryClientCache.update(controllerPos, gantryPos, path, carriedItems)
	}
}

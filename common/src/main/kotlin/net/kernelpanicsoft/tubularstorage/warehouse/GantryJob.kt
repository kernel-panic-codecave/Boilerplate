package net.kernelpanicsoft.tubularstorage.warehouse

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * One physical crane task, queued on [WarehouseControllerBlockEntity] and executed as two travel
 * legs - to the source, then to the destination - rather than resolved instantly, since the
 * physical travel time is the point (see `docs/design/m3-warehouse-storage.md`).
 */
sealed interface GantryJob {
	val resource: ItemResource
	val amount: Long

	/** Move [resource]/[amount] from [slot]'s rack into the controller's own staging buffer. */
	data class Retrieve(val slot: WarehouseIndex.RackSlotRef, override val resource: ItemResource, override val amount: Long) : GantryJob

	/** Move [resource]/[amount] from the controller's own staging buffer into the rack at [targetPos]/[targetDirection], resolved once up front rather than re-planned on arrival. */
	data class Stow(
		val targetPos: BlockPos,
		val targetDirection: Direction?,
		override val resource: ItemResource,
		override val amount: Long,
	) : GantryJob
}

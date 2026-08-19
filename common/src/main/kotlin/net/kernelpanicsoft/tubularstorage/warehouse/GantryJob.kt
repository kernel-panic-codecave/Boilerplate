package net.kernelpanicsoft.tubularstorage.warehouse

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * One physical crane task, queued on [WarehouseControllerBlockEntity] and executed as two travel
 * legs - to the source, then to the destination - rather than resolved instantly, since the
 * physical travel time is the point (see `docs/design/m3-warehouse-storage.md`).
 */
sealed interface GantryJob {
	val stack: ResourceStack<ItemResource>

	/**
	 * Move [resource]/[amount] from [slot]'s rack into the controller's own outbound buffer. If
	 * [deliverTo] is set (a request being fulfilled, see `RequestFulfillment`, or a warehouse
	 * terminal withdrawal), the controller ships it back out via a connected pipe (see
	 * [DeliveryTarget]) once it lands in the buffer, rather than leaving it there indefinitely with
	 * nothing else to drain it.
	 */
	data class Retrieve(
		val slot: WarehouseIndex.RackSlotRef,
		override val stack: ResourceStack<ItemResource>,
		val deliverTo: DeliveryTarget? = null,
	) : GantryJob

	/** Move [resource]/[amount] from the controller's own staging buffer into the rack at [targetPos]/[targetDirection], resolved once up front rather than re-planned on arrival. */
	data class Stow(
		val sourceSlot: Int,
		val targetPos: BlockPos,
		val targetDirection: Direction?,
		override val stack: ResourceStack<ItemResource>
	) : GantryJob

	/**
	 * Move [resource]/[amount] directly from [slot]'s rack into the rack at [targetPos]/[targetDirection]
	 * - no buffer bounce, unlike [Retrieve]+[Stow] - see [WarehouseDefragPlanner], the only planner
	 * that currently emits this job kind. A destination that's lost its room by the time this
	 * actually executes (queued well ahead of when the gantry gets to it) falls back to landing in
	 * the controller's own `inboundBuffer` the same way a stale [Stow] target already does, rather
	 * than the item vanishing.
	 */
	data class Move(
		val slot: WarehouseIndex.RackSlotRef,
		val targetPos: BlockPos,
		val targetDirection: Direction?,
		override val stack: ResourceStack<ItemResource>,
	) : GantryJob
}

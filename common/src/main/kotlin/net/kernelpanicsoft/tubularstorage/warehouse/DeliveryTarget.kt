package net.kernelpanicsoft.tubularstorage.warehouse

import net.minecraft.core.BlockPos

/**
 * Where a [GantryJob.Retrieve]'s cargo goes once it lands in
 * [WarehouseControllerBlockEntity.outboundBuffer] - see [WarehouseControllerBlockEntity.dropOff].
 * Purely in-memory (like the job queue itself - see [WarehouseControllerBlockEntity]'s own KDoc),
 * never serialized.
 */
sealed interface DeliveryTarget {
	/** Ship via a connected pipe to a specific known position - targeted routing ([net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter.findRouteTo]). Both a [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment] request and a warehouse terminal withdrawal use this - both already know exactly where the item is going, an inventory directly connected to the requesting/withdrawing pipe, not a dumb push that could land anywhere (including straight back into the warehouse itself). */
	data class Pipe(val pos: BlockPos) : DeliveryTarget
}

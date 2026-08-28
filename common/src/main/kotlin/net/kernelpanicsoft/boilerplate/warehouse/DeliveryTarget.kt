package net.kernelpanicsoft.boilerplate.warehouse

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Where a [GantryJob.Retrieve]'s cargo goes once it lands in
 * [WarehouseControllerBlockEntity.outboundBuffer] - see [WarehouseControllerBlockEntity.dropOff].
 * Purely in-memory (like the job queue itself - see [WarehouseControllerBlockEntity]'s own KDoc),
 * never serialized.
 */
sealed interface DeliveryTarget {
	/**
	 * Ship via a connected pipe to a specific known position - targeted routing ([net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.findRouteTo]).
	 * Both a [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment] request and a
	 * warehouse terminal withdrawal use this - both already know exactly where the item is going,
	 * an inventory directly connected to the requesting/withdrawing pipe, not a dumb push that
	 * could land anywhere (including straight back into the warehouse itself).
	 *
	 * [face], when the caller already knows exactly which face of [pos] it means, disambiguates a
	 * [pos] that carries more than one same-type hook (two
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType]s on different faces of
	 * one block, say) - see [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace]'s
	 * own KDoc for why the topology-derived arrival direction can't be trusted to do that on its own.
	 */
	data class Pipe(val pos: BlockPos, val face: Direction? = null) : DeliveryTarget
}

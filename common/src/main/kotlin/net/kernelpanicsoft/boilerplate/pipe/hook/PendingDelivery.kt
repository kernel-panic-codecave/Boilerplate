package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.network.SResourceComponent

/**
 * A request/withdrawal [TerminalHookState.output] doesn't have the real item for yet - dispatched,
 * still traveling. Shown to the player as a reserved-slot placeholder in an otherwise-empty output
 * slot: a fake, non-interactable [net.minecraft.world.item.ItemStack] overlay plus a vanilla-style
 * cooldown wipe counting down [startTick]/[totalTicks] (see
 * `AbstractTerminalHookScreen`'s own rendering) - the *real* slot underneath stays genuinely empty
 * the whole time, so a recipe viewer polling it for "is this ingredient here yet" sees the honest
 * answer, not a stack that isn't really there. Clicking the placeholder cancels it -
 * [id] is what [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.reservationId] carries so
 * delivery ([net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.tick]) can tell a
 * since-cancelled reservation apart from a live one and redirect the real item back into the
 * network instead of landing here after all.
 *
 * [slot] is a genuine claim on that one [TerminalHookState.output] index, not just where the
 * placeholder happens to be drawn: the delivery lands there specifically on arrival, and
 * [ReservedSlotStorage] blocks everything else on the network from inserting there in the meantime.
 * A reservation is only ever handed out against a slot that's actually free
 * ([TerminalHookState.reserveOutputSlot]), and a request that can't get one is refused outright
 * rather than dispatched into an inbox with nowhere to put it - so [amount] is also capped at what
 * that single slot can genuinely hold.
 *
 * What's persisted is the trip's own *geometry* - [pipeHops] and [gantryBlocks], both fixed for the
 * life of the delivery - rather than a duration. [totalTicks] is derived from those against whatever
 * the pipes and gantry are managing *right now*, recomputed every time
 * `AbstractTerminalHookMenu.sendPendingDeliveries` builds a packet, so the progress bar tracks a
 * rolling estimate instead of one frozen at dispatch. Both speeds are pressure-driven and change
 * while a delivery is in flight - pressurise the network mid-trip and the bar speeds up to match,
 * rather than continuing to count down against a figure that stopped being true.
 *
 * The stored [totalTicks] is only ever the estimate as of the last recompute; nothing reads it
 * except as a starting value, and it is deliberately not kept up to date in NBT (that would mean
 * writing every terminal's hook state four times a second for a purely cosmetic number).
 */
@Serializable
data class PendingDelivery(
	val id: Long,
	val slot: Int,
	val resource: SResourceComponent,
	val amount: Long,
	val startTick: Long,
	val totalTicks: Int,
	/** Pipe segments this delivery still has to cross - fixed for the trip; the *time* that takes is not. */
	val pipeHops: Int = 0,
	/** Blocks of gantry travel this delivery needs, `0.0` for a provider pull that never involves one. */
	val gantryBlocks: Double = 0.0,
)

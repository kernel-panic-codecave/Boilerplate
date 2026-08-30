package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.network.SItemResource

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
 * [totalTicks] is a genuine, fixed estimate for a provider-hook pull (real pipe travel at a known
 * speed - see `RequestFulfillment.request`'s own KDoc), but only a rough one for a warehouse
 * retrieval, whose own gantry speed is pressure-gated and can fluctuate - the progress bar for one
 * of those is closer to "roughly how long this usually takes" than a precise countdown.
 */
@Serializable
data class PendingDelivery(
	val id: Long,
	val slot: Int,
	val resource: SItemResource,
	val amount: Long,
	val startTick: Long,
	val totalTicks: Int,
)

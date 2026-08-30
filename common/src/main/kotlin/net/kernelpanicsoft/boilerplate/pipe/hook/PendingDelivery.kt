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
 * [totalTicks] is a genuine, fixed estimate for a provider-hook pull (real pipe travel at a known
 * speed - see `RequestFulfillment.request`'s own KDoc), but only a rough one for a warehouse
 * retrieval, whose own gantry speed is pressure-gated and can fluctuate - the progress bar for one
 * of those is closer to "roughly how long this usually takes" than a precise countdown.
 */
@Serializable
data class PendingDelivery(
	val id: Long,
	val resource: SItemResource,
	val amount: Long,
	val startTick: Long,
	val totalTicks: Int,
)

package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.kernelpanicsoft.boilerplate.util.SDirection

/**
 * Something the *other* side of a boundary has sent here to be passed on - the outstanding half of a
 * two-leg delivery going **inward**, and the exact mirror of [RelayClaim].
 *
 * The two directions are asymmetric in one way only: where a claim goes. A pull is asked for by the
 * near hook, so the claim lives there ([RelayClaim] on the hook that asked); a push is *addressed*
 * by the near side but carried out on the far one, so the claim lives on the interface, which is
 * where the second leg begins. Both cases put it where leg 2 runs.
 *
 * Leg 1 is an ordinary delivery on the sending network, landing in [InterfaceHookState.stock]; leg 2
 * is [InterfaceHookType]'s own targeted push out of that stock toward [deliverTo], down the far
 * network's own pipes. Nothing teleports: a resource travels the full length of both.
 *
 * Without the claim, an arriving resource is indistinguishable from stock somebody dropped in - and
 * [InterfaceHookType.drainExcess] would push it to whatever destination the far router liked best,
 * which is rarely the one the sender meant. That is the whole reason this exists rather than the
 * inward direction riding on the excess drain alone.
 *
 * @property resource what is coming.
 * @property amount how much is still to be passed on.
 * @property deliverTo where it goes on the far network - the destination the sender named.
 * @property deliverFace which face of [deliverTo], where the sender named one.
 * @property expiresAtTick the game tick after which this claim is abandoned and whatever arrived
 *   against it becomes ordinary stock, drained like any other excess.
 */
@Serializable
data class InboundClaim(
	val resource: SResourceComponent,
	val amount: Long,
	val deliverTo: SBlockPos,
	val deliverFace: SDirection? = null,
	val expiresAtTick: Long,
)

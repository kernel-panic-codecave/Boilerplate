package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.kernelpanicsoft.boilerplate.util.SDirection

/**
 * One request this side of a subnet boundary has asked the *other* side to fill - the outstanding
 * half of a two-leg delivery.
 *
 * A request that crosses a boundary cannot be answered in one step. The far network extracts from
 * whatever holds the resource and delivers it, through its own pipes, into the interface's stock
 * (leg 1); only once it has physically arrived there can this side pull it across and send it on to
 * whoever asked (leg 2 - see [ProviderHookType.tick]). Nothing teleports: the resource travels the
 * full length of both networks.
 *
 * **A crossing carries no reservation.** A reservation is minted for a trip that is about to happen
 * and cleared when it lands; a crossing is asked for now and completes minutes later, across two
 * networks and possibly a reload, by which time the reservation it was promised may be cancelled,
 * broken, or simply never have been recorded. Delivering against one nobody owns reads as
 * *cancelled* on arrival and jams the item where it lands - which is exactly how this failed twice
 * before the reservation was taken out of it. What crosses is an ordinary unreserved delivery, and
 * the destination takes it the way it takes anything else pushed at it.
 *
 * The claim is what makes that safe to leave in flight. Without one, the near side would re-ask the
 * far side on every cycle for something already on its way and flood the interface with duplicates;
 * with one, a boundary crossing is asked for once and then waited on. [expiresAtTick] is the
 * backstop for the delivery that never arrives - a jam, a broken pipe, an interface mined mid-trip -
 * after which the claim lapses and the request may be made afresh rather than being outstanding
 * forever.
 *
 * @property resource what was asked for.
 * @property amount how much is still to be carried across; a partial arrival leaves the rest claimed.
 * @property settling how much has been sent on from the boundary and is still in flight this side.
 * @property deliverTo where it goes once it is across - the original requester's own destination.
 * @property deliverFace which face of [deliverTo], when the caller named one.
 * @property expiresAtTick the game tick after which this claim is abandoned - the arrival deadline
 *   while it is still outstanding, and the settle deadline once it is all [settling].
 */
@Serializable
data class RelayClaim(
	val resource: SResourceComponent,
	val amount: Long,
	/**
	 * How much of this claim is already on its way to [deliverTo] but has not landed yet.
	 *
	 * Still spoken for, and still counted as outstanding. A claim discharged the instant leg 2 is
	 * *dispatched* frees the near side to ask the far side for the same thing again while the first
	 * lot is still travelling this network - and the requester, reading a destination the items have
	 * not reached, duly asks. That double-delivers, which is exactly what it did before this existed.
	 */
	val settling: Long = 0,
	val deliverTo: SBlockPos,
	val deliverFace: SDirection? = null,
	val expiresAtTick: Long,
)

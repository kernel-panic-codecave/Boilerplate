package net.kernelpanicsoft.boilerplate.pipe.entity

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.resource.SResourceStack
import net.kernelpanicsoft.boilerplate.util.SDirection
import net.kernelpanicsoft.boilerplate.util.SDyeColor

/**
 * A resource stack in flight through a pipe network - items and fluids alike.
 *
 * [color] is the consignment color set by whichever extractor/request initiated the trip;
 * sorting pipes route on it, plain pipes ignore it. See `docs/design/m2-sorting-routing.md`.
 *
 * [targetFace] is the specific face of the *destination* block this delivery is meant for, when
 * the caller already knows exactly which one - a
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity] can carry up to six independent
 * hooks, and the direction [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.tick]'s
 * own final hop would otherwise compute reflects whichever neighboring pipe segment the item
 * happens to arrive from - pipe network topology, not which face the caller actually meant - so
 * two same-type hooks (two [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType]s,
 * say) on different faces of one block are otherwise indistinguishable at arrival time. `null` for
 * a delivery with no specific face in mind (a plain push via
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.findRoute], or a target that was
 * never ambiguous - a single rack, a chest), which keeps today's "whichever face the topology
 * happens to land on" resolution exactly as it was.
 *
 * [reservationId], when non-null, ties this delivery to a specific
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery] the destination terminal is
 * showing a reserved-slot placeholder for (see that class's own KDoc) - checked on arrival
 * ([net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.tick]) so a since-cancelled
 * reservation redirects the real item back into the network instead of landing in the terminal
 * after all. `null` for a delivery with no reservation tracking it (a plain extractor push, say).
 */
@Serializable
data class TravelingItem(
	val stack: SResourceStack<*>,
	val fromDirection: SDirection,
	var progress: Float = 0f,
	var path: List<SBlockPos> = emptyList(),
	val color: SDyeColor? = null,
	val targetFace: SDirection? = null,
	val reservationId: Long? = null,
) {
	/**
	 * This delivery as the client needs to see it - the same item with [path] cut to
	 * [CLIENT_PATH_LOOKAHEAD] hops.
	 *
	 * The route is by far the largest thing on the wire and the only part that grows without bound:
	 * a delivery crossing a base carries a hop for every segment it has left, and every one of them
	 * was being re-sent for every item in every pipe within
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.SYNC_RADIUS], up to twice a tick
	 * each. The renderer never looks past the second hop, so the rest is bytes to encode, ship and
	 * decode for nothing.
	 */
	fun forClient(): TravelingItem =
		if (path.size <= CLIENT_PATH_LOOKAHEAD) this else copy(path = path.take(CLIENT_PATH_LOOKAHEAD))

	companion object {
		/**
		 * How many hops of [path] a client is sent - the exact depth its own reads reach.
		 *
		 * [net.kernelpanicsoft.boilerplate.pipe.client.TravelingItemInstances] reads `path[0]` to
		 * aim the leg it is drawing, and
		 * [net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache] hands an item to the
		 * next segment by dropping one hop - so that copy's own `path[0]` is `path[1]`, and its
		 * "am I on my final leg" test (`size <= 1`) is this item's `size <= 2`. Three hops answers
		 * every one of those identically to the whole route: any path of three or more reads as
		 * "more than one leg left" at both levels, which is the only distinction either makes.
		 *
		 * Change this and [net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache]'s
		 * own duplicate check has to move with it - it compares routes seen from two sides that are
		 * truncated at different points along them.
		 */
		const val CLIENT_PATH_LOOKAHEAD = 3
	}
}

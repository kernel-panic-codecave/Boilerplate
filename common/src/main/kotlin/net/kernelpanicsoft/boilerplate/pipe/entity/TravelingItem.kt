package net.kernelpanicsoft.boilerplate.pipe.entity

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.network.SResourceStack
import net.kernelpanicsoft.boilerplate.util.SDirection
import net.kernelpanicsoft.boilerplate.util.SDyeColor

/**
 * An item stack in flight through a pipe network.
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
	val stack: SResourceStack<SItemResource>,
	val fromDirection: SDirection,
	var progress: Float = 0f,
	var path: List<SBlockPos> = emptyList(),
	val color: SDyeColor? = null,
	val targetFace: SDirection? = null,
	val reservationId: Long? = null,
)

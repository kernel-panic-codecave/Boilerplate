package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter

/**
 * A filter hook's own state: [SortingHookState]'s filter card and routing config, plus [batchSize].
 *
 * A destination that consumes a fixed number at a time - three raw ore to a slurry - is jammed by a
 * partial delivery rather than helped by it, and a trickle of sorted output delivers partials
 * forever. [batchSize] makes this face accept **only whole multiples**, so the surplus is simply
 * never sent and stays wherever it already was.
 *
 * Deliberately no buffer of its own. Whatever feeds the network is already a buffer - a barrel
 * ahead of an extraction hook, the warehouse, the chest the sorter fills - and a hook that hoarded
 * items would add a second place for them to hide, with its own contents to persist, drop on break
 * and explain. `filter -> barrel -> extractor -> batching filter` holds the remainder in the barrel,
 * where the player can see and reach it.
 */
class FilterHookState : SortingHookState(FilterHookType.ID) {

	/**
	 * How many of a matching resource must move at once, or [NOT_BATCHED] to accept any amount.
	 *
	 * Applies to whatever the filter already passes: the filter says *what* may go, this says *how
	 * many at a time*, and neither needs to restate the other.
	 */
	var batchSize: Long by field(Long.serializer()) { NOT_BATCHED }

	/** [batchSize], floored - a negative size would mean "accept nothing, ever". */
	fun effectiveBatchSize(): Long = batchSize.coerceAtLeast(NOT_BATCHED)

	companion object {
		/** [batchSize]'s "off" value: accept whatever arrives, in whatever quantity. */
		const val NOT_BATCHED = 1L
	}
}

/**
 * The batch multiple a delivery along [route] must satisfy, or [FilterHookState.NOT_BATCHED] when
 * nothing along it batches.
 *
 * Read from the hook on the *last hop* - the face the delivery actually crosses - derived the same
 * way the deposit derives it, so this asks the hook that will really gate the insert rather than
 * some other face of the same pipe.
 *
 * @param from where the route started, used when the route is a single hop and has no prior step.
 */
fun batchAtRouteEnd(level: BlockGetter, from: BlockPos, route: List<BlockPos>): Long {
	val destination = route.lastOrNull() ?: return FilterHookState.NOT_BATCHED
	val previous = if (route.size >= 2) route[route.size - 2] else from
	val direction = Direction.fromDelta(
		destination.x - previous.x,
		destination.y - previous.y,
		destination.z - previous.z,
	) ?: return FilterHookState.NOT_BATCHED

	val tile = level.getBlockEntity(previous) as? MultipartBlockEntity ?: return FilterHookState.NOT_BATCHED
	val hook = tile.hooks[direction.name] as? FilterHookState ?: return FilterHookState.NOT_BATCHED
	return hook.effectiveBatchSize()
}

/**
 * The largest part of [desired] that may cross the last hop of [route]: a whole multiple of the
 * destination's batch size, and never more than [room].
 *
 * The single rule every push site shares. A destination that consumes a fixed number at a time is
 * jammed by a partial delivery, so what is sent is rounded down to a multiple *after* being capped
 * at what the destination will actually take - a nearly-full machine with space for two of a
 * three-item batch has to be left alone entirely rather than handed the two.
 *
 * Returns [desired] capped at [room] when nothing along the route batches, so an ordinary line is
 * unaffected.
 *
 * @param room what the destination will accept right now; leave unset when the caller has not
 *   probed it and only the multiple matters.
 */
fun batchedForRoute(
	level: BlockGetter,
	from: BlockPos,
	route: List<BlockPos>,
	desired: Long,
	room: Long = Long.MAX_VALUE,
): Long {
	val capped = minOf(desired, room)
	if (capped <= 0) return 0
	val batch = batchAtRouteEnd(level, from, route)
	return if (batch <= FilterHookState.NOT_BATCHED) capped else (capped / batch) * batch
}

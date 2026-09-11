package net.kernelpanicsoft.boilerplate.pipe.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * How much of each resource is already on its way to a given destination.
 *
 * A destination's own storage answers "how much room is there" and nothing else: a chest with one
 * slot free reports one slot free to every extractor probing it, however many stacks are at that
 * moment queued in the pipes pointing at it. Each of those extractors then pulls a full batch, and
 * all but the first stall at the last segment with nowhere to land - the resource is out of its
 * source, unreachable to anything else, and stuck there until the destination happens to drain.
 * What is already committed has to come off the room before the next pull is sized, and that is
 * what this answers.
 *
 * **Counted, not booked.** The alternative - a ledger incremented when a delivery sets off and
 * decremented when it lands - is exact until the first delivery that neither lands nor jams: a
 * broken pipe, a chunk unloaded mid-flight, a server stopped. Every one of those leaks a
 * reservation that nothing will ever release, and a leaked reservation silently blocks a
 * destination forever. This is rebuilt from the pipes' own contents every tick instead, so the
 * worst a lost delivery costs is the tick it was lost in.
 *
 * **One tick stale, deliberately.** [record] runs as pipes tick, in whatever order the level
 * happens to tick them, so the figure being accumulated is only complete once the last pipe has
 * run. Readers therefore see the *previous* tick's completed count plus whatever [commit] has
 * reported over the two ticks since - never the half-built one, which would read as "almost
 * nothing is in flight" for every pipe that had not ticked yet.
 *
 * **Only what is asked about.** [record] is called for every delivery in every pipe on every tick,
 * which is the hottest loop in the mod; keying and summing all of that for destinations nobody
 * probes would be pure cost. A destination starts being counted the first time [headedFor] asks
 * about it and stops [WATCHED_TTL_TICKS] after the last time anything did, so the per-delivery
 * price is one `long` lookup. The first probe of a destination therefore reads zero and may
 * over-commit exactly once, which is the behaviour every probe had before this existed.
 */
object InboundCensus {

	/**
	 * Notes that [amount] of [resource] is sitting in a pipe bound for [destination].
	 *
	 * Called once per delivery per tick, from the pipe's own transport loop and against the list it
	 * has already decoded - the count is a by-product of work that was happening anyway rather than
	 * a second pass over the network.
	 *
	 * Deliveries are counted where they *end* the tick, so one hopping between segments is counted
	 * by the receiving pipe or by neither (when that pipe has already ticked), never by both.
	 */
	fun record(level: ServerLevel, destination: BlockPos, resource: ResourceComponent, amount: Long) {
		if (amount <= 0) return
		val census = censusFor(level)
		if (!census.isWatched(destination)) return
		census.counting.merge(Key(destination.asLong(), ResourceIdentity.of(resource)), amount, Long::plus)
	}

	/**
	 * Notes a delivery that has just entered the network, before any pipe has had the chance to
	 * count it.
	 *
	 * Without this a hook pulling several times inside one tick - or two hooks on the same
	 * destination - would each see a figure that predates all of their own pulls and commit the
	 * same room over again.
	 */
	fun commit(level: ServerLevel, destination: BlockPos, resource: ResourceComponent, amount: Long) {
		if (amount <= 0) return
		val census = censusFor(level)
		if (!census.isWatched(destination)) return
		census.dispatching.merge(Key(destination.asLong(), ResourceIdentity.of(resource)), amount, Long::plus)
	}

	/**
	 * How much of [resource] is already travelling toward [destination], and marks it as a
	 * destination worth counting from here on.
	 *
	 * @return the committed amount, or `0` for a destination nothing is headed to - and for one
	 *   being asked about for the first time, which has not been counted yet.
	 */
	fun headedFor(level: ServerLevel, destination: BlockPos, resource: ResourceComponent): Long {
		val census = censusFor(level)
		val key = Key(census.watch(destination), ResourceIdentity.of(resource))
		return (census.complete[key] ?: 0L) + (census.dispatching[key] ?: 0L) + (census.dispatched[key] ?: 0L)
	}

	private fun censusFor(level: ServerLevel): LevelCensus =
		byLevel.getOrPut(level.dimension()) { LevelCensus() }.also { it.roll(level.gameTime) }

	private val byLevel = HashMap<ResourceKey<Level>, LevelCensus>()

	/** A destination and one resource bound for it - see [ResourceIdentity] for why the resource cannot key a map itself. */
	private data class Key(val destination: Long, val resource: ResourceIdentity)

	/**
	 * One level's counts, rolled forward each tick.
	 *
	 * [complete] is the last tick that finished; [counting] is the one being built. They are
	 * swapped rather than cleared and refilled so a tick's figures are never read half-written.
	 */
	private class LevelCensus {
		var complete: HashMap<Key, Long> = HashMap()
		var counting: HashMap<Key, Long> = HashMap()
		var dispatched: HashMap<Key, Long> = HashMap()
		var dispatching: HashMap<Key, Long> = HashMap()

		private val watched = HashMap<Long, Long>()
		private var tick: Long = Long.MIN_VALUE

		fun roll(now: Long) {
			if (now == tick) return
			// A gap of more than one tick means nothing counted in between - the level was paused
			// or the counts are simply stale, and carrying them forward would hold rooms closed
			// against deliveries that have long since landed.
			val contiguous = now == tick + 1
			complete = if (contiguous) counting else HashMap()
			counting = HashMap()
			// A tick behind [complete], because that is how far behind a commit is: a multipart runs
			// its transport before its hooks
			// ([net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity.tick]), so a
			// delivery a hook commits on tick T is first counted by its own segment on T+1 and only
			// reaches [complete] on T+2. Dropping it after one tick would leave it invisible for
			// exactly the tick in between - harmless at any sane extraction interval, and a blind
			// spot on every single pull at an interval of one.
			dispatched = if (contiguous) dispatching else HashMap()
			dispatching = HashMap()
			watched.values.removeIf { now - it > WATCHED_TTL_TICKS }
			tick = now
		}

		/** Whether [destination] is being counted - the per-delivery gate on the transport loop's hot path. */
		fun isWatched(destination: BlockPos): Boolean = destination.asLong() in watched

		/** Starts (or renews) counting [destination], and returns its key. */
		fun watch(destination: BlockPos): Long = destination.asLong().also { watched[it] = tick }
	}

	/**
	 * How long after the last probe a destination goes on being counted.
	 *
	 * Long enough that an extraction hook at any configured interval
	 * ([net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState.MAX_INTERVAL_TICKS]) keeps its
	 * own destination watched between pulls - a destination that fell out of the set between two
	 * pulls would read zero on the next one and hand back the over-commit this exists to prevent.
	 */
	private const val WATCHED_TTL_TICKS = 400L
}

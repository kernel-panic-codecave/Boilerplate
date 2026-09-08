package net.kernelpanicsoft.boilerplate.pipe.network

/**
 * A version counter for everything [PipeRouter.awaitsDelivery] answers from - bumped whenever a
 * Crafting CPU starts or stops waiting on something.
 *
 * [PipeRouter] gives an awaiting destination an unbeatable priority so a craft's own output is never
 * filed away while the craft still needs it. That priority is only ever applied by a *search*,
 * though, and searches are cached on `(network, version, resource, colour, exclude)` - a key that
 * describes topology and says nothing about job state. A warehouse accepts practically everything,
 * so on any network that has been running a while there is already a cached route to storage for
 * the very intermediate a job is about to produce, and it wins by never letting the search run at
 * all: the craft's own output goes to the shelf and the job stalls waiting for it.
 *
 * The existing guard covered only the opposite direction - it refuses to *store* a route chosen
 * because its destination was awaiting, so a finished job cannot go on attracting things. This is
 * the other half: a route cached while nothing was awaiting must not outlive something starting to.
 *
 * Deliberately one global counter rather than one per level or per network. A job transition is
 * rare (enqueue, backlog to active, finished, cleared) and the cost of over-invalidating is one
 * re-search on another level, where the cost of *under*-invalidating is the bug above. Bumped from
 * the setters of [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuMemberState.activeJob] and
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob.done] rather than from their call
 * sites, so a future path that changes either cannot forget to.
 */
object RoutingDemand {
	@Volatile
	private var epoch: Int = 0

	/** The current version - a router that saw a different one must drop its cached routes. */
	val version: Int get() = epoch

	/** Records that some destination has started or stopped awaiting a resource. */
	fun changed() {
		epoch++
	}
}

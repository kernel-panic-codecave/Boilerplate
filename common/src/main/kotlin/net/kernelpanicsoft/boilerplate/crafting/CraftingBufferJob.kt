package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.CraftJobTreeNode
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.kernelpanicsoft.boilerplate.pipe.network.RoutingDemand
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/** A submitting terminal's own reference to a job it handed off to a Crafting CPU cluster - [cpuLeaderPos] identifies the cluster (see [CraftingCpuManager]), [jobId] the specific [CraftingBufferJob] on it. */
data class SubmittedJobRef(val cpuLeaderPos: BlockPos, val jobId: String)

/**
 * One in-flight [CraftingResolver.Plan] execution, owned and driven by a Crafting CPU cluster's own
 * leader (see [CraftingCpuManager]) rather than a terminal. Not NBT-persisted, the same runtime-only
 * tradeoff [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem] already has;
 * [CraftingCpuMemberState.combinedStorage]'s real contents are what actually survives a reload,
 * and a job with nothing to show for itself after a reload just gets treated as already finished.
 *
 * Every per-resource map here is keyed by [ResourceIdentity], not by the resource: a step's own
 * resource may be a fluid now, and `FluidResource` has no value equality of its own (see
 * [ResourceIdentity]), so raw-resource keys would never match on lookup.
 */
class CraftingBufferJob(val id: String, val target: ResourceComponent, val targetAmount: Long, val steps: List<CraftStep>) {
	/** Whether every [CraftingResolver.Plan.stockPulls] resource has been fully claimed from a reachable warehouse yet - purely informational; claiming itself keeps retrying whatever's still short every tick regardless of this flag. */
	var stockClaimed: Boolean = false

	/** [CraftingResolver.Plan.stockPulls] not yet fully claimed - seeded on promotion, drained as [CraftingBufferEncasementType] successfully claims more of each resource. */
	val outstandingStockClaims: MutableMap<ResourceIdentity, Long> = mutableMapOf()

	/**
	 * What [outstandingStockClaims] started at, per resource - the plan's own
	 * [CraftingResolver.Plan.stockPulls], kept because that map is decremented as claims are placed
	 * and [outstandingOutput] needs to know how much of what has arrived came from stock rather than
	 * from this job's own machines.
	 */
	val stockPlanned: MutableMap<ResourceIdentity, Long> = mutableMapOf()

	/** The pattern-provider hook's own target position assigned to each [steps] index, once resolved - stable for the rest of that step. */
	val tableForStep: MutableMap<Int, BlockPos> = mutableMapOf()

	/** The pattern-provider hook's own position for each [steps] index - where a `CRAFTING`-kind step's own buffer delivery needs to land, see [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType]. */
	val hookPosForStep: MutableMap<Int, BlockPos> = mutableMapOf()

	/** [hookPosForStep]'s own face for each [steps] index, once resolved. */
	val hookFaceForStep: MutableMap<Int, Direction> = mutableMapOf()

	/** [hookPosForStep]'s own pattern-slot index for each [steps] index, once resolved - `null` if the pattern was somehow no longer held by the time it resolved. */
	val patternIndexForStep: MutableMap<Int, Int> = mutableMapOf()

	/** `(step index, ingredient identity)` -> cumulative amount already fed to that step's own target toward its requirement (`perRun * runs`). */
	val fedAmounts: MutableMap<Pair<Int, ResourceIdentity>, Long> = mutableMapOf()

	/**
	 * [steps] index -> cumulative amount of that step's own [CraftStep.resource] that has actually
	 * reached the cluster's own storage - not just its *current* amount there, since an intermediate
	 * step's own output is typically consumed again as a later step's input, which would otherwise
	 * make it look like less was ever produced than actually was.
	 *
	 * Recomputed each tick from live state (see [CraftingBufferEncasementType]'s own step loop)
	 * rather than accumulated as deliveries are dispatched. Counting dispatches was always a lie -
	 * a delivery still in flight counted as arrived - and there is nothing left to count now that
	 * outputs arrive by being *pushed* here rather than pulled by this job.
	 */
	val stepDelivered: MutableMap<Int, Long> = mutableMapOf()

	/** Ticks since the last attempt to drain a step's own output out of its machine - throttled rather than every tick, matching every other polling hook in this subsystem. */
	var ticksSincePull: Int = 0

	/** [steps] is empty (a request already fully covered by stock, nothing to craft) - how much of [targetAmount] has been claimed straight from a warehouse into the cluster's own storage so far. Unused otherwise; see [delivered]. */
	var stockOnlyDelivered: Long = 0

	/** [target]'s own value-comparable key - computed once, since every per-resource lookup below needs it. */
	val targetKey: ResourceIdentity = ResourceIdentity.of(target)

	/** How much of [targetAmount] has actually landed in the cluster's own storage so far - [target]'s own step (always present in [steps] once any crafting is needed at all) tracks this the same way as any other step; [stockOnlyDelivered] covers the [steps]-empty case instead. */
	val delivered: Long get() = steps.indexOfFirst { ResourceIdentity.of(it.resource) == targetKey }.let { if (it < 0) stockOnlyDelivered else stepDelivered[it] ?: 0L }

	/** Human-readable progress, surfaced to whichever terminal submitted this job. */
	var status: String = "Queued…"

	/** Whether this job is finished (delivered or gave up) - once true, the cluster drains everything left in its own storage back onto the network and clears this job out. */
	var done: Boolean = false
		set(value) {
			// Guarded on a real change: the runtime re-asserts this every tick once a job is
			// finished, and bumping [RoutingDemand] on each of those would flush every route cache
			// on the server continuously. Finishing also stops the cluster awaiting anything, which
			// is why it bumps at all.
			if (field == value) return
			field = value
			RoutingDemand.changed()
		}

	/** Whether any of [steps] produces [resource] - what tells "makes this but wants none right now" apart from "never makes it", for the trace. */
	fun needsAnyOf(resource: ResourceComponent): Boolean {
		val key = ResourceIdentity.of(resource)
		return steps.any { ResourceIdentity.of(it.resource) == key }
	}

	/**
	 * How much of [resource] this job's own steps still expect to receive, given [inStorage] of it
	 * sitting in the cluster right now - what makes this cluster a routing destination for it at
	 * all (see [CraftingCpuRuntime.awaitsDelivery]). `0` for a resource no step produces.
	 *
	 * Deliberately still only what the **steps produce**, never what the job consumes: attraction
	 * runs at an unbeatable priority, so widening it to inputs would let a cluster mid-job outrank
	 * every chest and warehouse on the network for every ingredient it happens to use.
	 *
	 * Already-received is `[inStorage] + whatever has since been fed onward as a later step's own
	 * input` - an intermediate is routinely consumed again the moment it lands, so its current
	 * amount alone would read as never having arrived and this job would keep attracting it forever
	 * - **minus whatever of it came from stock**, which is the subtlety this got wrong. A resource
	 * sourced partly from stock and partly from crafting is received in greater quantity than the
	 * steps will ever make, so counting all of it against step production drove this negative
	 * partway through: the cluster stopped advertising, the router filed the craft's own output into
	 * storage, and the job starved on the thing it was busy producing. Seen in the field as 304
	 * Infused Alloy wanted, 179 from stock, and the cluster giving up after 125.
	 */
	fun outstandingOutput(resource: ResourceComponent, inStorage: Long): Long {
		val key = ResourceIdentity.of(resource)
		var needed = 0L
		for (step in steps) {
			if (ResourceIdentity.of(step.resource) != key) continue
			needed += step.runs * (step.pattern.outputAmount(resource) ?: 1L)
		}
		if (needed <= 0L) return 0L
		val fedOnward = fedAmounts.entries.sumOf { (entry, amount) -> if (entry.second == key) amount else 0L }
		// Dispatched rather than confirmed-arrived, so a stock claim still in flight is discounted a
		// little early. That errs toward attracting for slightly longer, which is the safe direction:
		// the alternative is the cluster refusing deliveries it still needs.
		val fromStock = ((stockPlanned[key] ?: 0L) - (outstandingStockClaims[key] ?: 0L)).coerceAtLeast(0L)
		val fromSteps = (inStorage + fedOnward - fromStock).coerceAtLeast(0L)
		return needed - fromSteps
	}

	/** Whether [index]'s own requirement for [resource] (an input of `steps[index].pattern`) has been fully delivered yet - see [fedAmounts]. `true` for a resource that isn't actually one of [index]'s own inputs. */
	fun isInputFed(index: Int, resource: ResourceIdentity): Boolean {
		val perRun = steps[index].pattern.requiredInputs()[resource] ?: return true
		val needed = perRun * steps[index].runs
		return (fedAmounts[index to resource] ?: 0L) >= needed
	}

	/** Human-readable status for [index]'s own step, derived purely from this job's existing bookkeeping. */
	fun stepStatus(index: Int): String {
		if (done) return "Delivered"
		val step = steps[index]
		if (tableForStep[index] == null) return "Waiting for a pattern provider"
		val allFed = step.pattern.requiredInputs().keys.all { isInputFed(index, it) }
		return if (allFed) "Processing…" else "Feeding ingredients…"
	}

	private fun stepProgress(index: Int): Float {
		if (done) return 1f
		val step = steps[index]
		if (tableForStep[index] == null) return 0f
		val allFed = step.pattern.requiredInputs().keys.all { isInputFed(index, it) }
		return if (allFed) 0.75f else 0.4f
	}

	/** [target]'s own display name - what job status text names it by, for a fluid target as much as an item one. */
	fun targetName(): String = target.displayName().string

	/** Builds a [CraftJobTreeNode] rooted at the step producing [target] itself. `null` if [target] was fully covered by existing stock ([steps] empty), or this job is still queued (not yet promoted, nothing to recurse into). */
	fun toTree(): CraftJobTreeNode? {
		val rootIndex = steps.indexOfFirst { ResourceIdentity.of(it.resource) == targetKey }
		if (rootIndex < 0) return null

		fun build(index: Int): CraftJobTreeNode {
			val step = steps[index]
			val outputAmount = step.pattern.outputAmount(step.resource) ?: 1L
			val children = step.pattern.requiredInputs().keys.mapNotNull { input ->
				val childIndex = steps.indexOfFirst { ResourceIdentity.of(it.resource) == input }
				if (childIndex < 0) null else build(childIndex)
			}
			return CraftJobTreeNode(step.resource, step.runs * outputAmount, stepStatus(index), done, stepProgress(index), children)
		}

		val root = build(rootIndex)
		val overallProgress = if (targetAmount > 0) (delivered.toFloat() / targetAmount).coerceIn(0f, 1f) else if (done) 1f else 0f
		return root.copy(progress = overallProgress)
	}
}

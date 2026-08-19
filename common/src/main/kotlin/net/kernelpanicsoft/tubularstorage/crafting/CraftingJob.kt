package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.minecraft.core.BlockPos

/**
 * One in-flight [CraftingResolver.Plan] execution, driven forward a tick at a time by
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType.tick] - see
 * `docs/design/m4-crafting-automation.md`'s "Terminal" section. Purely a runtime bookkeeping
 * object living on [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState.jobs], not
 * NBT-persisted - an in-progress job doesn't survive a server restart, matching how little the
 * rest of the pipe network's own in-flight state
 * ([net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem] included) persists either.
 */
class CraftingJob(val target: ItemResource, val targetAmount: Long, val steps: List<CraftStep>) {
	/** The pattern provider hook's own target position assigned to each [steps] index, once one is picked - stable for the rest of the job so two steps never contend for the same target. */
	val tableForStep: MutableMap<Int, BlockPos> = mutableMapOf()

	/** `(step index, ingredient resource)` pairs whose delivery has already been requested - requested exactly once each, regardless of how many ticks it takes to arrive. */
	val fedInputs: MutableSet<Pair<Int, ItemResource>> = mutableSetOf()

	/** Ticks since the last attempt to pull [target] to its destination - throttled ([net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType.PULL_INTERVAL_TICKS]) rather than every tick, matching every other polling hook. */
	var ticksSincePull: Int = 0

	/** Human-readable progress, surfaced via [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity.craftJobStatus]. */
	var status: String = "Starting…"

	/** Whether this job is finished (delivered or gave up) and can be dropped from the queue. */
	var done: Boolean = false

	/**
	 * [index]'s own coarse progress, derived from this job's existing bookkeeping fields (no
	 * dedicated per-step state) - for [toTree]'s per-node status text.
	 */
	fun stepStatus(index: Int): String {
		if (done) return "Delivered"
		val step = steps[index]
		if (tableForStep[index] == null) return "Waiting for a pattern provider"
		val allFed = step.pattern.requiredInputs().keys.all { (index to it) in fedInputs }
		return if (allFed) "Processing…" else "Feeding ingredients…"
	}

	/**
	 * Builds a [CraftJobTreeNode] rooted at the step producing [target] itself, recursively
	 * expanding into the steps producing each of its own crafted (not stock-pulled) ingredients -
	 * see [CraftJobTreeNode]'s own KDoc for why a shared sub-resource appears once per consumer
	 * rather than being deduplicated into a DAG. `null` if [target] is being fulfilled straight
	 * from stock/a warehouse ([steps] empty) - nothing to show a tree for.
	 */
	fun toTree(): CraftJobTreeNode? {
		val rootIndex = steps.indexOfFirst { it.resource == target }
		if (rootIndex < 0) return null

		fun build(index: Int): CraftJobTreeNode {
			val step = steps[index]
			val outputAmount = step.pattern.outputs.firstOrNull { it.resource == step.resource }?.amount ?: 1L
			val children = step.pattern.requiredInputs().keys.mapNotNull { input ->
				val childIndex = steps.indexOfFirst { it.resource == input }
				if (childIndex < 0) null else build(childIndex)
			}
			return CraftJobTreeNode(step.resource, step.runs * outputAmount, stepStatus(index), done, children)
		}

		return build(rootIndex)
	}
}

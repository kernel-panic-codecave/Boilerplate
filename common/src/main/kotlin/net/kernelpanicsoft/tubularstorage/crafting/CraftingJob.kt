package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

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
	/**
	 * The pattern provider hook's own target position assigned to each [steps] index, once one is
	 * picked - stable for the rest of that step. Unlike before, more than one step can end up
	 * pointing at the exact same target now: an [AssemblyTableBlockEntity] runs multiple patterns
	 * in parallel (see [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType]'s own
	 * KDoc), so there's no reason two steps that happen to share a hook holding both their own
	 * patterns can't both use it.
	 */
	val tableForStep: MutableMap<Int, BlockPos> = mutableMapOf()

	/**
	 * The pattern provider hook's own position (not [tableForStep]'s target position) assigned to
	 * each [steps] index - an [AssemblyTableBlockEntity] target buffers ingredients per pattern
	 * slot on its own hook rather than a shared physical grid, so delivery for that case needs to
	 * reach the *hook*, not the table itself. Populated in lockstep with [tableForStep].
	 */
	val hookPosForStep: MutableMap<Int, BlockPos> = mutableMapOf()

	/**
	 * [hookPosForStep]'s own [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState.patterns]
	 * slot index for each [steps] index, once resolved - which of that hook's own several
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState.patternBuffers] this
	 * step's own ingredients need to land in directly, bypassing the shared, round-robin
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternBufferIO] (see
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.advanceTerminalJobs]'s own same-table self-supply
	 * step for why that matters). Populated in lockstep with [tableForStep]/[hookPosForStep]; `null`
	 * if the pattern was somehow no longer held by the time it resolved (the hook's own patterns
	 * changed mid-job, say) - that step's self-supply is simply skipped, falling back to whatever
	 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.request] can still resolve.
	 */
	val patternIndexForStep: MutableMap<Int, Int> = mutableMapOf()

	/**
	 * [hookPosForStep]'s own face for each [steps] index, once resolved - which specific face of
	 * that block actually carries the [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState]
	 * this step means, threaded all the way through
	 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.request]'s own
	 * `deliverFace` so delivery lands correctly even when another same-type hook sits on a different
	 * face of the same block (see [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem.targetFace]'s
	 * own KDoc). Populated in lockstep with [tableForStep]/[hookPosForStep]/[patternIndexForStep].
	 */
	val hookFaceForStep: MutableMap<Int, Direction> = mutableMapOf()

	/**
	 * `(step index, ingredient resource)` -> cumulative amount already delivered toward that
	 * step's own requirement (`perRun * runs`). A step's input isn't [isInputFed] until the *full*
	 * amount has arrived, not just after the first delivery, however small - for a multi-step job
	 * an earlier step producing this exact input (sticks needing planks, planks themselves crafted
	 * from logs by an earlier step in the same job) very often hasn't caught up yet when this step
	 * first attempts to feed, so a single partial shipment marked as "done" would permanently starve
	 * the rest of what this step actually needs.
	 */
	val fedAmounts: MutableMap<Pair<Int, ItemResource>, Long> = mutableMapOf()

	/** Whether [index]'s own requirement for [resource] (an input of `steps[index].pattern`) has been fully delivered yet - see [fedAmounts]. `true` for a resource that isn't actually one of [index]'s own inputs. */
	fun isInputFed(index: Int, resource: ItemResource): Boolean {
		val perRun = steps[index].pattern.requiredInputs()[resource] ?: return true
		val needed = perRun * steps[index].runs
		return (fedAmounts[index to resource] ?: 0L) >= needed
	}

	/** Ticks since the last attempt to pull [target] to its destination - throttled ([net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType.PULL_INTERVAL_TICKS]) rather than every tick, matching every other polling hook. */
	var ticksSincePull: Int = 0

	/**
	 * How much of [targetAmount] has actually shipped so far - a single
	 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.request] pull can return
	 * less than it was asked for (a source only had part of it, say), so this job isn't [done] until
	 * enough pulls have accumulated the full amount, not after the first one that ships anything at
	 * all. Each pull only asks for the remainder (`targetAmount - delivered`), so a slow multi-run
	 * batch (many runs of the same pattern feeding one big request) is collected incrementally
	 * rather than requiring every run to finish before the first pull is even attempted.
	 */
	var delivered: Long = 0

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
		val allFed = step.pattern.requiredInputs().keys.all { isInputFed(index, it) }
		return if (allFed) "Processing…" else "Feeding ingredients…"
	}

	/** [index]'s own coarse `0f..1f` progress, matching [stepStatus]'s own states - see [CraftJobTreeNode]'s own KDoc for why the root node overrides this with real fractional progress instead. */
	private fun stepProgress(index: Int): Float {
		if (done) return 1f
		val step = steps[index]
		if (tableForStep[index] == null) return 0f
		val allFed = step.pattern.requiredInputs().keys.all { isInputFed(index, it) }
		return if (allFed) 0.75f else 0.4f
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
			return CraftJobTreeNode(step.resource, step.runs * outputAmount, stepStatus(index), done, stepProgress(index), children)
		}

		val root = build(rootIndex)
		val overallProgress = if (targetAmount > 0) (delivered.toFloat() / targetAmount).coerceIn(0f, 1f) else if (done) 1f else 0f
		return root.copy(progress = overallProgress)
	}
}

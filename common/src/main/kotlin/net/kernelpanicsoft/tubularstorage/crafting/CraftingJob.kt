package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
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
	/** The assembly table position assigned to each [steps] index, once one is picked - stable for the rest of the job so two steps never contend for the same table. */
	val tableForStep: MutableMap<Int, BlockPos> = mutableMapOf()

	/** `(step index, ingredient resource)` pairs whose delivery has already been requested - requested exactly once each, regardless of how many ticks it takes to arrive. */
	val fedInputs: MutableSet<Pair<Int, ItemResource>> = mutableSetOf()

	/** Ticks since the last attempt to pull [target] to its destination - throttled ([net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType.PULL_INTERVAL_TICKS]) rather than every tick, matching every other polling hook. */
	var ticksSincePull: Int = 0

	/** Human-readable progress, surfaced via [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity.craftJobStatus]. */
	var status: String = "Starting…"

	/** Whether this job is finished (delivered or gave up) and can be dropped from the queue. */
	var done: Boolean = false
}

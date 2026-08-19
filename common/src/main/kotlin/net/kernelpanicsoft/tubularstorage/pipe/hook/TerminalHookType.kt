package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Turns the attached face into a search/withdraw window over every
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity] reachable on the
 * network - see `docs/design/m3-warehouse-storage.md`. Also drives [TerminalHookState.jobs]
 * forward a step per tick via [advanceTerminalJobs] - see `docs/design/m4-crafting-automation.md`'s
 * "Terminal" section. That function is a plain top-level one, not a method here, so
 * [CraftingTerminalHookType] (whose own state is a [CraftingTerminalHookState], a [TerminalHookState]
 * subtype) can drive the exact same job queue without needing to extend this object.
 */
object TerminalHookType : PipeHookType<TerminalHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "terminal"

	override fun createState(): TerminalHookState = TerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		TerminalHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: TerminalHookState) {
		advanceTerminalJobs(level, pos, tile, state)
	}

	override fun asItem(): Item = ItemRegistry.TerminalHook

	/**
	 * Wider than [DEFAULT_SHAPES]: a full-face plate (pixels 0-2 deep) plus the same 4x4 strut every
	 * other hook has (pixels 2-6), unioned per face - matches `terminal_hook.json`'s own two
	 * elements (`[0,0,0]`-`[16,16,2]` outward plate, `[6,6,2]`-`[10,10,6]` strut reaching to the
	 * pipe), where every other hook model is just the strut alone.
	 */
	override val shapesByDirection: Map<Direction, VoxelShape> = mapOf(
		Direction.NORTH to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.125), Shapes.box(0.375, 0.375, 0.125, 0.625, 0.625, 0.375)),
		Direction.SOUTH to Shapes.or(Shapes.box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0), Shapes.box(0.375, 0.375, 0.625, 0.625, 0.625, 0.875)),
		Direction.WEST to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0), Shapes.box(0.125, 0.375, 0.375, 0.375, 0.625, 0.625)),
		Direction.EAST to Shapes.or(Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.625, 0.375, 0.375, 0.875, 0.625, 0.625)),
		Direction.DOWN to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.125, 1.0), Shapes.box(0.375, 0.125, 0.375, 0.625, 0.375, 0.625)),
		Direction.UP to Shapes.or(Shapes.box(0.0, 0.875, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.375, 0.625, 0.375, 0.625, 0.875, 0.625)),
	)
}

/** Ticks between retries of the final [CraftingJob.target] pull once every step has at least been fed - matches [RequesterHookType.REQUEST_INTERVAL_TICKS]'s own polling cadence. */
const val PULL_INTERVAL_TICKS = 40

/** Drives [state]'s job queue forward one tick, shared by [TerminalHookType] and [CraftingTerminalHookType] - see [advance]'s own KDoc. */
fun advanceTerminalJobs(level: ServerLevel, pos: BlockPos, tile: HookBlockEntity, state: TerminalHookState) {
	val job = state.jobs.firstOrNull() ?: return
	advance(level, pos, job)
	if (tile.craftJobStatus != job.status) {
		tile.craftJobStatus = job.status
		level.sendBlockUpdated(pos, tile.blockState, tile.blockState, Block.UPDATE_ALL)
	}
	if (job.done) state.jobs.removeAt(0)
}

/**
 * Advances [job] by exactly one tick's worth of work: feeds any still-unrequested step
 * ingredients into their assigned [PatternProviderHookType] hook's own attached target (picked
 * once, the first reachable hook holding a step's [net.kernelpanicsoft.tubularstorage.crafting.Pattern]
 * whose target no other step in this job has already claimed), then - throttled to
 * [PULL_INTERVAL_TICKS] - retries pulling [CraftingJob.target] itself to [TerminalHookState.output]
 * (this hook's own built-in delivery slots, at [pos] directly), exactly the same
 * [RequestFulfillment.request] a plain withdrawal uses. A step with no [CraftingJob.steps] at
 * all (the target was already fully covered by stock) skips straight to that pull. Reaching the
 * target this way requires whatever produces it - a reachable warehouse, or a
 * [PatternProviderHookType] hook (`providesItems = true`) exposing its own target's output once
 * produced - the same requirement every other provider-style hook already has for pulling from
 * a non-pipe inventory; nothing here reaches into anything without one.
 */
private fun advance(level: ServerLevel, pos: BlockPos, job: CraftingJob) {
	if (job.steps.isEmpty()) {
		val delivered = RequestFulfillment.request(level, pos, ResourceStack(job.target, job.targetAmount), pos)
		job.status = if (delivered) "Requested ${job.targetAmount}x ${job.target.cachedStack.hoverName.string}" else "Nothing available to fulfill the request"
		job.done = true
		return
	}

	for ((index, step) in job.steps.withIndex()) {
		var tablePos = job.tableForStep[index]
		if (tablePos == null) {
			val provider = RequestFulfillment.reachablePatternProviders(level, pos)
				.firstOrNull { it.state.heldPatterns().contains(step.pattern) && it.targetPos !in job.tableForStep.values }
			if (provider == null) {
				job.status = "No free pattern provider for ${step.resource.cachedStack.hoverName.string}"
				continue
			}
			tablePos = provider.targetPos
			job.tableForStep[index] = tablePos
		}
		for ((resource, perRun) in step.pattern.requiredInputs()) {
			val key = index to resource
			if (key in job.fedInputs) continue
			if (RequestFulfillment.request(level, pos, ResourceStack(resource, perRun * step.runs), tablePos)) job.fedInputs += key
		}
	}

	job.ticksSincePull++
	if (job.ticksSincePull < PULL_INTERVAL_TICKS) {
		val fedSteps = job.steps.indices.count { i -> job.steps[i].pattern.requiredInputs().keys.all { r -> (i to r) in job.fedInputs } }
		job.status = "Crafting ($fedSteps/${job.steps.size} step(s) fed)…"
		return
	}
	job.ticksSincePull = 0

	if (RequestFulfillment.request(level, pos, ResourceStack(job.target, job.targetAmount), pos)) {
		job.status = "Delivered ${job.targetAmount}x ${job.target.cachedStack.hoverName.string}"
		job.done = true
	} else {
		job.status = "Waiting on ${job.steps.size} crafting step(s)…"
	}
}

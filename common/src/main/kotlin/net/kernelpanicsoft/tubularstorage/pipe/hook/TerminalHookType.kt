package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu
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
		advanceTerminalJobs(level, pos, direction, tile, state)
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

/** Drives [state]'s job queue forward one tick, shared by [TerminalHookType] and [CraftingTerminalHookType] - see [advance]'s own KDoc. [direction] is this specific [state]'s own face on [tile], threaded through so a delivery back to this same hook (the final [CraftingJob.target] pull) lands on the right face even when another terminal shares the block. */
fun advanceTerminalJobs(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: TerminalHookState) {
	val job = state.jobs.firstOrNull() ?: return
	advance(level, pos, direction, job)
	if (tile.craftJobStatus != job.status) {
		tile.craftJobStatus = job.status
		level.sendBlockUpdated(pos, tile.blockState, tile.blockState, Block.UPDATE_ALL)
	}
	if (job.done) state.jobs.removeAt(0)
}

/**
 * Advances [job] by exactly one tick's worth of work: feeds any not-yet-fully-fed step
 * ingredients into their assigned [PatternProviderHookType] hook's own attached target (picked
 * once, the first reachable hook holding a step's [net.kernelpanicsoft.tubularstorage.crafting.Pattern]
 * - more than one step can end up sharing the exact same hook, an [AssemblyTableBlockEntity]
 * target runs multiple patterns' own runs in parallel, see [PatternProviderHookType]'s own KDoc).
 * For an [AssemblyTableBlockEntity] target specifically, a step's own required input already
 * sitting in that same table's own output (an earlier step of this same job, feeding the same
 * hook, having already produced it) is moved directly into that step's own pattern-slot buffer
 * before anything else is attempted - see the inline "same-table self-supply" comment below for
 * why that has to bypass [PatternBufferIO]'s shared, round-robin insert entirely rather than
 * reaching it through an ordinary [RequestFulfillment.request]. Either way, each step's own
 * remaining shortfall (`needed - `[CraftingJob.fedAmounts]`[key]`) is re-requested every tick until
 * [CraftingJob.isInputFed], not just once, since a multi-step job's own earlier step (logs into
 * planks, say) very often hasn't produced enough of a later step's input (planks into sticks) yet
 * on the tick that later step first attempts to feed - then, throttled to
 * [PULL_INTERVAL_TICKS] - retries pulling whatever's still outstanding of [CraftingJob.target]
 * (`targetAmount - delivered`) to [TerminalHookState.output] (this hook's own built-in delivery
 * slots, at [pos] directly), exactly the same [RequestFulfillment.request] a plain withdrawal
 * uses. A single pull can easily ship less than asked for - a big batch (many runs of the same
 * pattern) accumulates in a [PatternProviderHookType] hook's own target output over time, not all
 * at once - so [CraftingJob.delivered] tracks progress across as many pulls as it takes, and the
 * job isn't [CraftingJob.done] until the full amount has actually shipped. A step with no
 * [CraftingJob.steps] at all (the target was already fully covered by stock) skips straight to
 * that pull, same accumulation - its very first attempt runs immediately rather than waiting a
 * full [PULL_INTERVAL_TICKS] (an ordinary instant withdrawal shouldn't feel throttled), only
 * falling back to the same [PULL_INTERVAL_TICKS] cadence once a first attempt already shipped
 * something but not the full amount, so a slow warehouse retrieval isn't re-enqueued every single
 * tick while its own gantry job is still in flight. Reaching the target this way requires whatever produces it - a
 * reachable warehouse, or a [PatternProviderHookType] hook (`providesItems = true`) exposing its
 * own target's output once produced - the same requirement every other provider-style hook
 * already has for pulling from a non-pipe inventory; nothing here reaches into anything without
 * one.
 */
private fun advance(level: ServerLevel, pos: BlockPos, direction: Direction, job: CraftingJob) {
	if (job.steps.isEmpty()) {
		job.ticksSincePull++
		if (job.delivered > 0 && job.ticksSincePull < PULL_INTERVAL_TICKS) return
		job.ticksSincePull = 0

		val shipped = RequestFulfillment.request(level, pos, ResourceStack(job.target, job.targetAmount - job.delivered), pos, direction)
		job.delivered += shipped
		if (job.delivered >= job.targetAmount) {
			job.status = "Requested ${job.targetAmount}x ${job.target.cachedStack.hoverName.string}"
			job.done = true
		} else if (shipped <= 0) {
			job.status = "Nothing available to fulfill the request"
			job.done = true
		}
		return
	}

	for ((index, step) in job.steps.withIndex()) {
		var tablePos = job.tableForStep[index]
		if (tablePos == null) {
			// No longer excludes a table another step of this same job already claimed - an
			// AssemblyTableBlockEntity target now runs multiple steps' own patterns in parallel
			// (see PatternProviderHookType's own KDoc), so there's no reason two steps that happen
			// to share one hook can't both use it.
			val provider = RequestFulfillment.reachablePatternProviders(level, pos)
				.firstOrNull { it.state.heldPatterns().contains(step.pattern) }
			if (provider == null) {
				job.status = "No free pattern provider for ${step.resource.cachedStack.hoverName.string}"
				continue
			}
			tablePos = provider.targetPos
			job.tableForStep[index] = tablePos
			job.hookPosForStep[index] = provider.hookPos
			job.hookFaceForStep[index] = provider.direction
			provider.state.indexOfPattern(step.pattern)?.let { job.patternIndexForStep[index] = it }
		}
		// An AssemblyTableBlockEntity target buffers ingredients per pattern slot on its own hook
		// (see PatternBufferIO) rather than sharing a physical grid - delivery targets the hook's
		// own position for that case, or the target directly for anything else (a generic
		// inventory that was never rebuilt to understand buffers).
		val table = level.getBlockEntity(tablePos) as? AssemblyTableBlockEntity
		val deliverTo = if (table != null) job.hookPosForStep.getValue(index) else tablePos
		// deliverFace only makes sense once deliverTo is actually the hook's own position (the
		// AssemblyTableBlockEntity/buffer case) - for a generic target, deliverTo is the target
		// itself, an entirely different block from the hook this face belongs to.
		val deliverFace = if (table != null) job.hookFaceForStep[index] else null
		val patternIndex = job.patternIndexForStep[index]
		val hookState = if (table != null && patternIndex != null && deliverFace != null) {
			patternProviderStateAt(level, job.hookPosForStep.getValue(index), deliverFace)
		} else null
		for ((resource, perRun) in step.pattern.requiredInputs()) {
			if (job.isInputFed(index, resource)) continue
			val key = index to resource
			val needed = perRun * step.runs
			var already = job.fedAmounts[key] ?: 0L
			// Same-table self-supply: this step's own required input, already sitting in this same
			// table's own output (produced by an earlier step of this job feeding the same hook),
			// moves straight into this step's own pattern-slot buffer - bypassing PatternBufferIO's
			// shared, round-robin insert entirely, which has no way to know a delivery is meant for
			// one specific step's own slot rather than whichever slot it happens to visit first (see
			// RequestFulfillment.fulfillFromProvider's own KDoc for the bug this replaced).
			if (table != null && hookState != null && patternIndex != null) {
				val shortfall = needed - already
				val available = table.output.extract(resource, shortfall, true)
				if (available > 0) {
					val buffer = hookState.bufferFor(patternIndex)
					val roomInBuffer = buffer.insert(resource, available, true)
					val moved = table.output.extract(resource, roomInBuffer, false)
					if (moved > 0) {
						buffer.insert(resource, moved, false)
						already += moved
						job.fedAmounts[key] = already
					}
				}
			}
			if (job.isInputFed(index, resource)) continue
			val shipped = RequestFulfillment.request(level, pos, ResourceStack(resource, needed - already), deliverTo, deliverFace)
			if (shipped > 0) job.fedAmounts[key] = already + shipped
		}
	}

	job.ticksSincePull++
	if (job.ticksSincePull < PULL_INTERVAL_TICKS) {
		val fedSteps = job.steps.indices.count { i -> job.steps[i].pattern.requiredInputs().keys.all { r -> job.isInputFed(i, r) } }
		job.status = "Crafting ($fedSteps/${job.steps.size} step(s) fed)…"
		return
	}
	job.ticksSincePull = 0

	val shipped = RequestFulfillment.request(level, pos, ResourceStack(job.target, job.targetAmount - job.delivered), pos, direction)
	job.delivered += shipped
	if (job.delivered >= job.targetAmount) {
		job.status = "Delivered ${job.targetAmount}x ${job.target.cachedStack.hoverName.string}"
		job.done = true
	} else {
		job.status = "Waiting on ${job.steps.size} crafting step(s)… (${job.delivered}/${job.targetAmount} delivered)"
	}
}

/** The [PatternProviderHookState] on [face] of the [HookBlockEntity] at [hookPos] specifically - disambiguates two [PatternProviderHookType] hooks sharing one block, the same way [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Hook]'s own [direction]-first lookup does. */
private fun patternProviderStateAt(level: ServerLevel, hookPos: BlockPos, face: Direction): PatternProviderHookState? {
	val tile = level.getBlockEntity(hookPos) as? HookBlockEntity ?: return null
	return tile.hooks[face.name] as? PatternProviderHookState
}

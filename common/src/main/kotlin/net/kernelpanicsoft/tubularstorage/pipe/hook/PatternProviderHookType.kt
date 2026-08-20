package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.PatternProviderHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Holds encoded [net.kernelpanicsoft.tubularstorage.crafting.PatternItem]s and decides *what* to
 * run against whatever's on its own attached face - "any adjacent inventory," not just an
 * [AssemblyTableBlockEntity], the same way [ProviderHookType]/[ExtractionHookType] already reach
 * into any generic [ItemApi.BLOCK]-exposed inventory.
 *
 * An [AssemblyTableBlockEntity] target gets the full buffered/parallel treatment
 * ([tickAssemblyTable]): ingredient delivery aimed at this hook lands in [PatternProviderHookState.patternBuffers]
 * (via [PatternBufferIO], exposed on this hook's own position - see
 * [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Hook]'s registration), and once a
 * buffer holds a full run's worth, it's atomically moved out and handed to
 * [AssemblyTableBlockEntity.beginBufferedRun] - up to [AssemblyTableBlockEntity.maxParallelRuns]
 * simultaneous runs, not just one, and never touching the table's own [AssemblyTableBlockEntity.grid]
 * at all, so different patterns' ingredients (even ones sharing an input type) can never collide.
 *
 * Any other target (a vanilla furnace, say - something that already processes on its own once fed
 * and was never rebuilt to understand buffers) falls back to the older single-slot behavior
 * ([tickGenericTarget]): ingredient delivery still targets the target's own position directly, and
 * [PatternProviderHookState.activeSlot] tracks the one pattern presumed in flight, guessed via
 * plain ingredient presence - there's no generic "is this inventory still processing" query the way
 * [AssemblyTableBlockEntity] itself exposes one. [providesItems] reuses the exact same
 * [ProviderHookType]-style pull machinery
 * ([net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.reachableProviders]) to
 * expose the target's own output as network stock once produced - no separate "pull" logic needed
 * here at all. See `docs/design/m4-crafting-automation.md`.
 */
object PatternProviderHookType : PipeHookType<PatternProviderHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "pattern_provider"

	override fun createState(): PatternProviderHookState = PatternProviderHookState()

	override val hasMenu: Boolean = true
	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		PatternProviderHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: HookBlockEntity, state: PatternProviderHookState) {
		val targetPos = pos.relative(direction)
		val targetTile = level.getBlockEntity(targetPos)
		if (targetTile is AssemblyTableBlockEntity) {
			tickAssemblyTable(state, targetTile)
		} else {
			tickGenericTarget(level, targetPos, direction, state)
		}
	}

	/** Starts as many new parallel runs on [table] as its own [AssemblyTableBlockEntity.maxParallelRuns] currently allows room for, one per pattern slot whose own [PatternProviderHookState.patternBuffers] entry already holds a full run's worth. */
	private fun tickAssemblyTable(state: PatternProviderHookState, table: AssemblyTableBlockEntity) {
		for (index in 0 until state.patterns.size()) {
			if (table.activeRuns.size >= table.maxParallelRuns()) return
			val pattern = patternAt(state, index) ?: continue
			val buffer = state.bufferFor(index)
			val requiredInputs = pattern.requiredInputs()
			if (requiredInputs.any { (resource, perRun) -> amountIn(buffer, resource) < perRun }) continue
			for ((resource, perRun) in requiredInputs) buffer.extract(resource, perRun, false)
			table.beginBufferedRun(pattern)
		}
	}

	/** The older, single-run-at-a-time behavior for a target with no buffered/parallel processing of its own - see this object's own KDoc. */
	private fun tickGenericTarget(level: ServerLevel, targetPos: BlockPos, direction: Direction, state: PatternProviderHookState) {
		val active = state.activeSlot
		if (active != null) {
			val pattern = patternAt(state, active)
			val stillRunning = pattern != null && inputsPresent(level, targetPos, direction.opposite, pattern)
			if (!stillRunning) state.activeSlot = null
			return
		}

		for (index in 0 until state.patterns.size()) {
			val pattern = patternAt(state, index) ?: continue
			if (!inputsPresent(level, targetPos, direction.opposite, pattern)) continue
			state.activeSlot = index
			return
		}
	}

	private fun patternAt(state: PatternProviderHookState, index: Int): Pattern? {
		val stack = state.patterns.get(index).getItem()
		if (stack.isEmpty) return null
		return PatternItemData(stack).pattern
	}

	/** Total amount of [resource] currently sitting across every slot of [storage] - [ArchieItemStorage] has no direct "how much of X do I hold" query of its own. */
	internal fun amountIn(storage: ArchieItemStorage, resource: ItemResource): Long {
		var total = 0L
		for (i in 0 until storage.size()) if (storage.getResource(i) == resource) total += storage.getAmount(i)
		return total
	}

	/**
	 * Whether [pattern]'s own [Pattern.requiredInputs] are all currently sitting in whatever's at
	 * [targetPos] - queried from [direction], the face the target itself was reached through.
	 * Reads the target's combined [earth.terrarium.common_storage_lib.storage.base.CommonStorage.get]/
	 * [earth.terrarium.common_storage_lib.storage.base.CommonStorage.size] view rather than
	 * simulating an [earth.terrarium.common_storage_lib.storage.base.CommonStorage.extract] - a
	 * directed exposure like [net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity.ioStorage]
	 * only ever `extract`s from its *output* side, so simulating an extract of the *input*
	 * ingredients sitting in its grid would always read as unavailable.
	 */
	private fun inputsPresent(level: ServerLevel, targetPos: BlockPos, direction: Direction, pattern: Pattern): Boolean {
		val storage = ItemApi.BLOCK.find(level, targetPos, direction) ?: return false
		val totals = HashMap<ItemResource, Long>()
		for (i in 0 until storage.size()) {
			val slot = storage.get(i)
			if (slot.resource.isBlank) continue
			totals[slot.resource] = (totals[slot.resource] ?: 0L) + slot.amount
		}
		for ((resource, amount) in pattern.requiredInputs()) {
			if ((totals[resource] ?: 0L) < amount) return false
		}
		return true
	}

	override fun asItem(): Item = ItemRegistry.PatternProviderHook
}

/**
 * Bridges [state]'s own [PatternProviderHookState.patternBuffers] into one [CommonStorage],
 * exposed directly on the pattern-provider hook's own block position (see
 * [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Hook]'s registration) - what a
 * delivery aimed at this hook (rather than its target) actually lands in.
 *
 * [insert] distributes [resource] across every pattern slot whose own [Pattern.requiredInputs]
 * wants more of it, round-robin rather than filling earlier slots (in [PatternProviderHookState.patterns]'
 * own order) to their own full [PatternProviderHookState.MAX_BUFFERED_RUNS_PER_PATTERN] cap before
 * ever touching the next one - each pass only tops a slot up to its own *next* single-run
 * boundary, cycling through every still-demanding slot before starting a second pass. A single
 * `insert` call has no idea whether some *other*, separate call (a different job step's own
 * delivery, arriving on a different tick) is about to want the exact same resource for a different
 * slot - greedily filling one slot to its full multi-run cap first would starve that other slot
 * indefinitely if the two calls' combined amount never happens to exceed the first slot's own
 * cap. Round-robin instead guarantees every demanding slot gets at least its own first run's worth
 * before any slot gets its second.
 *
 * [extract] always reports nothing available - buffers are feed-only staging, never a pull source
 * in their own right (a completed run's real output comes from [AssemblyTableBlockEntity.output]
 * instead, exposed the ordinary way).
 */
class PatternBufferIO(private val state: PatternProviderHookState) : CommonStorage<ItemResource> {
	override fun size(): Int = state.patterns.size()
	override fun get(index: Int): StorageSlot<ItemResource> = state.bufferFor(index).get(0)

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		var madeProgress = true
		while (remaining > 0 && madeProgress) {
			madeProgress = false
			for (index in 0 until state.patterns.size()) {
				if (remaining <= 0) break
				val stack = state.patterns.get(index).getItem()
				if (stack.isEmpty) continue
				val pattern = PatternItemData(stack).pattern ?: continue
				val perRun = pattern.requiredInputs()[resource] ?: continue
				val buffer = state.bufferFor(index)
				val cap = perRun * PatternProviderHookState.MAX_BUFFERED_RUNS_PER_PATTERN
				val already = PatternProviderHookType.amountIn(buffer, resource)
				if (already >= cap) continue
				val nextRunBoundary = minOf(((already / perRun) + 1) * perRun, cap)
				val roomThisPass = nextRunBoundary - already
				if (roomThisPass <= 0) continue
				val inserted = buffer.insert(resource, minOf(remaining, roomThisPass), simulate)
				if (inserted > 0) madeProgress = true
				remaining -= inserted
			}
		}
		return amount - remaining
	}

	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long = 0
}

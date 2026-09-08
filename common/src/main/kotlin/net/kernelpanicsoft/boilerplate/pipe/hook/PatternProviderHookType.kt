package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternProviderHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType.tickGenericTarget
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType.tickVanillaCraftingTable
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Blocks
import net.kernelpanicsoft.boilerplate.network.roomFor
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace

/**
 * Holds encoded [net.kernelpanicsoft.boilerplate.crafting.PatternItem]s and decides *what* to
 * run against whatever's on its own attached face - "any adjacent inventory," the same way
 * [ProviderHookType]/[ExtractionHookType] already reach into any generic [ItemApi.BLOCK]-exposed
 * inventory.
 *
 * A real, placed vanilla crafting table is the special case for a `CRAFTING`-kind pattern,
 * detected by block state rather than block entity (a crafting table has none of its own)
 * ([tickVanillaCraftingTable]): resolves instantly, since a pattern's own output was already
 * assembled once at encode time - see its own KDoc. Ingredient staging
 * ([PatternProviderHookState.patternBuffers], exposed on this hook's own position via
 * [PatternBufferIO] - see [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]'s
 * registration) and the assembled result ([PatternProviderHookState.patternOutputBuffers],
 * exposed via [PatternOutputIO]) both live entirely as virtual hook state, since the table itself
 * has no inventory to hold either.
 *
 * Any other target (a vanilla furnace, say, or a future crusher - a `PROCESSING`-kind pattern's
 * own real, self-driving machine) falls back to a single-slot behavior ([tickGenericTarget]):
 * ingredient delivery targets the target's own position directly, and
 * [PatternProviderHookState.activeSlot] tracks the one pattern presumed in flight, guessed via
 * plain ingredient presence - there's no generic "is this inventory still processing" query.
 * [providesItems] reuses the exact same [ProviderHookType]-style pull machinery
 * ([net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.reachableProviders]) to
 * expose the target's own output as network stock once produced - no separate "pull" logic needed
 * here at all. A Crafting CPU is what actually feeds/drains either case as part of a job's own
 * steps - see `docs/design/m4-crafting-automation.md`.
 */
object PatternProviderHookType : PipeHookType<PatternProviderHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "pattern_provider"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Item) }

	/** [PipeHookType.basePressureCost] - Can convert a whole batch of patterns in one tick - the heaviest per-tick work among the hooks. */
	override val basePressureCost: Long = 4L

	override fun createState(): PatternProviderHookState = PatternProviderHookState()

	override val hasMenu: Boolean = true
	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		PatternProviderHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: PatternProviderHookState) {
		val targetPos = pos.relative(direction)
		if (level.getBlockState(targetPos).`is`(Blocks.CRAFTING_TABLE)) tickVanillaCraftingTable(pos, state)
		else tickGenericTarget(level, targetPos, direction, state)
	}

	/**
	 * A real, placed vanilla crafting table - the special case for a `CRAFTING`-kind pattern (see
	 * this object's own KDoc). Resolves instantly: a pattern's own [Pattern.outputs] were already
	 * assembled once, at encode time (see `PatternEncoder`), so running it here is just the same
	 * generic extract-inputs/insert-outputs conversion every pattern kind already uses - no live
	 * recipe lookup needed. A `while` loop rather than one run per tick, since "instant" means a
	 * whole buffered batch converts in one go rather than being spread across ticks. The result
	 * lands in [PatternProviderHookState.patternOutputBuffers] (via [outputBufferFor]) since a real
	 * crafting table has no inventory of its own to hold it.
	 */
	private fun tickVanillaCraftingTable(pos: BlockPos, state: PatternProviderHookState) {
		for (index in 0 until state.patterns.size()) {
			val pattern = patternAt(state, index)?.takeIf { it.kind == PatternKind.CRAFTING } ?: continue
			val buffer = state.bufferFor(index)
			val output = state.outputBufferFor(index)
			// A CRAFTING pattern names only kinds a vanilla grid can hold (see PatternEncoder), so
			// every side of it resolves to a storage surface here - one that somehow named anything
			// else could not have been encoded in this kind at all, and is skipped rather than
			// half-run.
			val requiredInputs = pattern.requiredInputs().map { (key, perRun) -> key.resource to perRun }
			val outputs = pattern.outputs.map { it.resource to it.amount }
			val kinds = (requiredInputs + outputs).associate { (resource, _) ->
				ResourceIdentity.of(resource) to ResourceKindRegistry.storageFor(resource)
			}
			if (kinds.values.any { it == null }) continue
			fun kindOf(resource: ResourceComponent) = kinds.getValue(ResourceIdentity.of(resource))!!

			var runs = 0
			while (
				requiredInputs.all { (resource, perRun) -> amountIn(buffer, resource) >= perRun } &&
				outputs.all { (resource, amount) -> kindOf(resource).roomFor(output, resource, amount) >= amount }
			) {
				for ((resource, perRun) in requiredInputs) {
					val taken = kindOf(resource).extract(buffer, resource, perRun, false)
					ResourceTrace.moved(pos, "pattern.consume", resource, perRun, taken, "pattern" to index)
					// The run's inputs are gone but its outputs are not yet in - a partial extract
					// here would leave the pattern half-run with no way to put the rest back.
					ResourceTrace.lost(pos, "pattern.consume", resource, perRun - taken, "input vanished mid-run")
				}
				for ((resource, amount) in outputs) {
					val stored = kindOf(resource).insert(output, resource, amount, false)
					ResourceTrace.moved(pos, "pattern.produce", resource, amount, stored, "pattern" to index)
					// The loop only runs while the output buffer says it has room for a whole
					// output, so this should be unreachable - but the amount is already assembled
					// by the time we find out, and there is nowhere to put it back.
					ResourceTrace.lost(pos, "pattern.produce", resource, amount - stored, "output buffer took less than it promised")
				}
				runs++
			}
			if (runs > 0) ResourceTrace.at(pos, "pattern.convert", "pattern" to index, "runs" to runs)
		}
	}

	/** The single-run-at-a-time behavior for a target with no buffered processing of its own - see this object's own KDoc. */
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
		val stack = state.patterns[index].getItem()
		if (stack.isEmpty) return null
		return PatternItemData(stack).pattern
	}

	/**
	 * Total amount of [resource] currently sitting across every slot of [storage] - a
	 * [CommonStorage] has no direct "how much of X do I hold" query of its own.
	 *
	 * Compared through [ResourceIdentity], not `==`: a resource is not guaranteed value equality
	 * across kinds (see [ResourceIdentity] itself), so a bare comparison silently counts zero for
	 * any kind that lacks it.
	 */
	internal fun amountIn(storage: CommonStorage<*>, resource: ResourceComponent): Long {
		val wanted = ResourceIdentity.of(resource)
		var total = 0L
		for (i in 0 until storage.size()) {
			if (ResourceIdentity.of(storage.getResource(i) as ResourceComponent) == wanted) total += storage.getAmount(i)
		}
		return total
	}

	/**
	 * Whether [pattern]'s own [Pattern.requiredInputs] are all currently sitting in whatever's at
	 * [targetPos] - queried from [direction], the face the target itself was reached through.
	 * Reads the target's combined [earth.terrarium.common_storage_lib.storage.base.CommonStorage.get]/
	 * [earth.terrarium.common_storage_lib.storage.base.CommonStorage.size] view rather than
	 * simulating an [earth.terrarium.common_storage_lib.storage.base.CommonStorage.extract] - a
	 * directed exposure only ever `extract`s from its *output* side, so simulating an extract of
	 * the *input* ingredients sitting in its grid would always read as unavailable.
	 */
	private fun inputsPresent(level: ServerLevel, targetPos: BlockPos, direction: Direction, pattern: Pattern): Boolean {
		val storage = ItemApi.BLOCK.find(level, targetPos, direction) ?: return false
		val totals = HashMap<ResourceIdentity, Long>()
		for (i in 0 until storage.size()) {
			val slot = storage[i]
			if (slot.resource.isBlank) continue
			val key = ResourceIdentity.of(slot.resource)
			totals[key] = (totals[key] ?: 0L) + slot.amount
		}
		for ((key, amount) in pattern.requiredInputs()) {
			if ((totals[key] ?: 0L) < amount) return false
		}
		return true
	}

	override fun asItem(): Item = ItemRegistry.PatternProviderHook
}

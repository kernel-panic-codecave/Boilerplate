package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.archie.serialization.ArchieStorageMap
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackResourceStorageExposer
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PassThroughStorage
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.Direction

/**
 * Holds up to [SLOT_COUNT] [net.kernelpanicsoft.boilerplate.crafting.PatternItem] stacks - see
 * [PatternProviderHookType].
 */
class PatternProviderHookState : HookHolderState(PatternProviderHookType.ID), FallbackItemStorageExposer, FallbackResourceStorageExposer {
	val patterns: ArchieItemStorage by itemField(SLOT_COUNT)

	/**
	 * The face this provider presents: a [PassThroughStorage] reading from [PatternBufferIO] - the
	 * same shape [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState] presents.
	 *
	 * The pass-through is the point. A hook's exposed storage answers *instead* of the face's own
	 * (see [net.kernelpanicsoft.boilerplate.registry.TileRegistry]), and handing back the bare
	 * buffer meant a machine pointed at this face met the pattern's ingredient staging - which
	 * refuses anything that is not one of that pattern's own inputs. A machine's *result* never is,
	 * so it was refused outright and stayed in the machine, stalling the job waiting on it. That
	 * arrangement - provider pushes ingredients in, machine pushes its result back into the same
	 * pipe - is exactly what a Pattern Provider exists to make possible, and what
	 * [PassThroughStorage]'s own KDoc describes.
	 *
	 * The buffer keeps its first refusal on inserts ([PassThroughStorage]'s own `stage`), because
	 * ingredients genuinely do arrive here: a delivery aimed at this hook gates on what this face
	 * reports room for. So what the pattern consumes still stages, and only what it declines - the
	 * result, and anything else a neighbour pushes - goes onto the network. Reads and extractions
	 * reach the buffer unchanged.
	 *
	 * Falls back to the bare buffer when this hook is not actually mounted on [tile]: a pass-through
	 * has no face to route from then, and the read surface is still correct.
	 */
	override fun exposedStorage(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>? {
		// Only items: a pattern's ingredient staging is an item surface, and there is nothing of any
		// other kind here for a face to read. Answering `null` is not a dead end - the face falls
		// back to the plain pass-through every face has (see [exposedStorageFor]), so a machine
		// pushing a fluid or an addon's own chemical at this face still gets it routed.
		if (kind != ResourceKindRegistry.Item) return null
		val buffer = PatternBufferIO(this)
		val face = directionOn(tile) ?: return buffer
		return PassThroughStorage.of(tile, face, kind, backing = buffer, stage = buffer) ?: buffer
	}

	/** [exposedStorage] with the item kind named - total, since that branch never answers `null`. */
	@Suppress("UNCHECKED_CAST")
	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> =
		exposedStorage(tile, ResourceKindRegistry.Item) as? CommonStorage<ItemResource> ?: PatternBufferIO(this)

	/** The [tile] face this hook is attached to, or null if [tile] doesn't actually carry it - hooks are keyed by `Direction.name`. */
	fun directionOn(tile: MultipartBlockEntity): Direction? {
		for ((directionName, hookState) in tile.hooks) if (hookState === this) return Direction.valueOf(directionName)
		return null
	}

	/**
	 * Index into [patterns] the attached target is currently feeding/processing, or `null` if idle
	 * - only meaningful for the generic single-slot target case (a plain inventory that just
	 * processes on its own once fed, a vanilla furnace say). Runtime-only; a reload just re-detects
	 * it from whatever's still sitting in the target.
	 */
	var activeSlot: Int? = null

	/**
	 * Virtual per-pattern-slot input buffer, keyed by [patterns]' own slot index (as a string) -
	 * staged ingredients for a queued/in-flight Crafting CPU job step, isolated from whatever's
	 * actively running (and from every *other* slot's own staged ingredients) instead of sharing
	 * the attached target's own physical grid. [patterns] can hold up to [SLOT_COUNT] entirely
	 * different patterns at once, and more than one of them can genuinely be feeding/running at the
	 * same time - without a buffer *per pattern*, two different patterns needing the exact same
	 * ingredient (planks, say) would have no way to keep "these 2 planks are for sticks" and "these
	 * 3 planks are for the pickaxe itself" apart in one shared grid. [PatternBufferIO] is what a
	 * delivery aimed at this hook actually inserts into; [PatternProviderHookType.tick] atomically
	 * moves a whole run's worth out of the relevant buffer the moment it starts that run.
	 */
	val patternBuffers: ArchieStorageMap<ArchieItemStorage> by itemMapField(Pattern.GRID_SIZE)

	/** [patternBuffers]' own entry for [index] into [patterns], lazily created on first use. */
	fun bufferFor(index: Int): ArchieItemStorage = patternBuffers.getOrPut(index.toString())

	/**
	 * Virtual per-pattern-slot output buffer, keyed the same way [patternBuffers] is - where a
	 * `CRAFTING`-kind pattern's own instantly-assembled result lands (see
	 * [PatternProviderHookType.tickVanillaCraftingTable]), since a real vanilla crafting table has
	 * no inventory of its own to hold one. Exposed externally via [PatternOutputIO]; unused for a
	 * `PROCESSING`-kind pattern, whose target already has a real output of its own.
	 */
	val patternOutputBuffers: ArchieStorageMap<ArchieItemStorage> by itemMapField(Pattern.GRID_SIZE)

	/** [patternOutputBuffers]' own entry for [index] into [patterns], lazily created on first use. */
	fun outputBufferFor(index: Int): ArchieItemStorage = patternOutputBuffers.getOrPut(index.toString())

	/** The actual [Pattern] carried by each non-blank slot in [patterns], in slot order - what [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest]/[net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType] search across reachable pattern providers for. */
	fun heldPatterns(): List<Pattern> =
		(0 until patterns.size()).mapNotNull { i -> patterns[i].getItem().takeIf { !it.isEmpty }?.let { PatternItemData(it).pattern } }

	/** [patterns]' own slot index holding [pattern], or `null` if it isn't currently held at all - the [bufferFor] index a Crafting CPU job step targeting this [pattern] needs to feed directly, rather than the shared, round-robin [PatternBufferIO]. */
	fun indexOfPattern(pattern: Pattern): Int? {
		for (i in 0 until patterns.size()) {
			val stack = patterns.get(i).getItem()
			if (stack.isEmpty) continue
			if (PatternItemData(stack).pattern == pattern) return i
		}
		return null
	}

	companion object {
		const val SLOT_COUNT = 9

		/** How many runs' worth of a single pattern [patternBuffers] holds before refusing more. TODO M5: derive from the target's own pressure capacity - a flat baseline until then. */
		const val MAX_BUFFERED_RUNS_PER_PATTERN = 4
	}
}

/**
 * Bridges [state]'s own [PatternProviderHookState.patternBuffers] into one [CommonStorage],
 * exposed directly on the pattern-provider hook's own block position (see
 * [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]'s registration) - what a
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
 * in their own right (a completed run's real output comes from the target's own real output for a
 * `PROCESSING`-kind pattern, or [PatternOutputIO] for a `CRAFTING`-kind one, instead).
 */
class PatternBufferIO(private val state: PatternProviderHookState) : CommonStorage<ItemResource> {
	override fun size(): Int = state.patterns.size()
	override fun get(index: Int): StorageSlot<ItemResource> = state.bufferFor(index)[0]

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		var madeProgress = true
		while (remaining > 0 && madeProgress) {
			madeProgress = false
			for (index in 0 until state.patterns.size()) {
				if (remaining <= 0) break
				val stack = state.patterns[index].getItem()
				if (stack.isEmpty) continue
				val pattern = PatternItemData(stack).pattern
				if (pattern == Pattern.EMPTY) continue
				val perRun = pattern.requiredInputs()[ResourceIdentity.of(resource)] ?: continue
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

/**
 * Bridges [state]'s own [PatternProviderHookState.patternOutputBuffers] into one [CommonStorage] -
 * the symmetric counterpart to [PatternBufferIO], exposing a `CRAFTING`-kind pattern's own
 * instantly-assembled result (see [PatternProviderHookType.tickVanillaCraftingTable]) as ordinary
 * pullable network stock, the same way a `PROCESSING`-kind pattern's own real target output
 * already is. [insert] always reports nothing accepted - the only way anything lands here is
 * [tickVanillaCraftingTable] itself, direct on [PatternProviderHookState.outputBufferFor].
 */
class PatternOutputIO(private val state: PatternProviderHookState) : CommonStorage<ItemResource> {
	override fun size(): Int = state.patterns.size()
	override fun get(index: Int): StorageSlot<ItemResource> = state.outputBufferFor(index)[0]

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long = 0

	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		for (index in 0 until state.patterns.size()) {
			if (remaining <= 0) break
			remaining -= state.outputBufferFor(index).extract(resource, remaining, simulate)
		}
		return amount - remaining
	}
}

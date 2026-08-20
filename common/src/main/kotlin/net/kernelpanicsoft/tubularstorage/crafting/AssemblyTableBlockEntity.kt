package net.kernelpanicsoft.tubularstorage.crafting

import dev.architectury.registry.menu.ExtendedMenuProvider
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.gui.AssemblyTableMenu
import net.kernelpanicsoft.tubularstorage.power.PressureConsumer
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * A real, pipe-fed block that visibly processes a [Pattern] over time rather than resolving a
 * craft instantly - `docs/design/m4-crafting-automation.md`'s "Physical Assembly Table" decision.
 * Holds no patterns of its own - a [PatternProviderHookType] hook attached to any of its faces owns
 * those (as [PatternItem]s) and decides *what* to run, via [beginProcessing]; this block only knows
 * *how* to run whatever it's told, the same way a vanilla furnace doesn't know its own recipes
 * either. [grid]/[output] are exposed as one combined [ioStorage] (see
 * [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.AssemblyTable]'s
 * [net.kernelpanicsoft.tubularstorage.warehouse.rack.exposeRackStorage] registration) so a
 * Pattern Provider hook (or anything else) can feed/drain it exactly like any other reachable
 * inventory - no special coupling required.
 */
class AssemblyTableBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.AssemblyTable, pos, state), ExtendedMenuProvider, PressureConsumer {

	val grid: ArchieItemStorage by itemField(Pattern.GRID_SIZE)
	val output: ArchieItemStorage by itemField(OUTPUT_SLOTS)

	/** Ticks [activePattern] has been processing for - reset to `0` whenever it stops matching [grid]/[output] (an ingredient pulled back out mid-run genuinely aborts it) or once it completes. */
	private var progressTicks: Double by field(Double.serializer()) { 0.0 }

	/**
	 * The pattern currently being run, or `null` if idle - set only by [beginProcessing], never
	 * scanned for internally. Not NBT-persisted (runtime-only, like
	 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob]'s own identical tradeoff) - a
	 * reload aborts an in-progress run, which the owning Pattern Provider hook simply retries once
	 * it next sees [grid] satisfied again.
	 */
	var activePattern: Pattern? = null
		private set

	/**
	 * Runs currently processing in parallel, fed via a [PatternProviderHookType] hook's own
	 * per-pattern buffer ([net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState.patternBuffers])
	 * rather than [grid] - a genuinely separate pathway from [activePattern]'s single, [grid]-
	 * checked run: ingredients are consumed from the buffer the instant [beginBufferedRun] accepts
	 * a run (an "atomic move-in", not tracked in [grid] at all), so any number of different
	 * patterns can process side by side without contending over shared grid slots the way trying
	 * to run them all through [beginProcessing]/[grid] at once would. Not NBT-persisted, same
	 * runtime-only tradeoff as [activePattern].
	 */
	val activeRuns: MutableList<ActiveRun> = mutableListOf()

	val ioStorage: CommonStorage<ItemResource> = AssemblyTableIO(grid, output)

	override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		AssemblyTableMenu(id, inventory, this)

	override fun getDisplayName(): Component = Component.translatable(Util.makeDescriptionId("container", BuiltInRegistries.BLOCK.getKey(blockState.block)))

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}

	/**
	 * Starts running [pattern] if idle and [grid]/[output] currently satisfy it ([canRun]) - called
	 * externally (a [PatternProviderHookType] hook, once it sees a held pattern's ingredients
	 * present), not decided here. Returns whether it actually started; `false` while already
	 * mid-run or if [pattern] doesn't currently fit, either of which the caller should treat as
	 * "try again later."
	 */
	fun beginProcessing(pattern: Pattern): Boolean {
		if (activePattern != null) return false
		if (!canRun(pattern)) return false
		activePattern = pattern
		progressTicks = 0.0
		return true
	}

	/**
	 * Starts a new parallel [ActiveRun] for [pattern], trusting the caller ([PatternProviderHookType])
	 * already extracted this run's own ingredients from its own buffer - unlike [beginProcessing],
	 * this never touches [grid] and never re-checks anything about [pattern]'s own inputs, since
	 * they're already spent by the time this is called. Returns `false` (and does nothing) once
	 * [activeRuns] is already at [maxParallelRuns] - the caller should treat that as "try again once
	 * something else finishes," the same as a rejected [beginProcessing].
	 */
	fun beginBufferedRun(pattern: Pattern): Boolean {
		if (activeRuns.size >= maxParallelRuns()) return false
		activeRuns += ActiveRun(pattern)
		setChanged()
		return true
	}

	/** How many [activeRuns] can process at once right now. TODO M5: scale with real pressure capacity, once this has one - a flat baseline until then, same as [maxBufferedRunsPerPattern]. */
	fun maxParallelRuns(): Int = BASE_MAX_PARALLEL_RUNS

	/** How many runs' worth of a single pattern [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState.patternBuffers] holds before refusing more. TODO M5: scale with real pressure capacity - a flat baseline until then, same as [maxParallelRuns]. */
	fun maxBufferedRunsPerPattern(): Int = BASE_MAX_BUFFERED_RUNS_PER_PATTERN

	fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return

		val pattern = activePattern
		if (pattern != null) {
			if (!canRun(pattern)) {
				activePattern = null
				progressTicks = 0.0
			} else {
				progressTicks += onPressureTick(NO_PRESSURE_LINE)
				if (progressTicks >= PROCESSING_TIME_TICKS) {
					progressTicks = 0.0
					run(pattern)
					activePattern = null
				}
			}
		}

		val iterator = activeRuns.iterator()
		while (iterator.hasNext()) {
			val activeRun = iterator.next()
			if (!activeRun.completed) {
				activeRun.progressTicks += onPressureTick(NO_PRESSURE_LINE)
				if (activeRun.progressTicks < PROCESSING_TIME_TICKS) continue
				activeRun.completed = true
			}
			// Completed, but output might not have room yet (another run's own product still
			// sitting there, say) - stays in activeRuns, retried next tick, rather than losing the
			// already-consumed ingredients' own product.
			val outputs = activeRun.pattern.outputs
			if (outputs.any { out -> output.insert(out.resource, out.amount, true) < out.amount }) continue
			for (out in outputs) output.insert(out.resource, out.amount, false)
			iterator.remove()
			setChanged()
		}
	}

	/** Whether [grid] currently holds enough of every one of [pattern]'s [Pattern.requiredInputs], and [output] has room for the result - checked every tick rather than cached, since either can change out from under an in-progress run (an ingredient pulled back out, say). */
	private fun canRun(pattern: Pattern): Boolean {
		for ((resource, amount) in pattern.requiredInputs()) {
			if (amountInGrid(resource) < amount) return false
		}
		for (out in pattern.outputs) {
			if (output.insert(out.resource, out.amount, true) < out.amount) return false
		}
		return true
	}

	private fun amountInGrid(resource: ItemResource): Long {
		var total = 0L
		for (i in 0 until grid.size()) if (grid.getResource(i) == resource) total += grid.getAmount(i)
		return total
	}

	private fun run(pattern: Pattern) {
		for ((resource, amount) in pattern.requiredInputs()) grid.extract(resource, amount, false)
		for (out in pattern.outputs) output.insert(out.resource, out.amount, false)
		setChanged()
	}

	override val basePressureCost: Long get() = 0
	override val maxPressureDraw: Long get() = 0

	companion object {
		/** Ticks a matched pattern takes to complete at 1.0x (baseline, unaffected by pressure until M5) speed. */
		const val PROCESSING_TIME_TICKS = 100.0

		/** [output]'s own slot count - more than 1 so parallel runs of different patterns can each land their own product without waiting on each other's still-unclaimed output. */
		private const val OUTPUT_SLOTS = 9

		/** [maxParallelRuns]'s flat baseline until M5 gives pressure scaling a real meaning. */
		private const val BASE_MAX_PARALLEL_RUNS = 3

		/** [maxBufferedRunsPerPattern]'s flat baseline until M5 gives pressure scaling a real meaning. */
		private const val BASE_MAX_BUFFERED_RUNS_PER_PATTERN = 4

		/** Stand-in [ArchieEnergyStorage] for [onPressureTick] calls until M5 gives this block a real one - see [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity]'s identical [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity.pressureSpeedMultiplier]. */
		private val NO_PRESSURE_LINE = ArchieEnergyStorage(0)

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: AssemblyTableBlockEntity) = tile.tick(level, pos, state)
	}
}

/**
 * One parallel run in progress on an [AssemblyTableBlockEntity]'s own [AssemblyTableBlockEntity.activeRuns] -
 * [pattern]'s own ingredients are already spent (extracted from a [PatternProviderHookType] hook's
 * own buffer the instant this was created), so nothing about this run can be aborted the way
 * [AssemblyTableBlockEntity.activePattern] can if [AssemblyTableBlockEntity.grid] stops matching -
 * the only outstanding question left is *when* [progressTicks] finishes and whether
 * [AssemblyTableBlockEntity.output] has room for the result yet.
 */
class ActiveRun(val pattern: Pattern, var progressTicks: Double = 0.0, var completed: Boolean = false)

/**
 * [grid]/[output] combined into one [CommonStorage] for [earth.terrarium.common_storage_lib.item.ItemApi.BLOCK]
 * exposure - [insert] always targets [grid] (what a pipe feeding this table should reach), [extract]
 * always [output] (what a pipe/hopper draining the result should reach), regardless of which slot
 * index either call happens to touch. [get]/[size] still expose *both* (so generic introspection -
 * e.g. a warehouse scan - sees the table's full contents), just not through [insert]/[extract]'s
 * own directed behavior.
 */
private class AssemblyTableIO(private val grid: ArchieItemStorage, private val output: ArchieItemStorage) : CommonStorage<ItemResource> {
	override fun size(): Int = grid.size() + output.size()

	override fun get(index: Int): StorageSlot<ItemResource> =
		if (index < grid.size()) grid.get(index) else output.get(index - grid.size())

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long = grid.insert(resource, amount, simulate)
	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long = output.extract(resource, amount, simulate)
}

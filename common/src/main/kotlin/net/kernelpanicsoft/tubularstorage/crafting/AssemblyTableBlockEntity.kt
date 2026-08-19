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
 * A real, pipe-fed block that both stores encoded [Pattern]s and executes them, rather than
 * resolving a craft instantly - `docs/design/m4-crafting-automation.md`'s "Physical Assembly
 * Table" decision. [grid]/[output] are one shared 3x3-plus-result shape used for both purposes: a
 * player manually filling [grid] (and, for a [PatternKind.PROCESSING] pattern, [output] too) and
 * encoding it via [AssemblyTableMenu.encode] is exactly the same slots [tick] later matches
 * [patterns] against and pipes feed - [ioStorage] exposes [grid] for insertion and [output] for
 * extraction as one combined [CommonStorage] (see [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.AssemblyTable]'s
 * [net.kernelpanicsoft.tubularstorage.warehouse.rack.exposeRackStorage] registration, reused as-is
 * from the rack block types - it's already generic over any [CommonStorage], not rack-specific).
 *
 * A pattern is matched purely by ingredient *count*, not grid position - [PatternKind] only ever
 * mattered for how a pattern was originally encoded (see [PatternEncoder]), not for how it's later
 * run: [Pattern.requiredInputs] already collapses a [PatternKind.CRAFTING] pattern's own positional
 * grid into the same shape a [PatternKind.PROCESSING] one already has.
 */
class AssemblyTableBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.AssemblyTable, pos, state), ExtendedMenuProvider, PressureConsumer {

	val patterns: MutableList<Pattern> by listField(Pattern.serializer()) { emptyList() }
	val grid: ArchieItemStorage by itemField(Pattern.GRID_SIZE)
	val output: ArchieItemStorage by itemField(1)

	/** Ticks the currently-matched pattern has been processing for - reset to `0` whenever nothing in [patterns] currently matches [grid]/[output], so pulling an ingredient out mid-run genuinely aborts it rather than leaving silent progress behind. */
	private var progressTicks: Double by field(Double.serializer()) { 0.0 }

	val ioStorage: CommonStorage<ItemResource> = AssemblyTableIO(grid, output)

	override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		AssemblyTableMenu(id, inventory, this)

	override fun getDisplayName(): Component = Component.translatable(Util.makeDescriptionId("container", BuiltInRegistries.BLOCK.getKey(blockState.block)))

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}

	fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val pattern = patterns.firstOrNull { canRun(it) }
		if (pattern == null) {
			if (progressTicks != 0.0) progressTicks = 0.0
			return
		}

		progressTicks += onPressureTick(NO_PRESSURE_LINE)
		if (progressTicks < PROCESSING_TIME_TICKS) return
		progressTicks = 0.0
		run(pattern)
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

		/** Stand-in [ArchieEnergyStorage] for [onPressureTick] calls until M5 gives this block a real one - see [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity]'s identical [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity.pressureSpeedMultiplier]. */
		private val NO_PRESSURE_LINE = ArchieEnergyStorage(0)

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: AssemblyTableBlockEntity) = tile.tick(level, pos, state)
	}
}

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

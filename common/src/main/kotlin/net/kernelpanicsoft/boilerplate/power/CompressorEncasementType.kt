package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A compressor: burns a fuel item into its own [CompressorEncasementState.pressure], then pushes
 * surplus outward across the pressure network every tick via
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager]'s own equalization
 * pass (this encasement is just another endpoint to it, the same as a tank) - see
 * `docs/design/m5-pressure-power.md`. No GUI yet, matching [PressureTankEncasementType].
 */
object CompressorEncasementType : PipeEncasementType<CompressorEncasementState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "compressor"

	override val id: ResourceLocation get() = ID

	/** Attachable only on a dedicated pressure-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Pressure) }

	override fun createState(): CompressorEncasementState = CompressorEncasementState()

	override fun asItem(): Item = ItemRegistry.CompressorEncasement

	/** The casing's own geometry - see [pressureCasingShape]. */
	override fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: CompressorEncasementState): VoxelShape =
		pressureCasingShape(level, pos, state.formed)

	/** Drives [net.kernelpanicsoft.boilerplate.registry.BlockRegistry.CompressorPart]'s per-face variant off this segment's own connections - see [pressureCasingRenderState]. */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: CompressorEncasementState): BlockState =
		pressureCasingRenderState(BlockRegistry.CompressorPart, level, pos, previousState, attachmentState.formed)

	/** See [refreshPressureMultiblockFormation]. */
	override fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CompressorEncasementState) =
		refreshPressureMultiblockFormation(level, pos, changedPosPresent = true)

	/** See [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.onRemoved]'s identical note on [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]'s own removal-path caveat. */
	override fun onRemoved(level: ServerLevel, pos: BlockPos, state: CompressorEncasementState) =
		refreshPressureMultiblockFormation(level, pos, changedPosPresent = false)

	/**
	 * Furnace-analog burn cycle: once nothing's currently lit, consumes one fuel item from
	 * [CompressorEncasementState.fuel] (vanilla's own [AbstractFurnaceBlockEntity.getFuel] table -
	 * deprecated in favor of a per-loader hook this common module has no cross-platform equivalent
	 * for yet, but still functional for every vanilla fuel), then fills [CompressorEncasementState.pressure]
	 * by [PRESSURE_PER_TICK] every tick for as long as it stays lit. [selfHealPressureMultiblockFormation]
	 * runs first - see its own KDoc for why a periodic reconciliation is needed on top of
	 * [onAttached]/[onRemoved].
	 */
	override fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: CompressorEncasementState) {
		selfHealPressureMultiblockFormation(level, pos, state)
		if (state.burnTicksRemaining <= 0) {
			val slot = state.fuel.get(0)
			if (slot.resource.isBlank || slot.amount <= 0) return
			val burnTime = fuelValues[slot.resource.cachedStack.item] ?: 0
			if (burnTime <= 0) return
			state.fuel.extract(slot.resource, 1, false)
			state.burnTicksRemaining = burnTime
		}
		state.burnTicksRemaining--
		state.pressure.insert(PRESSURE_PER_TICK, false)
	}

	/** Cached rather than re-derived every tick - [AbstractFurnaceBlockEntity.getFuel] rebuilds a fresh map on every call. */
	private val fuelValues by lazy { AbstractFurnaceBlockEntity.getFuel() }

	private const val PRESSURE_PER_TICK = 20L
}

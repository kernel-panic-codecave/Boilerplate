package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock.FaceMode
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.encasement.CasingGeometry
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Shared [PipeEncasementType] base for [CompressorEncasementType]/[PressureTankEncasementType] -
 * both wrap the exact same casing (see [GEOMETRY]) and both are members of the same pressure-
 * multiblock cluster space, differing only in item/texture and in what [tick] does beyond
 * formation self-heal. The casing geometry/shape/render assembly and the formation
 * attach/remove/reconcile plumbing live here once rather than duplicated per type, mirroring
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType]'s own
 * `faceModeFor`/`casingShape`/`getRenderState` members on the crafting side's single type.
 */
abstract class PressureEncasementType<S : EncasementHolderState> : PipeEncasementType<S>() {
	/**
	 * The calling type's own part block instance ([net.kernelpanicsoft.boilerplate.registry.BlockRegistry.CompressorPart]/
	 * [net.kernelpanicsoft.boilerplate.registry.BlockRegistry.PressureTankPart]) - both share
	 * [ConnectingEncasementModelBlock]'s class, so identity, not just type, picks the right
	 * default in [getRenderState].
	 */
	protected abstract val partBlock: Block

	/**
	 * The casing's own geometry - the pressure-side [CasingGeometry] instance, sized to
	 * [PressurePipeBlock]'s narrower 4x4 cross-section (0.375 hole margin) rather than an item
	 * pipe's 6x6 (see [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType]'s own
	 * 0.3125 instance). The margin was kept identical to the crafting buffer's own rather than
	 * shrunk to match, so the casing's outer silhouette against a neighboring block stays the same
	 * either way.
	 */
	private val GEOMETRY = CasingGeometry(0.375)

	/** The casing's own geometry - see [pressureCasingShape]. */
	override fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: S): VoxelShape =
		pressureCasingShape(level, pos, state.formed)

	/** Drives the calling type's own [partBlock]'s per-face variant off this segment's own connections - see [pressureCasingRenderState]. */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: S): BlockState =
		pressureCasingRenderState(partBlock, level, pos, previousState, attachmentState.formed)

	/** See [refreshPressureMultiblockFormation]. */
	override fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: S) =
		refreshPressureMultiblockFormation(level, pos, changedPosPresent = true)

	/** See [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.onRemoved]'s identical note on [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]'s own removal-path caveat. */
	override fun onRemoved(level: ServerLevel, pos: BlockPos, state: S) =
		refreshPressureMultiblockFormation(level, pos, changedPosPresent = false)

	/** Formation self-heal only - a pressure encasement with no active tick work of its own is otherwise passive (see [PressureTankEncasementType]'s own KDoc; [CompressorEncasementType.tick] is the one that does more). */
	override fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: S) =
		selfHealPressureMultiblockFormation(level, pos, state)

	/**
	 * See [casingShape]. [partBlock] is the calling type's own part block instance ([net.kernelpanicsoft.boilerplate.registry.BlockRegistry.CompressorPart]/[net.kernelpanicsoft.boilerplate.registry.BlockRegistry.PressureTankPart]) - both share [ConnectingEncasementModelBlock]'s class, so identity, not just type, picks the right default. [formed] comes from the caller's own synced [EncasementHolderState.formed] rather than being recomputed here, the same reasoning [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.getRenderState] gives for its own identical parameter.
	 */
	private fun pressureCasingRenderState(partBlock: Block, level: Level, pos: BlockPos, previousState: BlockState, formed: Boolean): BlockState {
		val base = if (previousState.block === partBlock) previousState else partBlock.defaultBlockState()
		var state = base.setValue(ConnectingEncasementModelBlock.FORMED, formed)
		for ((direction, property) in ConnectingEncasementModelBlock.FACES) {
			state = state.setValue(property, pressureFaceModeFor(level, pos, direction))
		}
		return state
	}

	/**
	 * The casing's full per-face mode set, shared by [CompressorEncasementType]/
	 * [PressureTankEncasementType] - [FaceMode.ARM] wherever a neighboring segment carries either
	 * kind of pressure encasement (bridging the pair regardless of
	 * [ConnectingEncasementModelBlock.FORMED], since they're physically joined either way),
	 * [FaceMode.NONE] where this segment's own pipe still connects that way (nothing to plug - the
	 * connecting arm/pipe already reads fine there) or where no pipe has been placed here at all
	 * yet (nothing to plug either - the hole stays open until a pipe actually earns a cap),
	 * [FaceMode.CAP] otherwise (a dead end, plugged).
	 */
	private fun pressureCasingShape(level: BlockGetter, pos: BlockPos, formed: Boolean): VoxelShape {
		if (level !is Level) return PipeEncasementType.DEFAULT_CORE_SHAPE
		return GEOMETRY.forFaceModes(ConnectingEncasementModelBlock.FACES.keys.associateWith { pressureFaceModeFor(level, pos, it) }, formed)
	}

	private fun pressureFaceModeFor(level: Level, pos: BlockPos, direction: Direction): FaceMode {
		if (pressureMultiblockMemberAt(level, pos.relative(direction)) != null) return FaceMode.ARM
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity
		if (tile == null || tile.pipeBlockId == MultipartBlockEntity.NONE) return FaceMode.NONE
		if (level.getBlockState(pos).getValue(PipeBlock.propertiesByDirection.getValue(direction))) return FaceMode.NONE
		return FaceMode.CAP
	}
}

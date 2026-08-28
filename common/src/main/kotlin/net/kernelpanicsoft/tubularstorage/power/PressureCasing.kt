package net.kernelpanicsoft.tubularstorage.power

import net.kernelpanicsoft.tubularstorage.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.ConnectingEncasementModelBlock.FaceMode
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.encasement.CasingGeometry
import net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.power.block.PressurePipeBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * [CompressorEncasementType]/[PressureTankEncasementType]'s shared casing geometry - the pressure-
 * side [CasingGeometry] instance, sized to [PressurePipeBlock]'s narrower 4x4 cross-section (0.375
 * hole margin) rather than an item pipe's 6x6 (see
 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType]'s own 0.3125 instance).
 * The margin was kept identical to the crafting buffer's own rather than shrunk to match, so the
 * casing's outer silhouette against a neighboring block stays the same either way.
 */
private val GEOMETRY = CasingGeometry(0.375)

/**
 * Shared [PipeEncasementType.casingShape]/[PipeEncasementType.getRenderState] logic for
 * [CompressorEncasementType]/[PressureTankEncasementType] - both wrap the exact same casing (see
 * [GEOMETRY]), differing only in item/texture, so the face-mode derivation and render-state assembly
 * live here once rather than duplicated per type. Mirrors
 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType.faceModeFor]/`casingShape`/`getRenderState`
 * exactly: [FaceMode.ARM] wherever a neighboring segment carries either kind of pressure
 * encasement (bridging the pair regardless of [ConnectingEncasementModelBlock.FORMED], since
 * they're physically joined either way), [FaceMode.NONE] where this segment's own pipe still
 * connects that way (nothing to plug - the connecting arm/pipe already reads fine there) or where
 * no pipe has been placed here at all yet (nothing to plug either - the hole stays open until a
 * pipe actually earns a cap), [FaceMode.CAP] otherwise (a dead end, plugged).
 */
internal fun pressureCasingShape(level: BlockGetter, pos: BlockPos, formed: Boolean): VoxelShape {
	if (level !is Level) return PipeEncasementType.DEFAULT_CORE_SHAPE
	return GEOMETRY.forFaceModes(ConnectingEncasementModelBlock.FACES.keys.associateWith { pressureFaceModeFor(level, pos, it) }, formed)
}

/** See [pressureCasingShape]. [partBlock] is the calling type's own part block instance ([net.kernelpanicsoft.tubularstorage.registry.BlockRegistry.CompressorPart]/[net.kernelpanicsoft.tubularstorage.registry.BlockRegistry.PressureTankPart]) - both share [ConnectingEncasementModelBlock]'s class, so identity, not just type, picks the right default. [formed] comes from the caller's own synced [net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState.formed] rather than being recomputed here, the same reasoning [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType.getRenderState] gives for its own identical parameter. */
internal fun pressureCasingRenderState(partBlock: Block, level: Level, pos: BlockPos, previousState: BlockState, formed: Boolean): BlockState {
	val base = if (previousState.block === partBlock) previousState else partBlock.defaultBlockState()
	var state = base.setValue(ConnectingEncasementModelBlock.FORMED, formed)
	for ((direction, property) in ConnectingEncasementModelBlock.FACES) {
		state = state.setValue(property, pressureFaceModeFor(level, pos, direction))
	}
	return state
}

private fun pressureFaceModeFor(level: Level, pos: BlockPos, direction: Direction): FaceMode {
	if (pressureMultiblockMemberAt(level, pos.relative(direction)) != null) return FaceMode.ARM
	val tile = level.getBlockEntity(pos) as? MultipartBlockEntity
	if (tile == null || tile.pipeBlockId == MultipartBlockEntity.NONE) return FaceMode.NONE
	if (level.getBlockState(pos).getValue(PipeBlock.propertiesByDirection.getValue(direction))) return FaceMode.NONE
	return FaceMode.CAP
}

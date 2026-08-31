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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A pressure tank: a passive [PressureTankEncasementState.pressure] buffer, one endpoint on the
 * pressure network - see `docs/design/m5-pressure-power.md`. No GUI yet (a fill-level display
 * lands with the rest of M5's polish pass) - right-clicking without a wrench does nothing.
 */
object PressureTankEncasementType : PipeEncasementType<PressureTankEncasementState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "pressure_tank"

	override val id: ResourceLocation get() = ID

	/** Attachable only on a dedicated pressure-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Pressure) }

	override fun createState(): PressureTankEncasementState = PressureTankEncasementState()

	override fun asItem(): Item = ItemRegistry.PressureTankEncasement

	/** The casing's own geometry - see [pressureCasingShape]. */
	override fun casingShape(level: BlockGetter, pos: BlockPos, tile: MultipartBlockEntity?, state: PressureTankEncasementState): VoxelShape =
		pressureCasingShape(level, pos, state.formed)

	/** Drives [net.kernelpanicsoft.boilerplate.registry.BlockRegistry.PressureTankPart]'s per-face variant off this segment's own connections - see [pressureCasingRenderState]. */
	override fun getRenderState(level: Level, pos: BlockPos, previousState: BlockState, attachmentState: PressureTankEncasementState): BlockState =
		pressureCasingRenderState(BlockRegistry.PressureTankPart, level, pos, previousState, attachmentState.formed)

	/** See [refreshPressureMultiblockFormation]. */
	override fun onAttached(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: PressureTankEncasementState) =
		refreshPressureMultiblockFormation(level, pos, changedPosPresent = true)

	/** See [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType.onRemoved]'s identical note on [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]'s own removal-path caveat. */
	override fun onRemoved(level: ServerLevel, pos: BlockPos, state: PressureTankEncasementState) =
		refreshPressureMultiblockFormation(level, pos, changedPosPresent = false)

	/** Formation self-heal only - a tank is otherwise passive (see the class KDoc). */
	override fun tick(level: ServerLevel, pos: BlockPos, tile: MultipartBlockEntity, state: PressureTankEncasementState) =
		selfHealPressureMultiblockFormation(level, pos, state)
}

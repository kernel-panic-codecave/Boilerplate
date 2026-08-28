package net.kernelpanicsoft.boilerplate.power.block

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.power.PressureApi
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A brass/rivet-themed pipe segment carrying nothing of its own yet - see
 * `docs/design/m5-pressure-power.md`. A thin [PipeBlock] subclass: promotion
 * ([PipeBlock.useItemOn]), the wrench-detach global listener
 * ([net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.register]), and every shape/
 * attachment-highlight query are all inherited unchanged - a promoted pressure segment is just
 * [net.kernelpanicsoft.boilerplate.registry.BlockRegistry.Multipart], the same block a promoted
 * item segment uses (see [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity]).
 * Only the three things that actually differ from a plain item pipe are overridden below: its
 * [primaryNetworkType], its narrower 4x4 cross-section, and which capability it auto-connects to
 * externally ([PressureApi] rather than `ItemApi` - deliberately not
 * [earth.terrarium.common_storage_lib.energy.EnergyApi]/RF/FE, see that object's own KDoc).
 */
class PressurePipeBlock(properties: Properties) : PipeBlock(properties) {
	override fun codec(): MapCodec<PressurePipeBlock> = CODEC

	override val primaryNetworkType: NetworkType get() = NetworkTypeRegistry.Pressure

	/**
	 * A slimmer 4x4 cross-section - deliberately narrower than item pipes' own 6x6 core (see
	 * [PipeBlock.coreShape]), the whole reason a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.AdapterHookType] hook exists: bridging the two
	 * sizes where an item pipe run meets a pressure-only branch.
	 */
	override val coreShape: VoxelShape get() = CORE_SHAPE

	override val armShapesByDirection: Map<Direction, VoxelShape> get() = armShapes

	override fun externalConnectionExists(level: Level, pos: BlockPos, direction: Direction): Boolean =
		PressureApi.find(level, pos, direction) != null

	/** [PipeBlock]'s own overrides both hardcode [TileRegistry.Pipe] - this pipe type registers under [TileRegistry.PressurePipe] instead, so both need re-overriding here rather than inheriting. */
	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.PressurePipe.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.PressurePipe, PipeBlockEntity::tick)

	companion object {
		val CODEC: MapCodec<PressurePipeBlock> = simpleCodec(::PressurePipeBlock)

		val CORE_SHAPE: VoxelShape = Shapes.box(0.375, 0.375, 0.375, 0.625, 0.625, 0.625)

		val armShapes: Map<Direction, VoxelShape> = mapOf(
			Direction.NORTH to Shapes.box(0.375, 0.375, 0.0, 0.625, 0.625, 0.375),
			Direction.SOUTH to Shapes.box(0.375, 0.375, 0.625, 0.625, 0.625, 1.0),
			Direction.WEST to Shapes.box(0.0, 0.375, 0.375, 0.375, 0.625, 0.625),
			Direction.EAST to Shapes.box(0.625, 0.375, 0.375, 1.0, 0.625, 0.625),
			Direction.DOWN to Shapes.box(0.375, 0.0, 0.375, 0.625, 0.375, 0.625),
			Direction.UP to Shapes.box(0.375, 0.625, 0.375, 0.625, 1.0, 0.625),
		)
	}
}

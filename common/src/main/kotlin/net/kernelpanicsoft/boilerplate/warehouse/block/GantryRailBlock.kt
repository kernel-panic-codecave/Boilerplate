package net.kernelpanicsoft.boilerplate.warehouse.block

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.boilerplate.util.byDirection
import net.kernelpanicsoft.boilerplate.util.invoke
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The warehouse gantry's static perimeter frame - real, solid blocks auto-placed in a hollow
 * rectangle around a bound volume's rail height when it's bound (and removed on rebind/unbind),
 * the same way BuildCraft's Quarry built its own frame (see `docs/design/m3-warehouse-storage.md`,
 * decision #3). A six-way connecting block, the same shape as
 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock] - joins into a continuous frame/track
 * rather than a row of separate cubes - but not actually a pipe: carries no items, isn't part of
 * any [net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager] network, and only
 * connects to its own kind. The *moving* parts (the crossbeams that track the gantry head, the
 * head itself) stay a client-only dynamic render instead of real blocks, since those change every
 * tick and placing/breaking a real block that often isn't practical.
 */
class GantryRailBlock(properties: Properties) : Block(properties) {
	init {
		registerDefaultState(propertiesByDirection.values.fold(stateDefinition.any()) { state, property -> state.setValue(property, false) })
	}

	override fun codec(): MapCodec<out Block> = CODEC

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		propertiesByDirection.values.forEach { builder.add(it) }
	}

	override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
		computeConnections(defaultBlockState(), context.level, context.clickedPos)

	override fun updateShape(
		state: BlockState,
		direction: Direction,
		neighborState: BlockState,
		level: LevelAccessor,
		pos: BlockPos,
		neighborPos: BlockPos,
	): BlockState = state.setValue(propertiesByDirection.getValue(direction), neighborState.block is GantryRailBlock)

	fun computeConnections(state: BlockState, level: LevelAccessor, pos: BlockPos): BlockState =
		Direction.entries.fold(state) { result, direction ->
			result.setValue(propertiesByDirection.getValue(direction), level.getBlockState(pos.relative(direction)).block.let { it is GantryRailBlock || (direction == Direction.DOWN && it !is AirBlock) })
		}

	override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = CORE_SHAPE {
		for ((direction, property) in propertiesByDirection) {
			if (state.getValue(property)) or(ARM_SHAPES.getValue(direction))
		}
	}

	companion object {
		val CODEC: MapCodec<GantryRailBlock> = simpleCodec(::GantryRailBlock)

		val propertiesByDirection: Map<Direction, BooleanProperty> = mapOf(
			Direction.NORTH to BlockStateProperties.NORTH,
			Direction.SOUTH to BlockStateProperties.SOUTH,
			Direction.EAST to BlockStateProperties.EAST,
			Direction.WEST to BlockStateProperties.WEST,
			Direction.UP to BlockStateProperties.UP,
			Direction.DOWN to BlockStateProperties.DOWN,
		)

		val CORE_SHAPE: VoxelShape = Shapes.box(0.3125, 0.3125, 0.3125, 0.6875, 0.6875, 0.6875)

		val ARM_SHAPES: Map<Direction, VoxelShape> = Shapes.box(0.3125, 0.3125, 0.0, 0.6875, 0.6875, 0.3125).byDirection
	}
}

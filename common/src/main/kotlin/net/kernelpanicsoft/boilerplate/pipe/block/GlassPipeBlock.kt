package net.kernelpanicsoft.boilerplate.pipe.block

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty

/**
 * A pipe segment rendered translucent, whose traveling items are visible in transit - see
 * [net.kernelpanicsoft.boilerplate.pipe.client.GlassPipeVisual]. Mechanically
 * identical to a plain [PipeBlock] (same connections/shape/network participation); only its own
 * [TileRegistry.GlassPipe] block entity type and blockstate/models differ, so that renderer never
 * runs for the far more common opaque tier.
 */
class GlassPipeBlock(properties: Properties) : PipeBlock(properties) {

	init
	{
		registerDefaultState(
			defaultBlockState()
				.setValue(straightProperty, false)
				.setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
		)
	}

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>)
	{
		super.createBlockStateDefinition(builder)
		builder.add(straightProperty, BlockStateProperties.AXIS)
	}

	override fun getStateForPlacement(context: BlockPlaceContext): BlockState = computeAxis(super.getStateForPlacement(context))

	override fun updateShape(
		state: BlockState,
		direction: Direction,
		neighborState: BlockState,
		level: LevelAccessor,
		pos: BlockPos,
		neighborPos: BlockPos
	): BlockState = computeAxis(super.updateShape(state, direction, neighborState, level, pos, neighborPos))


	private fun computeAxis(state: BlockState): BlockState
	{
		var ret = state
		var straight = false
		for ((axis, props) in propertiesByAxis)
		{
			val found = Direction.entries
				.filterNot { it == props.first.key || it == props.second.key }
				.fold(state.getValue(props.first.value) &&  state.getValue(props.second.value))
				{ acc, direction -> acc && !state.getValue(propertiesByDirection[direction]!!)}
			if (found)
			{
				straight = true
				ret = ret.setValue(BlockStateProperties.AXIS, axis)
			}
		}
		ret = ret.setValue(straightProperty, straight)
		return ret
	}

	override val showsTravelingItems: Boolean = true
	override val isTranslucent: Boolean = true

	override fun codec(): MapCodec<out PipeBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.GlassPipe.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.GlassPipe, PipeBlockEntity::tick)

	companion object {
		val CODEC: MapCodec<GlassPipeBlock> = simpleCodec(::GlassPipeBlock)

		val straightProperty: BooleanProperty = BooleanProperty.create("straight")
		val propertiesByAxis = propertiesByDirection.entries.groupBy { it.key.axis }.mapValues { (_, props) ->
			val (first, second) = props
			first to second
		}
	}
}

package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.item.HookItem
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A plain pipe segment: transports items through [PipeBlockEntity], auto-connecting to
 * neighboring pipes and inventories. Carries no hooks - see [HookBlock] for the (heavier,
 * hook-carrying) variant this promotes into the moment a [HookItem] is used against it.
 */
open class PipeBlock(properties: Properties) : BaseEntityBlock(properties) {

	/**
	 * Whether this pipe type's contents are visible in transit - see
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.TravelingItemRenderer]. False for the plain
	 * (opaque) tier; [GlassPipeBlock] overrides it. Consulted by
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer] too, off whatever
	 * pipe type a [HookBlock] was promoted from, so a promoted glass pipe keeps showing its
	 * contents and a promoted opaque one doesn't - the trait belongs to the pipe type, not to
	 * whether a hook happens to be attached.
	 */
	open val showsTravelingItems: Boolean = false

	/**
	 * Whether this pipe type's body should render translucent rather than solid. False for the
	 * plain (opaque) tier; [GlassPipeBlock] overrides it. A plain `Boolean`, not a
	 * `net.minecraft.client.renderer.RenderType`, deliberately - that type is client-only, and this
	 * class is loaded on a dedicated server too; [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer]
	 * (client-only itself) is what actually maps this to a real `RenderType` for a [HookBlock]
	 * promoted from this pipe type, exactly like [showsTravelingItems] above.
	 */
	open val isTranslucent: Boolean = false

	init {
		registerDefaultState(propertiesByDirection.values.fold(stateDefinition.any()) { state, property -> state.setValue(property, false) })
	}

	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

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
	): BlockState = state.setValue(propertiesByDirection.getValue(direction), canConnect(level, pos, direction))

	private fun computeConnections(state: BlockState, level: LevelAccessor, pos: BlockPos): BlockState =
		Direction.entries.fold(state) { result, direction ->
			result.setValue(propertiesByDirection.getValue(direction), canConnect(level, pos, direction))
		}

	private fun canConnect(level: LevelAccessor, pos: BlockPos, direction: Direction): Boolean {
		val neighborPos = pos.relative(direction)
		if (PipeRouter.isPipe(level, neighborPos)) return true
		val realLevel = level as? Level ?: return false
		return ItemApi.BLOCK.find(realLevel, neighborPos, direction.opposite) != null
	}

	override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
		var shape = CORE_SHAPE
		for ((direction, property) in propertiesByDirection) {
			if (state.getValue(property)) shape = Shapes.or(shape, armShapes.getValue(direction))
		}
		return shape
	}

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.Pipe.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.Pipe, PipeBlockEntity::tick)

	override fun isPathfindable(state: BlockState, pathComputationType: PathComputationType): Boolean = false

	/**
	 * Right-clicking a plain pipe with a [HookItem] promotes it into a [HookBlock] carrying the
	 * same connections, transplanting this block entity's data across via [CompoundTag] (not
	 * vanilla's "with metadata" API, to keep this independent of Archie's own persistence format),
	 * then delegates to [HookBlock.useItemOn] to actually attach the hook.
	 */
	override fun useItemOn(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult {
		if (stack.item !is HookItem) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		if (level.isClientSide) return ItemInteractionResult.SUCCESS
		val oldTile = level.getBlockEntity(pos) as? PipeBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

		val tag = CompoundTag()
		oldTile.saveToTag(tag)

		val hookState = propertiesByDirection.values.fold(BlockRegistry.Hook.defaultBlockState()) { result, property ->
			result.setValue(property, state.getValue(property))
		}
		level.setBlock(pos, hookState, Block.UPDATE_CLIENTS)
		val newTile = level.getBlockEntity(pos) as? HookBlockEntity ?: return ItemInteractionResult.SUCCESS
		newTile.loadFromTag(tag)
		newTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(this)

		return BlockRegistry.Hook.clickBlockWithItem(stack, level.getBlockState(pos), level, pos, player, hand, hitResult)
	}


	companion object {

		val CODEC: MapCodec<PipeBlock> = simpleCodec(::PipeBlock)

		val propertiesByDirection: Map<Direction, BooleanProperty> = mapOf(
			Direction.NORTH to BlockStateProperties.NORTH,
			Direction.SOUTH to BlockStateProperties.SOUTH,
			Direction.EAST to BlockStateProperties.EAST,
			Direction.WEST to BlockStateProperties.WEST,
			Direction.UP to BlockStateProperties.UP,
			Direction.DOWN to BlockStateProperties.DOWN,
		)

		/** 6x6 (`0.3125..0.6875`, pixels 5-11), matching the pipe model's own core cross-section - same numbers as [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock.CORE_SHAPE], which uses an identical connecting-block shape. */
		val CORE_SHAPE: VoxelShape = Shapes.box(0.3125, 0.3125, 0.3125, 0.6875, 0.6875, 0.6875)

		/** Same 6x6 cross-section as [CORE_SHAPE], reaching from each face to the core's own boundary - see [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock.armShapes]. */
		val armShapes: Map<Direction, VoxelShape> = mapOf(
			Direction.NORTH to Shapes.box(0.3125, 0.3125, 0.0, 0.6875, 0.6875, 0.3125),
			Direction.SOUTH to Shapes.box(0.3125, 0.3125, 0.6875, 0.6875, 0.6875, 1.0),
			Direction.WEST to Shapes.box(0.0, 0.3125, 0.3125, 0.3125, 0.6875, 0.6875),
			Direction.EAST to Shapes.box(0.6875, 0.3125, 0.3125, 1.0, 0.6875, 0.6875),
			Direction.DOWN to Shapes.box(0.3125, 0.0, 0.3125, 0.6875, 0.3125, 0.6875),
			Direction.UP to Shapes.box(0.3125, 0.6875, 0.3125, 0.6875, 1.0, 0.6875),
		)
	}
}

package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import dev.architectury.registry.menu.MenuRegistry
import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookState
import net.kernelpanicsoft.tubularstorage.pipe.item.HookItem
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
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
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** A pipe segment: transports items through [PipeBlockEntity], auto-connecting to neighboring pipes and inventories. */
open class PipeBlock(properties: Properties) : BaseEntityBlock(properties) {

	init {
		registerDefaultState(
			(propertiesByDirection.values + hookPropertiesByDirection.values).fold(stateDefinition.any()) { state, property ->
				state.setValue(property, false)
			}
		)
	}

	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		propertiesByDirection.values.forEach { builder.add(it) }
		hookPropertiesByDirection.values.forEach { builder.add(it) }
	}

	override fun getStateForPlacement(context: net.minecraft.world.item.context.BlockPlaceContext): BlockState =
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
		if (level.getBlockState(neighborPos).block is PipeBlock) return true
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

	/** Right-clicking a face with a [HookItem] attaches that hook to it (see `docs/design/m1-pipe-network.md`), unless that face already carries one. */
	override fun useItemOn(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult {
		val hookItem = stack.item as? HookItem ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		if (level.isClientSide) return ItemInteractionResult.SUCCESS
		val tile = level.getBlockEntity(pos) as? PipeBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		val direction = armFor(state, pos, hitResult) ?: hitResult.direction
		if (tile.hooks.containsKey(direction.name)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

		tile.hooks[direction.name] = HookState(type = hookItem.hookId)
		level.setBlock(pos, state.setValue(hookPropertiesByDirection.getValue(direction), true), Block.UPDATE_CLIENTS)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
		level.playSound(null, pos, state.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
		if (!player.abilities.instabuild) stack.shrink(1)
		return ItemInteractionResult.SUCCESS
	}

	/** Empty-hand right-click on a hooked face opens that hook's GUI (if it has one), or - while sneaking - removes it. */
	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? PipeBlockEntity ?: return InteractionResult.PASS
		val direction = armFor(state, pos, hitResult) ?: hitResult.direction
		val hookState = tile.hooks[direction.name] ?: return InteractionResult.PASS
		val hookType = HookTypeRegistry.byId(hookState.type) ?: return InteractionResult.PASS

		if (!level.isClientSide) {
			if (player.isShiftKeyDown) {
				tile.hooks.remove(direction.name)
				level.setBlock(pos, state.setValue(hookPropertiesByDirection.getValue(direction), false), Block.UPDATE_CLIENTS)
				level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
				level.playSound(null, pos, state.soundType.breakSound, SoundSource.BLOCKS, 1f, 1f)
			} else if (hookType.hasMenu) {
				tile.pendingMenuFace = direction
				MenuRegistry.openExtendedMenu(player as ServerPlayer, tile)
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide)
	}

	/**
	 * Which face a click actually targets: [BlockHitResult.getDirection] is the literal geometric
	 * face normal, which for a connected face's arm is only correct if you click the arm's outward
	 * tip - clicking one of its (narrower-than-a-full-face) lateral sides reports that side's own
	 * direction instead, e.g. the *east* side of a *north*-pointing arm reports EAST. Resolves the
	 * hit point back to whichever connected arm's own shape it actually landed in, if any.
	 */
	private fun armFor(state: BlockState, pos: BlockPos, hitResult: BlockHitResult): Direction? {
		val local = hitResult.location.subtract(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
		return propertiesByDirection.entries
			.firstOrNull { (direction, property) -> state.getValue(property) && armShapes.getValue(direction).bounds().contains(local.x, local.y, local.z) }
			?.key
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

		/** Whether a hook is attached to each face - kept as real blockstate (not just block-entity `hooks`) so the multipart model can skip a plain arm where a hook would clip into it. */
		val hookPropertiesByDirection: Map<Direction, BooleanProperty> = mapOf(
			Direction.NORTH to BooleanProperty.create("hook_north"),
			Direction.SOUTH to BooleanProperty.create("hook_south"),
			Direction.EAST to BooleanProperty.create("hook_east"),
			Direction.WEST to BooleanProperty.create("hook_west"),
			Direction.UP to BooleanProperty.create("hook_up"),
			Direction.DOWN to BooleanProperty.create("hook_down"),
		)

		private val CORE_SHAPE: VoxelShape = Shapes.box(0.375, 0.375, 0.375, 0.625, 0.625, 0.625)

		private val armShapes: Map<Direction, VoxelShape> = mapOf(
			Direction.NORTH to Shapes.box(0.375, 0.375, 0.0, 0.625, 0.625, 0.375),
			Direction.SOUTH to Shapes.box(0.375, 0.375, 0.625, 0.625, 0.625, 1.0),
			Direction.WEST to Shapes.box(0.0, 0.375, 0.375, 0.375, 0.625, 0.625),
			Direction.EAST to Shapes.box(0.625, 0.375, 0.375, 1.0, 0.625, 0.625),
			Direction.DOWN to Shapes.box(0.375, 0.0, 0.375, 0.625, 0.375, 0.625),
			Direction.UP to Shapes.box(0.375, 0.625, 0.375, 0.625, 1.0, 0.625),
		)
	}
}

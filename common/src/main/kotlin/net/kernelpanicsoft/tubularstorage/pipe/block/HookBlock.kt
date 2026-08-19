package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import dev.architectury.registry.menu.MenuRegistry
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.item.HookItem
import net.kernelpanicsoft.tubularstorage.pipe.item.PipeItem
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A pipe segment that can additionally carry a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] on each face - see
 * `docs/design/m1-pipe-network.md`. A plain [PipeBlock] promotes into this the moment a
 * [HookItem] is first used against it (see [PipeBlock.useItemOn]); a [HookItem] used against air
 * places one of these directly. Kept as its own block/entity pair rather than folded onto every
 * pipe: hooks bring six always-allocated 9-slot filter grids plus a synced map that a plain pipe
 * (the overwhelming majority of a build) shouldn't pay for.
 *
 * Carries no blockstate of its own beyond [propertiesByDirection] (connections): which faces have
 * a hook is read straight off [HookBlockEntity.hooks], not mirrored into blockstate, since a hook
 * only ever attaches where a connection already exists - collision (inherited [getShape]) already
 * covers it, and [RenderShape.INVISIBLE] hands *all* visuals (pipe body and hooks alike) to
 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer], which draws the
 * pipe body as [HookBlockEntity.pipeBlockId]'s own baked model rather than a hardcoded one, so a
 * promoted pipe keeps looking like whatever pipe type it actually is.
 */
class HookBlock(properties: Properties) : PipeBlock(properties) {

	override fun codec(): MapCodec<out PipeBlock> = CODEC

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.Hook.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.Hook, HookBlockEntity::tick)



	/** Right-clicking a face with a [HookItem] attaches that hook to it, unless that face already carries one. */
	override fun useItemOn(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult = clickBlockWithItem(stack, state, level, pos, player, hand, hitResult)

	fun clickBlockWithItem(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult {

		val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		val hookItem = stack.item as? HookItem
		val pipeItem = stack.item as? PipeItem
		if (pipeItem != null)
		{
			if (tile.pipeBlockId != HookBlockEntity.NONE) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(pipeItem.block)
		}
		else if (hookItem != null)
		{
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			val direction = armFor(level, state, pos, hitResult) ?: hitResult.direction
			if (tile.hooks.containsKey(direction.name)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val hookType = HookTypeRegistry.byId(hookItem.hookId) ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			tile.hooks.getOrPut(direction.name) { hookType.createState() }
		}
		else return if (stack.item is BlockItem) ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION else ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
		state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)
		level.playSound(null, pos, state.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
		if (!player.abilities.instabuild) stack.shrink(1)
		return ItemInteractionResult.SUCCESS
	}

	/** Empty-hand right-click on a hooked face opens that hook's GUI (if it has one), or - while sneaking - removes it. */
	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return InteractionResult.PASS
		val direction = armFor(level, state, pos, hitResult) ?: hitResult.direction
		val hookState = tile.hooks[direction.name] as? HookHolderState ?: return InteractionResult.PASS
		val hookType = HookTypeRegistry.byId(hookState.type) ?: return InteractionResult.PASS

		if (!level.isClientSide) {
			if (player.isShiftKeyDown) {
				var newState = state
				if (tile.hooks.remove(direction.name) != null) {
					if (!player.abilities.instabuild) player.addItem(hookType.asItem().defaultInstance)
				}

				if (!tile.hooks.iterator().hasNext()) {
					if (tile.pipeBlockId != HookBlockEntity.NONE)
					{
						val tag = CompoundTag()
						tile.saveToTag(tag)

						val pipeState = propertiesByDirection.values.fold(
							BuiltInRegistries.BLOCK.get(tile.pipeBlockId).defaultBlockState()
						) { result, property ->
							result.setValue(property, state.getValue(property))
						}.updateShape(direction, level.getBlockState(pos.relative(direction)), level, pos, pos.relative(direction))
						level.setBlock(pos, pipeState, UPDATE_ALL)
						val newTile =
							level.getBlockEntity(pos) as? PipeBlockEntity ?: return InteractionResult.SUCCESS
						newTile.loadFromTag(tag)
						newState = pipeState
					}
					else
					{
						newState = Blocks.AIR.defaultBlockState()
						level.setBlock(pos, newState, UPDATE_ALL)
					}
				}
				level.sendBlockUpdated(pos, state, newState, UPDATE_ALL)
				state.updateNeighbourShapes(level, pos, UPDATE_ALL)
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
	 *
	 * A click lands exactly on the clicked face's plane, i.e. exactly on one of the arm shape's own
	 * bounds - [net.minecraft.world.phys.AABB.contains] is exclusive on the *max* bound, so it
	 * silently rejects a hit on an arm's south/east/up-facing side while accepting one on its
	 * north/west/down-facing side. [containsInclusive] checks both bounds inclusively (with a small
	 * epsilon for floating-point slop in the hit point itself) so every side matches consistently.
	 */
	private fun armFor(level: Level, state: BlockState, pos: BlockPos, hitResult: BlockHitResult): Direction? {
		val tile = level.getBlockEntity(pos) as? HookBlockEntity
		val local = hitResult.location.subtract(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
		return propertiesByDirection.entries
			.firstOrNull { (direction, property) -> (state.getValue(property) || tile?.hooks?.containsKey(direction.name) ?: false) && containsInclusive(armShapes.getValue(direction).bounds(), local.x, local.y, local.z) }
			?.key
	}

	private fun containsInclusive(bounds: AABB, x: Double, y: Double, z: Double): Boolean =
		x >= bounds.minX - EPSILON && x <= bounds.maxX + EPSILON &&
				y >= bounds.minY - EPSILON && y <= bounds.maxY + EPSILON &&
				z >= bounds.minZ - EPSILON && z <= bounds.maxZ + EPSILON

	override fun getShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos,
		context: CollisionContext
	): VoxelShape
	{
		val shape = super.getShape(state, level, pos, context)
		val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return shape
		val hooksShape = tile.hooks.fold(Shapes.empty()) { shape, entry ->
			val hookState = entry.value as HookHolderState
			val hookType = HookTypeRegistry.byId(hookState.type) ?: return@fold shape
			val direction = Direction.valueOf(entry.key)
			Shapes.or(shape, hookType.shapesByDirection.getValue(direction))
		}
		if (tile.pipeBlockId != HookBlockEntity.NONE) return Shapes.or(shape, hooksShape)
		return hooksShape
	}

	companion object {
		val CODEC: MapCodec<HookBlock> = simpleCodec(::HookBlock)

		private const val EPSILON = 1.0E-5
	}
}

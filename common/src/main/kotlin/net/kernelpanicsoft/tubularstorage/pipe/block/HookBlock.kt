package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import dev.architectury.registry.menu.MenuRegistry
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.item.HookItem
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
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
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
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
		val pipeBlock = (stack.item as? BlockItem)?.block as? PipeBlock
		if (pipeBlock != null && pipeBlock !is HookBlock)
		{
			if (tile.pipeBlockId != HookBlockEntity.NONE) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(pipeBlock)
		}
		else if (hookItem != null)
		{
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			val direction = armFor(state, pos, hitResult) ?: hitResult.direction
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
		val direction = armFor(state, pos, hitResult) ?: hitResult.direction
		val hookState = tile.hooks[direction.name] as? HookHolderState ?: return InteractionResult.PASS
		val hookType = HookTypeRegistry.byId(hookState.type) ?: return InteractionResult.PASS

		if (!level.isClientSide) {
			if (player.isShiftKeyDown) {
				tile.hooks.remove(direction.name)
				level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
				state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)
				level.playSound(null, pos, state.soundType.breakSound, SoundSource.BLOCKS, 1f, 1f)
			} else if (hookType.hasMenu) {
				tile.pendingMenuFace = direction
				MenuRegistry.openExtendedMenu(player as ServerPlayer, tile)
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide)
	}

	override fun getShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos,
		context: CollisionContext
	): VoxelShape
	{
		val shape = super.getShape(state, level, pos, context)
		val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return shape
		val hooksShape = tile.hooks.fold(Shapes.empty()) { shape, entry -> Shapes.or(shape, armShapes[Direction.valueOf(entry.key)]!!)}
		if (tile.pipeBlockId != HookBlockEntity.NONE) return Shapes.or(shape, hooksShape)
		return hooksShape
	}

	companion object {
		val CODEC: MapCodec<HookBlock> = simpleCodec(::HookBlock)
	}
}

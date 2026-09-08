package net.kernelpanicsoft.boilerplate.warehouse.rack

import dev.architectury.registry.menu.ExtendedMenuProvider
import dev.architectury.registry.menu.MenuRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Shared shape for [GeneralRackBlock]/[BulkRackBlock]/[UnstackableRackBlock] - a plain, always
 * player-placeable cube (see [net.kernelpanicsoft.boilerplate.datagen.BoilerplateBlockStateProvider]
 * for its `cubeAll` model) whose only direct interaction is [useWithoutItem] echoing its
 * [RackBlockEntity.describeContents] to the action bar; real reads/writes go through
 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity]'s gantry, pipes,
 * or the warehouse terminal, not a per-rack GUI.
 */
abstract class RackBlock<T>(properties: Properties) : BaseEntityBlock(properties) where T : BlockEntity, T : RackBlockEntity, T : ExtendedMenuProvider {
	/** `null` - see [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.getMenuProvider] for why every extended-menu block here has to opt out of vanilla's spectator open path. */
	override fun getMenuProvider(state: BlockState, level: Level, pos: BlockPos): MenuProvider? = null


	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun getShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos,
		context: CollisionContext
	): VoxelShape = shape

	abstract val shape: VoxelShape

	@Suppress("UNCHECKED_CAST")
	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? T ?: return InteractionResult.PASS
		if (!level.isClientSide && player is ServerPlayer) {
			MenuRegistry.openExtendedMenu(player, tile)
		}
		return super.useWithoutItem(state, level, pos, player, hitResult)
	}
}

package net.kernelpanicsoft.boilerplate.warehouse

import com.mojang.serialization.MapCodec
import dev.architectury.registry.menu.MenuRegistry
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.MenuProvider

/**
 * A warehouse's single binding point - see [WarehouseWandItem]/[WarehouseControllerBlockEntity].
 * Empty-handed use opens the controller's own filter/priority menu ([WarehouseControllerScreen]);
 * the wand's own bind interaction is item-based ([WarehouseWandItem.useOn]) and keeps priority
 * over it.
 */
class WarehouseControllerBlock(properties: Properties) : BaseEntityBlock(properties) {
	/** `null` - see [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.getMenuProvider] for why every extended-menu block here has to opt out of vanilla's spectator open path. */
	override fun getMenuProvider(state: BlockState, level: Level, pos: BlockPos): MenuProvider? = null


	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WarehouseControllerBlockEntity(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.WarehouseController, WarehouseControllerBlockEntity::tick)

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? WarehouseControllerBlockEntity ?: return InteractionResult.PASS
		if (!level.isClientSide && player is ServerPlayer) {
			MenuRegistry.openExtendedMenu(player, tile)
		}
		return super.useWithoutItem(state, level, pos, player, hitResult)
	}

	/**
	 * Clears [WarehouseControllerBlockEntity.bounds] (which also removes the placed rail frame,
	 * via that property's own setter) on a genuine removal - `!state.is(newState.block)` excludes a
	 * same-block state change, e.g. a neighbor update, from counting as one. Deliberately *not*
	 * done from [WarehouseControllerBlockEntity.setRemoved] instead, which also fires on an
	 * ordinary chunk unload (including the whole world unloading at shutdown), not just a real
	 * removal - see [WarehouseControllerBlockEntity.bounds]'s own KDoc for the deadlock that caused.
	 */
	override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
		if (!state.`is`(newState.block) && !level.isClientSide) {
			(level.getBlockEntity(pos) as? WarehouseControllerBlockEntity)?.bounds = null
		}
		super.onRemove(state, level, pos, newState, movedByPiston)
	}

	companion object {
		val CODEC: MapCodec<WarehouseControllerBlock> = simpleCodec(::WarehouseControllerBlock)
	}
}

package net.kernelpanicsoft.boilerplate.warehouse.tank

import com.mojang.serialization.MapCodec
import dev.architectury.registry.menu.MenuRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * See [FluidTankBlockEntity]. A plain cube that opens [FluidTankScreen] on right-click - the same
 * shape [net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlock] takes.
 */
class FluidTankBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = FluidTankBlockEntity(pos, state)

	/** `null` - see [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.getMenuProvider] for why every extended-menu block here has to opt out of vanilla's spectator open path. */
	override fun getMenuProvider(state: BlockState, level: Level, pos: BlockPos): MenuProvider? = null

	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? FluidTankBlockEntity ?: return InteractionResult.PASS
		if (!level.isClientSide) MenuRegistry.openExtendedMenu(player as ServerPlayer, tile)
		return InteractionResult.sidedSuccess(level.isClientSide)
	}

	companion object {
		val CODEC: MapCodec<FluidTankBlock> = simpleCodec(::FluidTankBlock)
	}
}

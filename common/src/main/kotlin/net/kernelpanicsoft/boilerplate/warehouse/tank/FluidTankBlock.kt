package net.kernelpanicsoft.boilerplate.warehouse.tank

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * See [FluidTankBlockEntity]. A plain cube whose only direct interaction is echoing its contents to
 * the action bar - the same shape [net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlock] takes,
 * minus the menu, since there is no fluid-aware screen yet.
 */
class FluidTankBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = FluidTankBlockEntity(pos, state)

	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? FluidTankBlockEntity ?: return InteractionResult.PASS
		if (!level.isClientSide) player.displayClientMessage(tile.describeContents(), true)
		return InteractionResult.sidedSuccess(level.isClientSide)
	}

	companion object {
		val CODEC: MapCodec<FluidTankBlock> = simpleCodec(::FluidTankBlock)
	}
}

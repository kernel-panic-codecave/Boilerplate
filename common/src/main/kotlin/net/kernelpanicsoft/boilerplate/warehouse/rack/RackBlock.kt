package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Shared shape for [GeneralRackBlock]/[BulkRackBlock]/[UnstackableRackBlock] - a plain, always
 * player-placeable cube (see [net.kernelpanicsoft.boilerplate.datagen.BoilerplateBlockStateProvider]
 * for its `cubeAll` model) whose only direct interaction is [useWithoutItem] echoing its
 * [RackBlockEntity.describeContents] to the action bar; real reads/writes go through
 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity]'s gantry, pipes,
 * or the warehouse terminal, not a per-rack GUI.
 */
abstract class RackBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		if (!level.isClientSide) {
			val description = (level.getBlockEntity(pos) as? RackBlockEntity)?.describeContents()
			if (description != null) player.displayClientMessage(description, true)
		}
		return InteractionResult.SUCCESS
	}
}

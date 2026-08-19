package net.kernelpanicsoft.tubularstorage.crafting

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
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

/** See [AssemblyTableBlockEntity]. */
class AssemblyTableBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = AssemblyTableBlockEntity(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.AssemblyTable, AssemblyTableBlockEntity::tick)

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		if (!level.isClientSide) {
			val tile = level.getBlockEntity(pos) as? AssemblyTableBlockEntity ?: return InteractionResult.PASS
			player.openMenu(tile)
		}
		return InteractionResult.SUCCESS
	}

	companion object {
		val CODEC: MapCodec<AssemblyTableBlock> = simpleCodec(::AssemblyTableBlock)
	}
}

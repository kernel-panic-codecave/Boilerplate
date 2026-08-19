package net.kernelpanicsoft.tubularstorage.crafting

import com.mojang.serialization.MapCodec
import dev.architectury.registry.menu.MenuRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
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

/** See [AssemblyTableBlockEntity]. */
class AssemblyTableBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = AssemblyTableBlockEntity(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.AssemblyTable, AssemblyTableBlockEntity::tick)

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	/**
	 * [MenuRegistry.openExtendedMenu], not the plain vanilla [Player.openMenu] - [GuiRegistry.AssemblyTable][net.kernelpanicsoft.tubularstorage.registry.GuiRegistry.AssemblyTable]
	 * is registered via [MenuRegistry.ofExtended], and Fabric's own screen-handler API requires an
	 * extended menu type's provider be opened through Architectury's own cross-loader helper -
	 * `player.openMenu` alone throws `[Fabric] Extended screen handler ... must be opened with an
	 * ExtendedScreenHandlerFactory!`. Every hook menu already goes through this same helper (see
	 * `HookBlock.useWithoutItem`); this block just hadn't been updated to match when it stopped
	 * being a stub.
	 */
	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		if (!level.isClientSide) {
			val tile = level.getBlockEntity(pos) as? AssemblyTableBlockEntity ?: return InteractionResult.PASS
			MenuRegistry.openExtendedMenu(player as ServerPlayer, tile)
		}
		return InteractionResult.SUCCESS
	}

	companion object {
		val CODEC: MapCodec<AssemblyTableBlock> = simpleCodec(::AssemblyTableBlock)
	}
}

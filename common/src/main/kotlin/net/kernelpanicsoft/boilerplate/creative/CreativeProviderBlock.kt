package net.kernelpanicsoft.boilerplate.creative

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
 * A creative-tab-only, never-craftable block that offers an endless supply of one configured
 * resource to whatever touches it - see [CreativeProviderBlockEntity].
 *
 * Right-click opens its own screen, where the resource is set by clicking with it in hand, the same
 * ghost-slot gesture every other configuration surface in the mod uses.
 */
class CreativeProviderBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<CreativeProviderBlock> = CODEC

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CreativeProviderBlockEntity(pos, state)

	/** `null` - see [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.getMenuProvider] for why every extended-menu block here has to opt out of vanilla's spectator open path. */
	override fun getMenuProvider(state: BlockState, level: Level, pos: BlockPos): MenuProvider? = null

	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? CreativeProviderBlockEntity ?: return InteractionResult.PASS
		if (!level.isClientSide && player is ServerPlayer) MenuRegistry.openExtendedMenu(player, tile)
		return super.useWithoutItem(state, level, pos, player, hitResult)
	}

	companion object {
		val CODEC: MapCodec<CreativeProviderBlock> = simpleCodec(::CreativeProviderBlock)
	}
}

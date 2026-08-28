package net.kernelpanicsoft.tubularstorage.power.block

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.tubularstorage.power.entity.CreativePressureSourceBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * A creative-tab-only, never-craftable block that supplies effectively unlimited pressure to
 * whatever [net.kernelpanicsoft.tubularstorage.power.PressureApi]-aware neighbor touches it (a
 * pressure pipe, or an item pipe - conducts pressure as a secondary too) - for creative-mode
 * building and gametest fixtures that need a reachable pressure source without wiring up a real
 * fuel-burning [net.kernelpanicsoft.tubularstorage.power.CompressorEncasementType]. See
 * [net.kernelpanicsoft.tubularstorage.power.entity.CreativePressureSourceBlockEntity].
 */
class CreativePressureSourceBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<CreativePressureSourceBlock> = CODEC

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CreativePressureSourceBlockEntity(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.CreativePressureSource, CreativePressureSourceBlockEntity::tick)

	companion object {
		val CODEC: MapCodec<CreativePressureSourceBlock> = simpleCodec(::CreativePressureSourceBlock)
	}
}

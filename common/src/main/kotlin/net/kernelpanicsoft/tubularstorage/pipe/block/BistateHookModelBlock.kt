package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

class BistateHookModelBlock(properties: Properties) : HookModelBlock(properties)
{
	init {
		registerDefaultState(stateDefinition.any())
	}

	override fun codec(): MapCodec<out BistateHookModelBlock> = CODEC

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		super.createBlockStateDefinition(builder)
		builder.add(ACTIVE)
	}

	companion object {
		val ACTIVE: BooleanProperty = BooleanProperty.create("active")

		val CODEC: MapCodec<BistateHookModelBlock> = simpleCodec(::BistateHookModelBlock)
	}
}
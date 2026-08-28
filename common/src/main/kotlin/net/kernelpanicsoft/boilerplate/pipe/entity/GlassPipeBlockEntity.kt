package net.kernelpanicsoft.boilerplate.pipe.entity

import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * A [PipeBlockEntity] with no behavior of its own beyond binding [TileRegistry.GlassPipe] - its
 * only purpose is letting a glass pipe register its own (heavier)
 * [net.kernelpanicsoft.boilerplate.pipe.client.TravelingItemBlockEntityRenderer] without a
 * plain (opaque) [TileRegistry.Pipe] paying for it too.
 */
class GlassPipeBlockEntity(pos: BlockPos, state: BlockState) : PipeBlockEntity(TileRegistry.GlassPipe, pos, state)

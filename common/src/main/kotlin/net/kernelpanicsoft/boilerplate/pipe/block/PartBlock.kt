package net.kernelpanicsoft.boilerplate.pipe.block

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState

/**
 * A hidden model-carrier block - never placed in the world, never obtainable, with no item form.
 * Exists purely so a [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType] has a
 * real blockstate definition of its own (`assets/<ns>/blockstates/<type>_part.json` over block
 * models under `models/block/`) that its
 * [getRenderState][net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.getRenderState]
 * override can select variants from, where the multipart segment's visual bakes it exactly like it
 * bakes the pipe body's own state. One instance per attachment type is registered automatically as
 * `<type>_part` (see [net.kernelpanicsoft.boilerplate.registry.BlockRegistry]).
 *
 * Carries no behavior whatsoever: it never appears in a world, so its shapes are never asked for,
 * and the attachments' gameplay side stays entirely on
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity]. [RenderShape.MODEL] only
 * matters insofar as the visual bakes these states through the ordinary block-model pipeline.
 */
abstract class PartBlock(properties: Properties) : Block(properties) {
	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL
}

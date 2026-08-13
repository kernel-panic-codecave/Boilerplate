package net.kernelpanicsoft.tubularstorage.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.kernelpanicsoft.archie.util.div
import net.kernelpanicsoft.archie.util.plus
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf

/**
 * Renders each attached hook as its [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType]'s
 * baked model - modeled once facing north, then rotated in place to the attached face. Currently
 * placeholder cuboid geometry (see `docs/design/m1-pipe-network.md`) until real per-hook models
 * exist; the loading/rotation mechanism is otherwise final.
 */
class PipeHookBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<PipeBlockEntity> {
	private val modelManager = context.blockRenderDispatcher.blockModelShaper.modelManager
	private val modelRenderer = context.blockRenderDispatcher.modelRenderer

	override fun render(tile: PipeBlockEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
		val consumer = bufferSource.getBuffer(RenderType.solid())
		for ((directionName, hookState) in tile.hooks) {
			val direction = Direction.valueOf(directionName)
			val modelId = modelIdFor(hookState.type)
			val model = modelManager.getModel(ModelResourceLocation(modelId, "inventory"))

			poseStack.pushPose()
			poseStack.translate(0.5, 0.5, 0.5)
			poseStack.mulPose(rotationFor(direction))
			poseStack.translate(-0.5, -0.5, -0.5)
			modelRenderer.renderModel(poseStack.last(), consumer, null, model, 1f, 1f, 1f, packedLight, packedOverlay)
			poseStack.popPose()
		}
	}

	/** `mymod:extraction` -> `mymod:block/extraction_hook` - a convention, not a lookup, so an addon's own [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] gets a model reference for free as long as it follows it. */
	private fun modelIdFor(hookTypeId: ResourceLocation): ResourceLocation = "block" / hookTypeId + "_hook"

	/** Each hook's model faces north by default; this rotates it in place to face [direction] instead. */
	private fun rotationFor(direction: Direction): Quaternionf = when (direction) {
		Direction.NORTH -> Axis.YP.rotationDegrees(0f)
		Direction.SOUTH -> Axis.YP.rotationDegrees(180f)
		Direction.EAST -> Axis.YP.rotationDegrees(-90f)
		Direction.WEST -> Axis.YP.rotationDegrees(90f)
		Direction.UP -> Axis.XP.rotationDegrees(-90f)
		Direction.DOWN -> Axis.XP.rotationDegrees(90f)
	}
}

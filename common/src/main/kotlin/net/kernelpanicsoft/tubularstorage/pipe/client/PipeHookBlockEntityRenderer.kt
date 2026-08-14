package net.kernelpanicsoft.tubularstorage.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.kernelpanicsoft.archie.util.plus
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf

/**
 * Renders a [HookBlockEntity] entirely itself, since [HookBlock][net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock]
 * is [net.minecraft.world.level.block.RenderShape.INVISIBLE] and contributes no baked-model
 * geometry of its own:
 * - the pipe body, as [HookBlockEntity.pipeBlockId]'s own baked model (arms with a hook omitted,
 *   so the hook covers that face instead of clipping through a bare arm) - this way a promoted
 *   pipe keeps looking like whatever pipe type it actually is, rather than a hardcoded appearance,
 *   and a second pipe type needs nothing renderer-side beyond its own blockstate/models.
 * - each attached hook, as its [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType]'s baked
 *   model - modeled once facing north, then rotated in place to the attached face. Currently
 *   placeholder cuboid geometry (see `docs/design/m1-pipe-network.md`) until real per-hook models
 *   exist; the loading/rotation mechanism is otherwise final.
 *
 * Neither path delegates to the pipe type's own block entity renderer if it happens to register
 * one - no pipe type does today, and doing so safely means synthesizing a throwaway block entity
 * per hook block per frame just to ask. If a pipe type ever needs one, that's the fallback below
 * (baked-model [modelRenderer.tesselateBlock]) to extend, not a new code path.
 */
class PipeHookBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<HookBlockEntity> {
	private val blockModelShaper = context.blockRenderDispatcher.blockModelShaper
	private val modelManager = blockModelShaper.modelManager
	private val modelRenderer = context.blockRenderDispatcher.modelRenderer

	override fun render(tile: HookBlockEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
		val level = tile.level ?: return
		val consumer = bufferSource.getBuffer(RenderType.solid())

		val pipeState = pipeStateFor(tile)
		val pipeModel = blockModelShaper.getBlockModel(pipeState)
		modelRenderer.tesselateBlock(level, pipeModel, pipeState, tile.blockPos, poseStack, consumer, false, RandomSource.create(), tile.blockPos.asLong(), packedOverlay)

		for ((directionName, hookState) in tile.hooks) {
			val direction = Direction.valueOf(directionName)
			val model = modelManager.getModel(modelIdFor(hookState.type))

			poseStack.pushPose()
			poseStack.translate(0.5, 0.5, 0.5)
			poseStack.mulPose(rotationFor(direction))
			poseStack.translate(-0.5, -0.5, -0.5)
			modelRenderer.tesselateBlock(level, model, tile.blockState, tile.blockPos, poseStack, consumer, false, RandomSource.create(), tile.blockPos.asLong(), packedOverlay)
			poseStack.popPose()
		}
	}

	/**
	 * [HookBlockEntity.pipeBlockId]'s own default state, with each direction connected only if
	 * [tile] is actually connected there *and* has no hook attached - an attached hook takes that
	 * arm's place rather than clipping through it, mirroring what a plain (hookless) pipe of that
	 * type would show for the same connections.
	 */
	private fun pipeStateFor(tile: HookBlockEntity): BlockState {
		val pipeBlock = BuiltInRegistries.BLOCK.get(tile.pipeBlockId) as? PipeBlock ?: BlockRegistry.Pipe
		return PipeBlock.propertiesByDirection.entries.fold(pipeBlock.defaultBlockState()) { state, (direction, property) ->
			state.setValue(property, tile.blockState.getValue(property) && !tile.hooks.containsKey(direction.name))
		}
	}

	/**
	 * `mymod:extraction` -> the `inventory` variant of `mymod:extraction_hook`'s item model - the
	 * same baked model already guaranteed to exist for that hook's `HookItem` icon (registered as
	 * `<hook type path>_hook`, per [net.kernelpanicsoft.tubularstorage.registry.ItemRegistry]), so
	 * this never queries a model that was never actually baked. A convention, not a lookup table,
	 * so an addon's own [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] gets a model
	 * reference for free as long as it follows it.
	 */
	private fun modelIdFor(hookTypeId: ResourceLocation): ModelResourceLocation =
		ModelResourceLocation(hookTypeId + "_hook", "inventory")

	/** Each hook's model faces north by default; this rotates it in place to face [direction] instead. */
	private fun rotationFor(direction: Direction): Quaternionf = when (direction) {
		Direction.NORTH -> Axis.YP.rotationDegrees(0f)
		Direction.SOUTH -> Axis.YP.rotationDegrees(180f)
		Direction.EAST -> Axis.YP.rotationDegrees(-90f)
		Direction.WEST -> Axis.YP.rotationDegrees(90f)
		Direction.UP -> Axis.XP.rotationDegrees(90f)
		Direction.DOWN -> Axis.XP.rotationDegrees(-90f)
	}
}

package net.kernelpanicsoft.tubularstorage.warehouse.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.GantryClientCache
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Renders the ghost rail overlay and crane head for [tile]'s gantry - see
 * `docs/design/m3-warehouse-storage.md`. Only draws anything while the (dead-reckoned, from
 * [GantryClientCache]) gantry is actually mid-move: the rail is a readability aid for an active
 * job, not a permanent grid over the whole bound volume.
 *
 * Both the rail segments (spaced one per block along each leg of the active path) and the head
 * itself are drawn as [ItemRegistry.GantryRail]/[ItemRegistry.GantryHead]'s baked models - real
 * placeholder geometry (translucent for the rail, solid for the head), not an abstract line or
 * particle trail. Those two items exist purely as bake targets for this renderer, not for players.
 */
class WarehouseControllerBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WarehouseControllerBlockEntity> {
	private val modelManager = context.blockRenderDispatcher.blockModelShaper.modelManager
	private val modelRenderer = context.blockRenderDispatcher.modelRenderer

	override fun render(
		tile: WarehouseControllerBlockEntity,
		partialTick: Float,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int,
	) {
		val level = tile.level ?: return
		val gantry = GantryClientCache.get(tile.blockPos) ?: return
		if (!gantry.isMoving) return

		val railModel = modelManager.getModel(RAIL_MODEL_ID)
		val railConsumer = bufferSource.getBuffer(RenderType.translucent())
		var from = gantry.pos
		for (waypoint in gantry.remainingPath) {
			for (point in pointsAlong(from, waypoint)) {
				drawAt(tile.blockPos, point, level, poseStack, railConsumer, railModel, packedOverlay)
			}
			from = waypoint
		}

		val headModel = modelManager.getModel(HEAD_MODEL_ID)
		val headConsumer = bufferSource.getBuffer(RenderType.solid())
		drawAt(tile.blockPos, gantry.pos, level, poseStack, headConsumer, headModel, packedOverlay)
	}

	/** One point per block of travel from [from] to [to], not including [from] itself - so consecutive legs don't double-draw their shared endpoint. */
	private fun pointsAlong(from: Vec3, to: Vec3): List<Vec3> {
		val distance = to.subtract(from).length()
		val steps = ceil(distance).toInt().coerceAtLeast(1)
		return (1..steps).map { step -> from.lerp(to, step.toDouble() / steps) }
	}

	private fun drawAt(
		originPos: BlockPos,
		worldPos: Vec3,
		level: Level,
		poseStack: PoseStack,
		consumer: VertexConsumer,
		model: BakedModel,
		packedOverlay: Int,
	) {
		val blockPos = BlockPos.containing(worldPos)
		poseStack.pushPose()
		poseStack.translate(worldPos.x - originPos.x - 0.5, worldPos.y - originPos.y - 0.5, worldPos.z - originPos.z - 0.5)
		modelRenderer.tesselateBlock(
			level, model, level.getBlockState(blockPos), blockPos, poseStack, consumer, false,
			RandomSource.create(), blockPos.asLong(), packedOverlay,
		)
		poseStack.popPose()
	}

	companion object {
		private val RAIL_MODEL_ID = ModelResourceLocation(BuiltInRegistries.ITEM.getKey(ItemRegistry.GantryRail), "inventory")
		private val HEAD_MODEL_ID = ModelResourceLocation(BuiltInRegistries.ITEM.getKey(ItemRegistry.GantryHead), "inventory")
	}
}

package net.kernelpanicsoft.tubularstorage.warehouse.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.GantryClientCache
import net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Renders the *moving* half of a warehouse gantry, styled after BuildCraft's Quarry: the static
 * perimeter is real [BlockRegistry.GantryRail] blocks placed by
 * [WarehouseControllerBlockEntity.bounds] (ordinary chunk sync handles those), but the two
 * crossbeams that slide to track the head, the vertical drop rod connecting down to it, and the
 * head itself all change every tick and aren't practical as real blocks - so they stay a
 * client-only dynamic render instead, drawn (dead-reckoned, from [GantryClientCache]) only while
 * the gantry is actually mid-move.
 *
 * The crossbeams reuse [GantryRailBlock]'s own baked model - looked up once, in [eastWestModel]
 * etc., against a synthetic fully-connected [BlockState] rather than any placed block - so they
 * read as an extension of the real frame rather than a different material. One beam spans the
 * bound footprint's full X extent at the head's current Z, the other spans the full Z extent at
 * the head's current X, intersecting directly above wherever the head is; the drop rod does the
 * same straight down from rail height to the head. The head itself keeps using
 * [ItemRegistry.GantryHead]'s placeholder model, a fake item that exists purely as a bake target
 * for this renderer.
 */
class WarehouseControllerBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WarehouseControllerBlockEntity> {
	private val blockModelShaper = context.blockRenderDispatcher.blockModelShaper
	private val modelRenderer = context.blockRenderDispatcher.modelRenderer

	private val eastWestModel = blockModelShaper.getBlockModel(EAST_WEST_STATE)
	private val northSouthModel = blockModelShaper.getBlockModel(NORTH_SOUTH_STATE)
	private val upDownModel = blockModelShaper.getBlockModel(UP_DOWN_STATE)
	private val headModel = blockModelShaper.modelManager.getModel(HEAD_MODEL_ID)

	override fun render(
		tile: WarehouseControllerBlockEntity,
		partialTick: Float,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int,
	) {
		val level = tile.level ?: return
		val (gantry, bounds) = GantryClientCache.get(tile.blockPos) ?: return
		if (!gantry.isMoving) return

		val consumer = bufferSource.getBuffer(RenderType.solid())
		val railY = bounds.max.y
		val head = gantry.pos

		for (x in bounds.min.x..bounds.max.x) {
			drawAt(tile.blockPos, Vec3(x + 0.5, railY + 0.5, head.z), level, poseStack, consumer, eastWestModel, EAST_WEST_STATE, packedOverlay)
		}
		for (z in bounds.min.z..bounds.max.z) {
			drawAt(tile.blockPos, Vec3(head.x, railY + 0.5, z + 0.5), level, poseStack, consumer, northSouthModel, NORTH_SOUTH_STATE, packedOverlay)
		}
		for (y in floor(head.y).toInt()..railY) {
			drawAt(tile.blockPos, Vec3(head.x, y + 0.5, head.z), level, poseStack, consumer, upDownModel, UP_DOWN_STATE, packedOverlay)
		}

		drawAt(tile.blockPos, head, level, poseStack, consumer, headModel, level.getBlockState(BlockPos.containing(head)), packedOverlay)
	}

	private fun drawAt(
		originPos: BlockPos,
		worldPos: Vec3,
		level: Level,
		poseStack: PoseStack,
		consumer: VertexConsumer,
		model: BakedModel,
		state: BlockState,
		packedOverlay: Int,
	) {
		val blockPos = BlockPos.containing(worldPos)
		poseStack.pushPose()
		poseStack.translate(worldPos.x - originPos.x - 0.5, worldPos.y - originPos.y - 0.5, worldPos.z - originPos.z - 0.5)
		modelRenderer.tesselateBlock(
			level, model, state, blockPos, poseStack, consumer, false,
			RandomSource.create(), blockPos.asLong(), packedOverlay,
		)
		poseStack.popPose()
	}

	companion object {
		private val EAST_WEST_STATE: BlockState = BlockRegistry.GantryRail.defaultBlockState()
			.setValue(GantryRailBlock.propertiesByDirection.getValue(Direction.EAST), true)
			.setValue(GantryRailBlock.propertiesByDirection.getValue(Direction.WEST), true)

		private val NORTH_SOUTH_STATE: BlockState = BlockRegistry.GantryRail.defaultBlockState()
			.setValue(GantryRailBlock.propertiesByDirection.getValue(Direction.NORTH), true)
			.setValue(GantryRailBlock.propertiesByDirection.getValue(Direction.SOUTH), true)

		private val UP_DOWN_STATE: BlockState = BlockRegistry.GantryRail.defaultBlockState()
			.setValue(GantryRailBlock.propertiesByDirection.getValue(Direction.UP), true)
			.setValue(GantryRailBlock.propertiesByDirection.getValue(Direction.DOWN), true)

		private val HEAD_MODEL_ID = ModelResourceLocation(BuiltInRegistries.ITEM.getKey(ItemRegistry.GantryHead), "inventory")
	}
}

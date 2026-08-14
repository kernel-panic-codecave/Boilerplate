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
import net.minecraft.world.level.block.state.properties.BooleanProperty
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
 * The crossbeams/rod reuse [GantryRailBlock]'s own baked model rather than any placed block, so
 * they read as an extension of the real frame. Each segment's connected [BlockState] is computed
 * from where it actually sits along its own run ([connectionState]) rather than a single blanket
 * "fully connected" state - the two segments at a run's true ends only connect *inward*, so they
 * cap off with a plain core face instead of an arm stub poking into nothing. [modelFor] memoizes
 * the resulting handful of distinct baked models, since the same state recurs at every interior
 * position. One beam spans the bound footprint's full X extent at the head's current Z, the other
 * spans the full Z extent at the head's current X, intersecting directly above wherever the head
 * is; the drop rod does the same straight down from rail height to the head. The head itself keeps
 * using [ItemRegistry.GantryHead]'s placeholder model, a fake item that exists purely as a bake
 * target for this renderer.
 */
class WarehouseControllerBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WarehouseControllerBlockEntity> {
	private val blockModelShaper = context.blockRenderDispatcher.blockModelShaper
	private val modelRenderer = context.blockRenderDispatcher.modelRenderer
	private val headModel = blockModelShaper.modelManager.getModel(HEAD_MODEL_ID)

	private val modelCache = HashMap<BlockState, BakedModel>()
	private fun modelFor(state: BlockState): BakedModel = modelCache.getOrPut(state) { blockModelShaper.getBlockModel(state) }

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
			val state = connectionState(x, bounds.min.x, bounds.max.x, WEST_PROPERTY, EAST_PROPERTY)
			drawAt(tile.blockPos, Vec3(x + 0.5, railY + 0.5, head.z), level, poseStack, consumer, modelFor(state), state, packedOverlay)
		}
		for (z in bounds.min.z..bounds.max.z) {
			val state = connectionState(z, bounds.min.z, bounds.max.z, NORTH_PROPERTY, SOUTH_PROPERTY)
			drawAt(tile.blockPos, Vec3(head.x, railY + 0.5, z + 0.5), level, poseStack, consumer, modelFor(state), state, packedOverlay)
		}
		val bottomY = floor(head.y).toInt()
		for (y in bottomY..railY) {
			val state = connectionState(y, bottomY, railY, DOWN_PROPERTY, UP_PROPERTY)
			drawAt(tile.blockPos, Vec3(head.x, y + 0.5, head.z), level, poseStack, consumer, modelFor(state), state, packedOverlay)
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
		private val WEST_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.WEST)
		private val EAST_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.EAST)
		private val NORTH_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.NORTH)
		private val SOUTH_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.SOUTH)
		private val UP_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.UP)
		private val DOWN_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.DOWN)

		private val HEAD_MODEL_ID = ModelResourceLocation(BuiltInRegistries.ITEM.getKey(ItemRegistry.GantryHead), "inventory")

		/**
		 * [BlockRegistry.GantryRail]'s default state with [negativeProperty] connected iff [position]
		 * has a segment behind it (`position > min`) and [positiveProperty] connected iff it has one
		 * ahead (`position < max`) - so the two ends of a run only connect inward and cap off cleanly,
		 * rather than every segment (including the true ends) rendering as fully connected regardless
		 * of what's actually next to it.
		 */
		private fun connectionState(position: Int, min: Int, max: Int, negativeProperty: BooleanProperty, positiveProperty: BooleanProperty): BlockState {
			var state = BlockRegistry.GantryRail.defaultBlockState()
			if (position > min) state = state.setValue(negativeProperty, true)
			if (position < max) state = state.setValue(positiveProperty, true)
			return state
		}
	}
}

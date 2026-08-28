package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Renders [TravelingItem]s as small floating items, moving from the face they entered through to
 * the face they're headed to next via [CENTER] - shared by whichever block entity renderer
 * actually decides its pipe type's contents should be visible
 * ([net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.showsTravelingItems]):
 * [TravelingItemBlockEntityRenderer] for a plain [net.kernelpanicsoft.boilerplate.pipe.block.GlassPipeBlock],
 * and [MultipartTravelingItemRenderer] for a [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]
 * promoted from one.
 */
object TravelingItemRenderer {
	fun render(
		items: List<TravelingItem>,
		itemRenderer: ItemRenderer,
		level: Level,
		pos: BlockPos,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int,
		partialTick: Float,
	) {
		for ((stack, fromDirection, progress, path) in items) {
			val from = tipOf(fromDirection)
			val toDirection = path.firstOrNull()?.let { next ->
				Direction.fromDelta(next.x - pos.x, next.y - pos.y, next.z - pos.z)
			}
			val to = toDirection?.let(::tipOf) ?: CENTER
			val itemPos = pathPosition(from, to, progress.toDouble())
			val seed = pos.asLong().toInt()
			val centeringOffset = centeringOffset(itemRenderer, stack.resource.cachedStack, level, seed)

			poseStack.pushPose()
			poseStack.translate(itemPos.x, itemPos.y - centeringOffset, itemPos.z)
			poseStack.mulPose(Axis.YP.rotationDegrees((level.gameTime + partialTick) * SPIN_DEGREES_PER_TICK))
			poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
			itemRenderer.renderStatic(stack.resource.cachedStack, ItemDisplayContext.GROUND, packedLight, packedOverlay, poseStack, bufferSource, level, seed)
			poseStack.popPose()
		}
	}

	/**
	 * Interpolates through [CENTER] rather than straight from [from] to [to] - a pipe's model bends
	 * at a right angle through its core box (see
	 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.CORE_SHAPE]/`armShapes`), so a single
	 * straight line between two non-opposite faces cuts across the corner and pokes outside the
	 * pipe's own shape. Routing through the center keeps both legs axis-aligned with an arm instead,
	 * matching the model - and degenerates to the same straight line it always was for a
	 * straight-through (opposite-face) hop, since [from]/[CENTER]/[to] are already colinear then.
	 */
	private fun pathPosition(from: Vec3, to: Vec3, progress: Double): Vec3 =
		if (progress < 0.5) from.lerp(CENTER, progress * 2.0) else CENTER.lerp(to, (progress - 0.5) * 2.0)

	/** The point at the very edge of the block face in [direction], relative to the block's own local origin. */
	private fun tipOf(direction: Direction): Vec3 = CENTER.add(Vec3.atLowerCornerOf(direction.normal).scale(0.5))

	/**
	 * Downward shift so [stack]'s own rendered vertical *center* lands on [itemPos]'s Y, not its
	 * bottom edge - read off [stack]'s own resolved model rather than assumed, since a block item
	 * bakes to its block's own 3D model (a very different [ItemDisplayContext.GROUND] transform)
	 * rather than the flat `item/generated` quad a plain item uses.
	 *
	 * `net.minecraft.client.renderer.entity.ItemRenderer.render` applies
	 * [ItemDisplayContext.GROUND]'s own translate/scale and *then* recenters the model
	 * (`poseStack.translate(-0.5, -0.5, -0.5)`) before drawing it, both applied *after* our own
	 * [PoseStack] translate/scale in [render] so they're composed inside our outer [ITEM_SCALE] too.
	 * That recenter puts the model's own geometric middle back on the display transform's translate,
	 * with its scale left with nothing left to shift (it scales around that same middle) - so the
	 * midpoint ends up `ITEM_SCALE * translation.y` above our translate pivot, which this cancels out.
	 */
	private fun centeringOffset(itemRenderer: ItemRenderer, stack: ItemStack, level: Level, seed: Int): Float {
		val ground = itemRenderer.getModel(stack, level, null, seed).transforms.getTransform(ItemDisplayContext.GROUND)
		return ITEM_SCALE * ground.translation.y()
	}

	private val CENTER = Vec3(0.5, 0.5, 0.5)
	private const val ITEM_SCALE = 0.4f
	private const val SPIN_DEGREES_PER_TICK = 4f
}

package net.kernelpanicsoft.tubularstorage.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Renders [TravelingItem]s as small floating items, moving in a straight line from the face they
 * entered through toward the face they're headed to next - shared by whichever block entity
 * renderer actually decides its pipe type's contents should be visible
 * ([net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.showsTravelingItems]):
 * [TravelingItemBlockEntityRenderer] for a plain [net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock],
 * and [PipeHookBlockEntityRenderer] for a [net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock]
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
			val itemPos = from.lerp(to, progress.toDouble())

			poseStack.pushPose()
			poseStack.translate(itemPos.x, itemPos.y, itemPos.z)
			poseStack.mulPose(Axis.YP.rotationDegrees((level.gameTime + partialTick) * SPIN_DEGREES_PER_TICK))
			poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
			itemRenderer.renderStatic(stack, ItemDisplayContext.GROUND, packedLight, packedOverlay, poseStack, bufferSource, level, pos.asLong().toInt())
			poseStack.popPose()
		}
	}

	/** The point at the very edge of the block face in [direction], relative to the block's own local origin. */
	private fun tipOf(direction: Direction): Vec3 = CENTER.add(Vec3.atLowerCornerOf(direction.normal).scale(0.5))

	private val CENTER = Vec3(0.5, 0.5, 0.5)
	private const val ITEM_SCALE = 0.4f
	private const val SPIN_DEGREES_PER_TICK = 4f
}

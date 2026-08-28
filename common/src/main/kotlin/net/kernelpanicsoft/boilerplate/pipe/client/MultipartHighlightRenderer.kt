package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Draws the crosshair-targeting outline for a [MultipartBlock]: only the one part the player is
 * actually pointing at ([PipeBlock.targetedPart]'s answer), instead of vanilla's whole-block
 * outline. Called from `LevelRenderer.renderHitOutline` via a mixin - the same spot vanilla draws
 * its own outline through, with the very same already-bound `RenderType.lines()` consumer, so the
 * result is visually indistinguishable from vanilla's except for which boxes it covers.
 *
 * This split is what keeps the selection stable: the block's own
 * [net.minecraft.world.level.block.state.BlockBehaviour.getShape] stays the segment's full
 * geometry (vanilla raytraces against it every frame, so varying it by the previous hit fed the
 * clip back into itself and flickered), while the per-part outline reads the settled hit result
 * afterwards and never influences the next clip.
 *
 * Returns whether it handled this block - `true` means the outline was drawn here and the caller
 * must suppress vanilla's own; any miss (non-multipart block, no usable hit, unresolvable part)
 * returns `false` so vanilla's fallback outline still shows.
 */
object MultipartHighlightRenderer {

	/** Vanilla's own hit-outline color/alpha, so the per-part boxes read exactly like normal outlines. */
	private const val RED = 0f
	private const val GREEN = 0f
	private const val BLUE = 0f
	private const val ALPHA = 0.4f

	/** See the class KDoc. Coordinates follow `renderHitOutline`'s own convention: world-space boxes minus the camera position, under the frame's rotation-only pose stack. */
	@JvmStatic
	fun renderOutline(
		poseStack: PoseStack,
		consumer: VertexConsumer,
		camX: Double,
		camY: Double,
		camZ: Double,
		entity: Entity,
		pos: BlockPos,
		state: BlockState,
	): Boolean {
		val block = state.block as? MultipartBlock ?: return false
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return false
		val hit = minecraft.hitResult as? BlockHitResult ?: return false
		if (hit.blockPos != pos) return false

		val part = block.targetedPart(state, level, pos, entity.eyePosition, hit.location) ?: return false
		val shape = block.outlineShapeFor(part, state, level, pos)

		LevelRenderer.renderShape(poseStack, consumer, shape, pos.x.toDouble() - camX, pos.y.toDouble() - camY, pos.z.toDouble() - camZ, RED, GREEN, BLUE, ALPHA)
		return true
	}
}

package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.DebugOverlayTogglePacket
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer.mergedShapesCacheKey
import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer.unionShape
import net.kernelpanicsoft.boilerplate.pipe.network.EdgeKind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Draws the route-search overlay into the world frame: one wireframe per pipe network that hugs
 * the nodes' real block geometry - each member's [PipeBlock] core/arm/hook shape (promoted
 * [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock] segments and encasements included)
 * unioned into a single [VoxelShape] per network, each network in its own hue; the source of every
 * recent extract's route search; every hop that search considered (pipes it walked, boundary edges
 * it stopped at, destinations it probed and ones that rejected the item); and the route it
 * actually settled on, bright yellow.
 *
 * Tied to vanilla's F3+B hitbox toggle: each flip of `EntityRenderDispatcher`'s hitbox flag also
 * shows or hides this overlay and announces the state to the server (see
 * [DebugOverlayTogglePacket]), so route-search tracing and snapshot broadcasting only run while at
 * least one player is actually looking - on any environment, dev or production.
 *
 * Delta-free: it draws boxes and line segments under the frame's rotation-only pose stack at
 * camera-relative coordinates, exactly like vanilla's hit outline, then flushes its own
 * `RenderType.lines()` batch so the overlay is deterministic regardless of what the rest of the
 * level pass buffers.
 */
object DebugNetworkRenderer {
	/** The previous frame's hitbox flag, so a flip is announced to the server exactly once. Render-thread-only. */
	private var lastHitBoxes = false

	/** The [DebugNetworkCache.snapshot]'s network list this pass' aggregated wireframes were built from - a fresh snapshot replaces the list instance, so identity is the invalidation signal. */
	private var mergedShapesCacheKey: List<DebugNetworkCache.Network>? = null

	/** Per-network aggregated [VoxelShape]s, aligned with the cache key's list by index - `null` where a network had nothing pipe-shaped to draw. */
	private var mergedShapesCache: List<VoxelShape?> = emptyList()

	private const val NODE_ALPHA = 0.35f
	private const val SOURCE_EXPAND = 0.15

	// Edge-kind colors (RGB ints)
	private const val TRANSIT_COLOR = 0xcfd8e8
	private const val BOUNDARY_COLOR = 0x77808c
	private const val CANDIDATE_COLOR = 0x37e06e
	private const val REJECTED_COLOR = 0xe8432e

	private const val ROUTE_RED = 1f
	private const val ROUTE_GREEN = 0.85f
	private const val ROUTE_BLUE = 0.1f
	private const val ROUTE_ALPHA = 0.95f

	private const val EDGE_ALPHA = 0.55f
	private const val SOURCE_RED = 0.2f
	private const val SOURCE_GREEN = 0.9f
	private const val SOURCE_BLUE = 1f
	private const val SOURCE_ALPHA = 0.7f

	fun renderFrame(poseStack: PoseStack, bufferSource: MultiBufferSource) {
		val minecraft = Minecraft.getInstance()
		val hitBoxes = minecraft.entityRenderDispatcher.shouldRenderHitBoxes()
		if (hitBoxes != lastHitBoxes) {
			lastHitBoxes = hitBoxes
			BoilerplateNetworkChannel.toServer(DebugOverlayTogglePacket(hitBoxes))
		}
		if (!hitBoxes) return

		val level = minecraft.level
		DebugNetworkCache.onLevel(level)
		if (level == null) return

		val source = bufferSource as? MultiBufferSource.BufferSource ?: return
		val (networks, traces) = DebugNetworkCache.snapshot()
		if (networks.isEmpty() && traces.isEmpty()) return

		val cam = minecraft.gameRenderer.mainCamera.position
		val consumer = source.getBuffer(RenderType.lines())
		val pose = poseStack.last()

		val shapes = mergedShapes(networks, level)
		for ((index, network) in networks.withIndex()) {
			val shape = shapes[index] ?: continue
			val (r, g, b) = unpack(network.color)
			LevelRenderer.renderShape(poseStack, consumer, shape, -cam.x, -cam.y, -cam.z, r, g, b, NODE_ALPHA)
		}

		for ((from, edges, route) in traces) {
			sourceBox(consumer, poseStack, from, cam)
			for ((from1, to, kind) in edges) {
				if (from1 == to) continue
				val color = when (kind) {
					EdgeKind.TRANSIT.ordinal -> TRANSIT_COLOR
					EdgeKind.BOUNDARY.ordinal -> BOUNDARY_COLOR
					EdgeKind.CANDIDATE.ordinal -> CANDIDATE_COLOR
					else -> REJECTED_COLOR
				}
				val (r, g, b) = unpack(color)
				line(consumer, pose, from1, to, cam, r, g, b, EDGE_ALPHA)
			}
			var previous: BlockPos? = null
			for (point in route) {
				val prev = previous
				if (prev != null && prev != point) {
					line(consumer, pose, prev, point, cam, ROUTE_RED, ROUTE_GREEN, ROUTE_BLUE, ROUTE_ALPHA)
				}
				previous = point
			}
		}

		source.endBatch(RenderType.lines())
	}

	/**
	 * The per-network aggregated [VoxelShape]s, rebuilt only when a fresh snapshot replaces the
	 * member lists (see [mergedShapesCacheKey]) - the actual unioning is [unionShape]'s job, and
	 * redoing it from the copy-on-write cache's own identity change means the wireframes are
	 * recomputed a few times per second at most, never per frame.
	 */
	private fun mergedShapes(networks: List<DebugNetworkCache.Network>, level: ClientLevel): List<VoxelShape?> {
		if (networks !== mergedShapesCacheKey) {
			mergedShapesCacheKey = networks
			mergedShapesCache = networks.map { network -> unionShape(level, network.members) }
		}
		return mergedShapesCache
	}

	/**
	 * Unions [members]' real block geometry into one absolute-coordinate [VoxelShape]: each pipe
	 * position's own [PipeBlock] shape (its core with per-face arms where connected - a promoted
	 * [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock] delegates the same
	 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.getShape] machinery, encasement
* casings and hook housings included) is shifted to its block offset and [Shapes.join]ed with
 * the rest. The result renders as a wireframe hugging the actual pipe bodies with
 * [LevelRenderer.renderShape] instead of full-block volumes.
	 * Non-pipe positions are skipped defensively - network membership is [PipeBlock] positions, so
	 * in practice this never drops anything.
	 */
	private fun unionShape(level: BlockGetter, members: List<BlockPos>): VoxelShape? {
		var merged: VoxelShape? = null
		for (member in members) {
			val blockState = level.getBlockState(member)
			if (blockState.block !is PipeBlock) continue
			val shape = blockState.getShape(level, member, CollisionContext.empty())
			if (shape.isEmpty) continue
			val absolute = shape.move(member.x.toDouble(), member.y.toDouble(), member.z.toDouble())
			merged = merged?.let { Shapes.join(it, absolute, BooleanOp.OR) } ?: absolute
		}
		return merged
	}

	private fun sourceBox(consumer: VertexConsumer, poseStack: PoseStack, pos: BlockPos, cam: Vec3) {
		LevelRenderer.renderLineBox(
			poseStack, consumer,
			pos.x - SOURCE_EXPAND - cam.x, pos.y - SOURCE_EXPAND - cam.y, pos.z - SOURCE_EXPAND - cam.z,
			pos.x + 1 + SOURCE_EXPAND - cam.x, pos.y + 1 + SOURCE_EXPAND - cam.y, pos.z + 1 + SOURCE_EXPAND - cam.z,
			SOURCE_RED, SOURCE_GREEN, SOURCE_BLUE, SOURCE_ALPHA,
		)
	}

	private fun line(
		consumer: VertexConsumer,
		pose: PoseStack.Pose,
		from: BlockPos,
		to: BlockPos,
		cam: Vec3,
		r: Float,
		g: Float,
		blue: Float,
		alpha: Float,
	) {
		consumer.addVertex(pose, (from.x + 0.5 - cam.x).toFloat(), (from.y + 0.5 - cam.y).toFloat(), (from.z + 0.5 - cam.z).toFloat())
			.setColor(r, g, blue, alpha)
			.setNormal(0f, 0f, 1f)
		consumer.addVertex(pose, (to.x + 0.5 - cam.x).toFloat(), (to.y + 0.5 - cam.y).toFloat(), (to.z + 0.5 - cam.z).toFloat())
			.setColor(r, g, blue, alpha)
			.setNormal(0f, 0f, 1f)
	}

	private fun unpack(color: Int): Triple<Float, Float, Float> {
		val r = (color shr 16 and 0xff) / 255f
		val g = (color shr 8 and 0xff) / 255f
		val b = (color and 0xff) / 255f
		return Triple(r, g, b)
	}
}
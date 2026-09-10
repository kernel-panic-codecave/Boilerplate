package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.floats.FloatArrayList
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.network.EdgeKind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Draws the route-search overlay into the world frame: one wireframe per pipe network that hugs
 * the nodes' real block geometry - each member's [PipeBlock] core/arm/hook shape (promoted
 * [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock] segments and encasements included)
 * unioned into a single [VoxelShape] per network, each network in its own hue; the source of every
 * recent extract's route search; every hop that search considered (pipes it walked, boundary edges
 * it stopped at, destinations it probed and ones that rejected the item); and the route it
 * actually settled on, bright yellow.
 *
 * Only ever reached while [net.kernelpanicsoft.boilerplate.debug.DebugFlag.NETWORK] is on -
 * [net.kernelpanicsoft.boilerplate.debug.client.DebugOverlay] owns the dispatch and
 * [net.kernelpanicsoft.boilerplate.debug.client.DebugFlags] the announcement of it to the server,
 * so route-search tracing and snapshot broadcasting only run while at least one player has asked
 * for this particular overlay, on any environment, dev or production.
 *
 * Delta-free: it draws boxes and line segments under the frame's rotation-only pose stack at
 * camera-relative coordinates, exactly like vanilla's hit outline, then flushes its own
 * `RenderType.lines()` batch so the overlay is deterministic regardless of what the rest of the
 * level pass buffers.
 *
 * Every line the overlay draws is *baked* off the frame path into flat coordinate arrays (see
 * [Wireframe] and [TraceLines]) and only re-baked when the geometry behind it actually changes, so
 * a frame costs one pass over those arrays and nothing else - no voxel-shape traversal, no shape
 * unioning, and no per-vertex allocation.
 */
object DebugNetworkRenderer {
	/** The [DebugNetworkCache.snapshot]'s network list the current [wireframes] were baked from - a fresh snapshot replaces the list instance, so identity is the cheap first-line invalidation signal. */
	private var wireframesKey: List<DebugNetworkCache.Network>? = null

	/** One baked wireframe per network in [wireframesKey], same order; networks with nothing pipe-shaped to draw are dropped. */
	private var wireframes: List<Wireframe> = emptyList()

	/** The previous bake's wireframes by network id, so a snapshot that re-sends unchanged geometry reuses them instead of re-unioning (see [Wireframe.fingerprint]). */
	private var wireframesById: Map<String, Wireframe> = emptyMap()

	/** The level [wireframesById] was baked against - network ids are only meaningful within one level, so a dimension switch has to void the reuse map rather than risk drawing the previous world's pipes. */
	private var wireframesLevel: ClientLevel? = null

	/** The [DebugNetworkCache.snapshot]'s trace list the current [traceLines] were baked from - same copy-on-write identity signal as [wireframesKey]. */
	private var traceLinesKey: List<DebugNetworkCache.Trace>? = null

	/** One baked segment batch per trace in [traceLinesKey], same order. */
	private var traceLines: List<TraceLines> = emptyList()

	/** Scratch destination for the per-vertex pose transforms, reused across the whole frame - vanilla's `addVertex(Pose, ...)`/`setNormal(Pose, ...)` helpers allocate a fresh `Vector3f` per call, which at overlay vertex counts is pure garbage. */
	private val scratch = Vector3f()

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

	/**
	 * One network's outline, flattened: [points] holds each line segment's two absolute-world
	 * endpoints (stride 6) and [normals] that segment's unit direction (stride 3), in the order
	 * [VoxelShape.forAllEdges] produced them. [fingerprint] identifies the member geometry the bake
	 * came from, so an unchanged network survives the next snapshot untouched.
	 */
	private class Wireframe(
		val fingerprint: Long,
		val points: DoubleArray,
		val normals: FloatArray,
		val red: Float,
		val green: Float,
		val blue: Float,
	)

	/**
	 * One route search's lines, flattened: the search origin [from] (drawn as a box), then every
	 * explored hop followed by the settled route, as absolute-world endpoint pairs in [points]
	 * (stride 6) with the matching RGBA in [colors] (stride 4). Emission order matches the
	 * unbaked renderer's exactly, which line blending depends on.
	 */
	private class TraceLines(val from: BlockPos, val points: DoubleArray, val colors: FloatArray)

	fun renderFrame(poseStack: PoseStack, source: MultiBufferSource.BufferSource) {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level
		DebugNetworkCache.onLevel(level)
		if (level == null) return

		val (networks, traces) = DebugNetworkCache.snapshot()
		if (networks.isEmpty() && traces.isEmpty()) return

		val cam = minecraft.gameRenderer.mainCamera.position
		val consumer = source.getBuffer(RenderType.lines())
		val pose = poseStack.last()

		for (wireframe in bakeWireframes(networks, level)) {
			drawWireframe(consumer, pose, wireframe, cam)
		}

		for (trace in bakeTraceLines(traces)) {
			sourceBox(consumer, poseStack, trace.from, cam)
			drawTraceLines(consumer, pose, trace, cam)
		}

		source.endBatch(RenderType.lines())
	}

	/**
	 * The baked per-network wireframes, rebuilt only when a fresh snapshot replaces the network
	 * list (see [wireframesKey]) *and* that network's member geometry actually differs from the
	 * previous bake ([fingerprintOf]) - a snapshot that re-sends the same pipes reuses the arrays
	 * verbatim, so unioning and [VoxelShape.forAllEdges] run only on a genuine world change.
	 */
	private fun bakeWireframes(networks: List<DebugNetworkCache.Network>, level: ClientLevel): List<Wireframe> {
		if (networks === wireframesKey && level === wireframesLevel) return wireframes

		val previous = if (level === wireframesLevel) wireframesById else emptyMap()
		val baked = ArrayList<Wireframe>(networks.size)
		val byId = HashMap<String, Wireframe>(networks.size)
		val shapes = ArrayList<VoxelShape>()
		for (network in networks) {
			shapes.clear()
			val fingerprint = collectShapes(level, network.members, shapes)
			val reused = previous[network.id]?.takeIf { it.fingerprint == fingerprint }
			val wireframe = reused ?: bakeWireframe(fingerprint, shapes, network.color) ?: continue
			baked += wireframe
			byId[network.id] = wireframe
		}

		wireframesKey = networks
		wireframes = baked
		wireframesById = byId
		wireframesLevel = level
		return baked
	}

	/**
	 * Appends each of [members]' real block geometry to [into] as an absolute-coordinate
	 * [VoxelShape] - the position's own [PipeBlock] shape (its core with per-face arms where
	 * connected; a promoted [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock] delegates
	 * the same [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.getShape] machinery, encasement
	 * casings and hook housings included) shifted to its block offset. Non-pipe positions are
	 * skipped defensively - network membership is [PipeBlock] positions, so in practice this never
	 * drops anything.
	 *
	 * Returns a fingerprint over the collected boxes, which is what tells [bakeWireframes] whether
	 * anything about this network's geometry moved since the last bake.
	 */
	private fun collectShapes(level: BlockGetter, members: List<BlockPos>, into: MutableList<VoxelShape>): Long {
		var fingerprint = 1L
		for (member in members) {
			val blockState = level.getBlockState(member)
			if (blockState.block !is PipeBlock) continue
			val shape = blockState.getShape(level, member, CollisionContext.empty())
			if (shape.isEmpty) continue
			into += shape.move(member.x.toDouble(), member.y.toDouble(), member.z.toDouble())
			fingerprint = fingerprintOf(fingerprint, member, shape)
		}
		return fingerprint
	}

	/** Mixes [pos] and every box of its [shape] into [seed] - exact enough that any change to a member's rendered geometry (connection, hook, encasement) lands on a different value. */
	private fun fingerprintOf(seed: Long, pos: BlockPos, shape: VoxelShape): Long {
		var hash = seed * 31L + pos.asLong()
		shape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
			hash = hash * 31L + minX.toRawBits()
			hash = hash * 31L + minY.toRawBits()
			hash = hash * 31L + minZ.toRawBits()
			hash = hash * 31L + maxX.toRawBits()
			hash = hash * 31L + maxY.toRawBits()
			hash = hash * 31L + maxZ.toRawBits()
		}
		return hash
	}

	/**
	 * Unions [shapes] into one outline and flattens its edges into a [Wireframe], so the network
	 * renders as a wireframe hugging the actual pipe bodies rather than full-block volumes.
	 *
	 * The fold is [Shapes.joinUnoptimized] rather than [Shapes.join] deliberately: `join`'s
	 * trailing `optimize()` re-derives the merged shape box by box, which is quadratic in the
	 * accumulated shape and buys nothing here - a finer voxel grid describes the same solid, and
	 * [VoxelShape.forAllEdges] only ever emits the solid's outline, so the baked segments are
	 * identical either way.
	 */
	private fun bakeWireframe(fingerprint: Long, shapes: List<VoxelShape>, color: Int): Wireframe? {
		var merged: VoxelShape? = null
		for (shape in shapes) {
			merged = merged?.let { Shapes.joinUnoptimized(it, shape, BooleanOp.OR) } ?: shape
		}
		if (merged == null) return null

		val points = DoubleArrayList()
		val normals = FloatArrayList()
		merged.forAllEdges { x1, y1, z1, x2, y2, z2 ->
			val dx = (x2 - x1).toFloat()
			val dy = (y2 - y1).toFloat()
			val dz = (z2 - z1).toFloat()
			val length = Mth.sqrt(dx * dx + dy * dy + dz * dz)
			points.add(x1); points.add(y1); points.add(z1)
			points.add(x2); points.add(y2); points.add(z2)
			normals.add(dx / length); normals.add(dy / length); normals.add(dz / length)
		}
		return Wireframe(
			fingerprint,
			points.toDoubleArray(),
			normals.toFloatArray(),
			(color shr 16 and 0xff) / 255f,
			(color shr 8 and 0xff) / 255f,
			(color and 0xff) / 255f,
		)
	}

	/** The baked per-trace segment batches, rebuilt only when a fresh snapshot replaces the trace list (see [traceLinesKey]). */
	private fun bakeTraceLines(traces: List<DebugNetworkCache.Trace>): List<TraceLines> {
		if (traces === traceLinesKey) return traceLines

		val baked = traces.map { trace ->
			val points = DoubleArrayList()
			val colors = FloatArrayList()
			for ((from, to, kind) in trace.edges) {
				if (from == to) continue
				val color = when (kind) {
					EdgeKind.TRANSIT.ordinal -> TRANSIT_COLOR
					EdgeKind.BOUNDARY.ordinal -> BOUNDARY_COLOR
					EdgeKind.CANDIDATE.ordinal -> CANDIDATE_COLOR
					else -> REJECTED_COLOR
				}
				segment(points, colors, from, to, (color shr 16 and 0xff) / 255f, (color shr 8 and 0xff) / 255f, (color and 0xff) / 255f, EDGE_ALPHA)
			}
			var previous: BlockPos? = null
			for (point in trace.route) {
				val prev = previous
				if (prev != null && prev != point) {
					segment(points, colors, prev, point, ROUTE_RED, ROUTE_GREEN, ROUTE_BLUE, ROUTE_ALPHA)
				}
				previous = point
			}
			TraceLines(trace.from, points.toDoubleArray(), colors.toFloatArray())
		}

		traceLinesKey = traces
		traceLines = baked
		return baked
	}

	/** Appends the block-center-to-block-center segment [from] -> [to] and its color to a trace bake. */
	private fun segment(
		points: DoubleArrayList,
		colors: FloatArrayList,
		from: BlockPos,
		to: BlockPos,
		red: Float,
		green: Float,
		blue: Float,
		alpha: Float,
	) {
		points.add(from.x + 0.5); points.add(from.y + 0.5); points.add(from.z + 0.5)
		points.add(to.x + 0.5); points.add(to.y + 0.5); points.add(to.z + 0.5)
		colors.add(red); colors.add(green); colors.add(blue); colors.add(alpha)
	}

	private fun drawWireframe(consumer: VertexConsumer, pose: PoseStack.Pose, wireframe: Wireframe, cam: Vec3) {
		val points = wireframe.points
		val normals = wireframe.normals
		val matrix = pose.pose()
		var point = 0
		var normal = 0
		while (point < points.size) {
			pose.transformNormal(normals[normal], normals[normal + 1], normals[normal + 2], scratch)
			val normalX = scratch.x
			val normalY = scratch.y
			val normalZ = scratch.z
			vertex(consumer, matrix, points[point] - cam.x, points[point + 1] - cam.y, points[point + 2] - cam.z)
				.setColor(wireframe.red, wireframe.green, wireframe.blue, NODE_ALPHA)
				.setNormal(normalX, normalY, normalZ)
			vertex(consumer, matrix, points[point + 3] - cam.x, points[point + 4] - cam.y, points[point + 5] - cam.z)
				.setColor(wireframe.red, wireframe.green, wireframe.blue, NODE_ALPHA)
				.setNormal(normalX, normalY, normalZ)
			point += 6
			normal += 3
		}
	}

	private fun drawTraceLines(consumer: VertexConsumer, pose: PoseStack.Pose, trace: TraceLines, cam: Vec3) {
		val points = trace.points
		val colors = trace.colors
		val matrix = pose.pose()
		var point = 0
		var color = 0
		while (point < points.size) {
			val red = colors[color]
			val green = colors[color + 1]
			val blue = colors[color + 2]
			val alpha = colors[color + 3]
			vertex(consumer, matrix, points[point] - cam.x, points[point + 1] - cam.y, points[point + 2] - cam.z)
				.setColor(red, green, blue, alpha)
				.setNormal(0f, 0f, 1f)
			vertex(consumer, matrix, points[point + 3] - cam.x, points[point + 4] - cam.y, points[point + 5] - cam.z)
				.setColor(red, green, blue, alpha)
				.setNormal(0f, 0f, 1f)
			point += 6
			color += 4
		}
	}

	/** `VertexConsumer.addVertex(Matrix4f, ...)` without its per-call `Vector3f`, which at overlay vertex counts is the frame's dominant allocation. */
	private fun vertex(consumer: VertexConsumer, matrix: Matrix4f, x: Double, y: Double, z: Double): VertexConsumer {
		matrix.transformPosition(x.toFloat(), y.toFloat(), z.toFloat(), scratch)
		return consumer.addVertex(scratch.x, scratch.y, scratch.z)
	}

	private fun sourceBox(consumer: VertexConsumer, poseStack: PoseStack, pos: BlockPos, cam: Vec3) {
		LevelRenderer.renderLineBox(
			poseStack, consumer,
			pos.x - SOURCE_EXPAND - cam.x, pos.y - SOURCE_EXPAND - cam.y, pos.z - SOURCE_EXPAND - cam.z,
			pos.x + 1 + SOURCE_EXPAND - cam.x, pos.y + 1 + SOURCE_EXPAND - cam.y, pos.z + 1 + SOURCE_EXPAND - cam.z,
			SOURCE_RED, SOURCE_GREEN, SOURCE_BLUE, SOURCE_ALPHA,
		)
	}
}

package net.kernelpanicsoft.tubularstorage.warehouse.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.instance.Instancer
import dev.engine_room.flywheel.api.model.IndexSequence
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.vertex.MutableVertexList
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.math.MoreMath
import dev.engine_room.flywheel.lib.model.LineModelBuilder
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.SingleMeshModel
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.task.SimplePlan
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.util.itemStack
import net.kernelpanicsoft.tubularstorage.warehouse.GantryClientCache
import net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock
import net.kernelpanicsoft.tubularstorage.warehouse.GantryVisualState
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseScale
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.joml.Vector4f
import org.joml.Vector4fc
import org.lwjgl.system.MemoryUtil
import java.util.function.Consumer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class WarehouseControllerVisual(
	visualizationContext: VisualizationContext,
	blockEntity: WarehouseControllerBlockEntity,
	partialTick: Float
) : AbstractBlockEntityVisual<WarehouseControllerBlockEntity>(visualizationContext, blockEntity, partialTick), DynamicVisual {

	private val xRailInstances = ArrayList<TransformedInstance>()
	private val zRailInstances = ArrayList<TransformedInstance>()
	private val yRodInstances = ArrayList<TransformedInstance>()
	private var bottomRodInstance: TransformedInstance? = null
	private var headInstance: TransformedInstance? = null
	private var outlineInstance: TransformedInstance? = null
	private var carriedItemInstances: List<TransformedInstance> = emptyList()
	private var carriedItemsCacheKey: List<ResourceStack<ItemResource>> = emptyList()

	companion object {
		val HEAD_MODEL_RL: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
			TubularStorage.MOD_ID,
			"block/gantry_head"
		)
		val HEAD_PARTIAL_MODEL = PartialModel.of(HEAD_MODEL_RL)

		/**
		 * A wireframe box slightly larger than a full block, centered on the head (same local-space
		 * convention as [headInstance] - local `[0,1]` maps to world `[head - 0.5, head + 0.5]`) -
		 * [GantryVisualState]'s own colored feedback indicator, tinted per-instance via
		 * [dev.engine_room.flywheel.lib.instance.ColoredLitInstance.color] rather than baked into the
		 * mesh, so the same model works for every state.
		 */
		val OUTLINE_MODEL: Model by lazy {
			val lo = -0.1f
			val hi = 1.1f
			LineModelBuilder()
				.line(lo, lo, lo, hi, lo, lo)
				.line(hi, lo, lo, hi, lo, hi)
				.line(hi, lo, hi, lo, lo, hi)
				.line(lo, lo, hi, lo, lo, lo)
				.line(lo, hi, lo, hi, hi, lo)
				.line(hi, hi, lo, hi, hi, hi)
				.line(hi, hi, hi, lo, hi, hi)
				.line(lo, hi, hi, lo, hi, lo)
				.line(lo, lo, lo, lo, hi, lo)
				.line(hi, lo, lo, hi, hi, lo)
				.line(hi, lo, hi, hi, hi, hi)
				.line(lo, lo, hi, lo, hi, hi)
				.build()
		}

		private val INDEXING_COLOR = intArrayOf(255, 204, 0)
		private val MOVING_COLOR = intArrayOf(51, 204, 255)

		private const val CARRIED_ITEM_Y_OFFSET = 0.65f
		private const val CARRIED_ITEM_RADIUS = 0.3f
		private const val CARRIED_ITEM_SCALE = 0.4f
		private const val CARRIED_ITEM_SPIN_DEGREES_PER_TICK = 3.0

		/**
		 * [itemStack]'s own baked model, re-baked into a plain triangle-list [Mesh] the same way
		 * [buildClippedRodMesh] does - but with [ItemDisplayContext.GROUND]'s transform (the same one
		 * [net.minecraft.client.renderer.entity.ItemRenderer.renderStatic] itself would apply)
		 * pre-applied to every vertex once here, rather than reapplied via the instance's own
		 * transform every frame, since it never actually changes for a given item.
		 */
		private fun buildCarriedItemMesh(itemStack: ItemStack): ClippedRodMesh {
			val model = Minecraft.getInstance().itemRenderer.getModel(itemStack, null, null, 0)
			val pose = PoseStack()
			model.transforms.getTransform(ItemDisplayContext.GROUND).apply(false, pose)
			pose.translate(-0.5, -0.5, -0.5)
			val matrix = pose.last().pose()
			val normalMatrix = pose.last().normal()

			val vertices = ArrayList<RodVertex>()
			for (quad in model.getQuads(null, null, RandomSource.create())) {
				val transformed = decodeQuadVertices(quad).map { point ->
					val transformedPos = matrix.transformPosition(Vector3f(point.x, point.y, point.z))
					ClipPoint(transformedPos.x(), transformedPos.y(), transformedPos.z(), point.u, point.v)
				}
				val rawNormal = quad.direction.normal
				val normal = normalMatrix.transform(Vector3f(rawNormal.x.toFloat(), rawNormal.y.toFloat(), rawNormal.z.toFloat())).normalize()
				for (i in 1 until transformed.size - 1) {
					vertices += RodVertex(transformed[0], normal.x(), normal.y(), normal.z())
					vertices += RodVertex(transformed[i], normal.x(), normal.y(), normal.z())
					vertices += RodVertex(transformed[i + 1], normal.x(), normal.y(), normal.z())
				}
			}
			return ClippedRodMesh(vertices)
		}

		private val DOWN_PROPERTY by lazy { GantryRailBlock.propertiesByDirection.getValue(Direction.DOWN) }
		private val UP_PROPERTY by lazy { GantryRailBlock.propertiesByDirection.getValue(Direction.UP) }
		private val WEST_PROPERTY by lazy { GantryRailBlock.propertiesByDirection.getValue(Direction.WEST) }
		private val EAST_PROPERTY by lazy { GantryRailBlock.propertiesByDirection.getValue(Direction.EAST) }
		private val NORTH_PROPERTY by lazy { GantryRailBlock.propertiesByDirection.getValue(Direction.NORTH) }
		private val SOUTH_PROPERTY by lazy { GantryRailBlock.propertiesByDirection.getValue(Direction.SOUTH) }

		private const val VERTEX_STRIDE_INTS = 8

		/** A conservative bounding sphere for [buildClippedRodMesh]'s output - always a subset of the unmodified unit cube, whatever the clip boundary. */
		private val UNIT_CUBE_BOUNDING_SPHERE: Vector4fc = Vector4f(0.5f, 0.5f, 0.5f, MoreMath.SQRT_3_OVER_2)

		/** One (position, UV) pair from a [BakedQuad]'s own packed vertex data, clip-plane math only - unlike [WarehouseControllerBlockEntityRenderer]'s equivalent, no brightness/light is carried, since every instance in this class (this clipped one included) is flat-lit through [relight] alone rather than smooth per-vertex lighting. */
		private data class ClipPoint(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

		/** [quad]'s 4 vertices decoded from [BakedQuad.getVertices]'s packed `DefaultVertexFormat.BLOCK` layout - position and UV only, in the model's own local (pre-transform) space. */
		private fun decodeQuadVertices(quad: BakedQuad): Array<ClipPoint> {
			val raw = quad.vertices
			return Array(4) { i ->
				val base = i * VERTEX_STRIDE_INTS
				ClipPoint(
					x = Float.fromBits(raw[base]),
					y = Float.fromBits(raw[base + 1]),
					z = Float.fromBits(raw[base + 2]),
					u = Float.fromBits(raw[base + 4]),
					v = Float.fromBits(raw[base + 5]),
				)
			}
		}

		/** Sutherland-Hodgman clip of the (convex, planar) [vertices] polygon against the horizontal plane `y = [clipY]`, keeping the side at or above it - the same idea as [WarehouseControllerBlockEntityRenderer]'s `clipBelow`, minus the brightness/light interpolation it also carries. */
		private fun clipAboveY(vertices: Array<ClipPoint>, clipY: Float): List<ClipPoint> {
			val result = ArrayList<ClipPoint>(vertices.size + 1)
			for (i in vertices.indices) {
				val current = vertices[i]
				val previous = vertices[(i + vertices.size - 1) % vertices.size]
				val currentInside = current.y >= clipY
				val previousInside = previous.y >= clipY
				if (currentInside != previousInside) {
					val t = (clipY - previous.y) / (current.y - previous.y)
					result += ClipPoint(
						x = previous.x + (current.x - previous.x) * t,
						y = clipY,
						z = previous.z + (current.z - previous.z) * t,
						u = previous.u + (current.u - previous.u) * t,
						v = previous.v + (current.v - previous.v) * t,
					)
				}
				if (currentInside) result += current
			}
			return result
		}

		/**
		 * Clips [state]'s own baked quads against local `y = [clipY]` and fan-triangulates whatever
		 * survives into a fresh [Mesh] - the geometric "actually cut the model down" counterpart to
		 * [WarehouseControllerBlockEntityRenderer.drawClippedAt], built once per call rather than
		 * reused, since the bottom rod segment's own clip boundary moves every frame the head does.
		 */
		private fun buildClippedRodMesh(state: BlockState, clipY: Float): ClippedRodMesh {
			val model = Minecraft.getInstance().blockRenderer.blockModelShaper.getBlockModel(state)
			val vertices = ArrayList<RodVertex>()
			for (quad in model.getQuads(state, null, RandomSource.create())) {
				val clipped = clipAboveY(decodeQuadVertices(quad), clipY)
				if (clipped.size < 3) continue
				val normal = quad.direction.normal
				val nx = normal.x.toFloat()
				val ny = normal.y.toFloat()
				val nz = normal.z.toFloat()
				for (i in 1 until clipped.size - 1) {
					vertices += RodVertex(clipped[0], nx, ny, nz)
					vertices += RodVertex(clipped[i], nx, ny, nz)
					vertices += RodVertex(clipped[i + 1], nx, ny, nz)
				}
			}
			return ClippedRodMesh(vertices)
		}

		private data class RodVertex(val point: ClipPoint, val normalX: Float, val normalY: Float, val normalZ: Float)

		/** A plain (non-indexed) triangle list mesh, flat-lit and flat-shaded like every other instance in this class - `light`/`overlay` are placeholders [relight] and Flywheel's own overlay handling override at render time, not baked-in values. */
		private class ClippedRodMesh(private val vertices: List<RodVertex>) : Mesh {
			override fun vertexCount(): Int = vertices.size

			override fun write(vertexList: MutableVertexList) {
				for (i in vertices.indices) {
					val vertex = vertices[i]
					vertexList.x(i, vertex.point.x)
					vertexList.y(i, vertex.point.y)
					vertexList.z(i, vertex.point.z)
					vertexList.r(i, 1f)
					vertexList.g(i, 1f)
					vertexList.b(i, 1f)
					vertexList.a(i, 1f)
					vertexList.u(i, vertex.point.u)
					vertexList.v(i, vertex.point.v)
					vertexList.overlay(i, OverlayTexture.NO_OVERLAY)
					vertexList.light(i, 0)
					vertexList.normalX(i, vertex.normalX)
					vertexList.normalY(i, vertex.normalY)
					vertexList.normalZ(i, vertex.normalZ)
				}
			}

			override fun indexSequence(): IndexSequence = SequentialIndexSequence
			override fun indexCount(): Int = vertices.size
			override fun boundingSphere(): Vector4fc = UNIT_CUBE_BOUNDING_SPHERE
		}

		/** Identity index buffer (`0, 1, 2, ...`) for [ClippedRodMesh]'s plain (non-indexed) triangle list. */
		private object SequentialIndexSequence : IndexSequence {
			override fun fill(ptr: Long, vertexCount: Int) {
				for (i in 0 until vertexCount) {
					MemoryUtil.memPutInt(ptr + i * 4L, i)
				}
			}
		}
	}

	init {
		updateInstances(0f)
		updateLight(0f)
	}

	override fun update(partialTick: Float) {
		updateInstances(partialTick)
		updateLight(partialTick)
	}

	private fun updateInstances(partialTick: Float) {
		val bounds = blockEntity.bounds
		if (bounds == null) {
			clearInstances()
			return
		}

		val head = GantryClientCache.get(blockEntity.blockPos, WarehouseScale.fromBounds(bounds).baseSpeedPerTick)?.pos
			?: Vec3.atCenterOf(blockEntity.blockPos)

		val origin = blockEntity.blockPos
		val railY = bounds.max.y

		// Compute local offsets equivalent to (worldPos.x - origin.x - 0.5, etc.) in the BER
		val headOffsetX = (visualPos.x + (head.x - origin.x - 0.5)).toFloat()
		val headOffsetY = (visualPos.y + (head.y - origin.y - 0.5)).toFloat()
		val headOffsetZ = (visualPos.z + (head.z - origin.z - 0.5)).toFloat()

		val railYOffset = (visualPos.y + (railY + 0.5 - origin.y - 0.5)).toFloat() // equivalent to railY - origin.y

		// 1. X-Axis Crossbeams
		val xRange = bounds.min.x..bounds.max.x
		val xStates = xRange.map { x ->
			WarehouseControllerBlockEntityRenderer.connectionState(
				x, bounds.min.x, bounds.max.x, WEST_PROPERTY, EAST_PROPERTY
			)
		}

		if (xRailInstances.size != xStates.size) {
			xRailInstances.forEach(Instance::delete)
			xRailInstances.clear()
			for (state in xStates) {
				val instancer = instancerProvider().instancer(
					InstanceTypes.TRANSFORMED, Models.block(state)
				)
				xRailInstances.add(instancer.createInstance())
			}
		}

		for ((idx, x) in xRange.withIndex()) {
			val relX = (visualPos.x + (x + 0.5 - origin.x - 0.5)).toFloat()
			xRailInstances[idx].apply {
				setIdentityTransform()
				// Exactly matches BER: Vec3(x + 0.5, railY + 0.5, head.z)
				translate(relX, railYOffset, headOffsetZ)
				setChanged()
			}
		}

		// 2. Z-Axis Crossbeams
		val zRange = bounds.min.z..bounds.max.z
		val zStates = zRange.map { z ->
			WarehouseControllerBlockEntityRenderer.connectionState(
				z, bounds.min.z, bounds.max.z, NORTH_PROPERTY, SOUTH_PROPERTY
			)
		}

		if (zRailInstances.size != zStates.size) {
			zRailInstances.forEach(Instance::delete)
			zRailInstances.clear()
			for (state in zStates) {
				val instancer = instancerProvider().instancer(
					InstanceTypes.TRANSFORMED, Models.block(state)
				)
				zRailInstances.add(instancer.createInstance())
			}
		}

		for ((idx, z) in zRange.withIndex()) {
			val relZ = (visualPos.z + (z + 0.5 - origin.z - 0.5)).toFloat()
			zRailInstances[idx].apply {
				setIdentityTransform()
				// Exactly matches BER: Vec3(head.x, railY + 0.5, z + 0.5)
				translate(headOffsetX, railYOffset, relZ)
				setChanged()
			}
		}

		// 3. Y-Rods Assembly
		val bottomY = floor(head.y).toInt()
		val clipFraction = (head.y - bottomY).toFloat()

		val fullYStart = bottomY + 1
		val fullYRange = if (fullYStart <= railY) (fullYStart..railY) else IntRange.EMPTY
		val yStates = fullYRange.map { y ->
			WarehouseControllerBlockEntityRenderer.connectionState(y, bottomY, railY, DOWN_PROPERTY, UP_PROPERTY)
		}

		if (yRodInstances.size != yStates.size) {
			yRodInstances.forEach(Instance::delete)
			yRodInstances.clear()
			for (state in yStates) {
				val instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.block(state))
				yRodInstances.add(instancer.createInstance())
			}
		}

		for ((idx, y) in fullYRange.withIndex()) {
			val relY = (visualPos.y + (y + 0.5 - origin.y - 0.5)).toFloat()
			yRodInstances[idx].apply {
				setIdentityTransform()
				// Exactly matches BER: Vec3(head.x, y + 0.5, head.z)
				translate(headOffsetX, relY, headOffsetZ)
				setChanged()
			}
		}

		if (bottomY <= railY) {
			val state = WarehouseControllerBlockEntityRenderer.connectionState(
				bottomY, bottomY, railY, DOWN_PROPERTY, UP_PROPERTY
			)
			val mesh = buildClippedRodMesh(state, clipFraction)
			bottomRodInstance?.delete()
			val instancer = instancerProvider().instancer(
				InstanceTypes.TRANSFORMED, SingleMeshModel(mesh, Materials.CUTOUT_BLOCK)
			)
			val relBottomY = (visualPos.y + (bottomY + 0.5 - origin.y - 0.5)).toFloat()
			bottomRodInstance = instancer.createInstance().apply {
				setIdentityTransform()
				translate(headOffsetX, relBottomY, headOffsetZ)
				setChanged()
			}
		} else {
			bottomRodInstance?.delete()
			bottomRodInstance = null
		}

		if (headInstance == null) {
			val headModel: Model = BakedModelBuilder(HEAD_PARTIAL_MODEL.get())
				.materialFunc { _, _, _ -> Materials.CUTOUT_BLOCK }
				.build()
			val headInstancer: Instancer<TransformedInstance> = instancerProvider().instancer(
				InstanceTypes.TRANSFORMED, headModel
			)
			headInstance = headInstancer.createInstance()
		}
		headInstance?.apply {
			setIdentityTransform()
			// Exactly matches BER: head
			translate(headOffsetX, headOffsetY, headOffsetZ)
			setChanged()
		}

		val outlineColor = colorFor(blockEntity.visualState)
		if (outlineColor == null) {
			outlineInstance?.delete()
			outlineInstance = null
		} else {
			if (outlineInstance == null) {
				val outlineInstancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, OUTLINE_MODEL)
				outlineInstance = outlineInstancer.createInstance()
			}
			outlineInstance?.apply {
				setIdentityTransform()
				translate(headOffsetX, headOffsetY, headOffsetZ)
				color(outlineColor[0], outlineColor[1], outlineColor[2])
				setChanged()
			}
		}

		val carried = GantryClientCache.carriedItems(blockEntity.blockPos)
		if (carried != carriedItemsCacheKey) {
			carriedItemInstances.forEach(Instance::delete)
			carriedItemInstances = carried.map { stack ->
				val mesh = buildCarriedItemMesh(stack.itemStack)
				val instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, SingleMeshModel(mesh, Materials.CUTOUT_BLOCK))
				instancer.createInstance()
			}
			carriedItemsCacheKey = carried
		}

		if (carried.isNotEmpty()) {
			val spinDegrees = ((level?.gameTime ?: 0L) + partialTick) * CARRIED_ITEM_SPIN_DEGREES_PER_TICK
			for ((index, instance) in carriedItemInstances.withIndex()) {
				val totalDegrees = (spinDegrees + index * (360.0 / carried.size)).toFloat()
				val angle = Math.toRadians(totalDegrees.toDouble()).toFloat()
				instance.apply {
					setIdentityTransform()
					translate(
						headOffsetX + cos(angle) * CARRIED_ITEM_RADIUS,
						headOffsetY + CARRIED_ITEM_Y_OFFSET,
						headOffsetZ + sin(angle) * CARRIED_ITEM_RADIUS,
					)
					rotate(Axis.YP.rotationDegrees(totalDegrees))
					scale(CARRIED_ITEM_SCALE, CARRIED_ITEM_SCALE, CARRIED_ITEM_SCALE)
					setChanged()
				}
			}
		}
	}

	/** [GantryVisualState] -> outline tint (`[r, g, b]`, `0..255` each), or `null` for [GantryVisualState.IDLE] (no outline at all). */
	private fun colorFor(state: GantryVisualState): IntArray? = when (state) {
		GantryVisualState.INDEXING -> INDEXING_COLOR
		GantryVisualState.MOVING -> MOVING_COLOR
		GantryVisualState.IDLE -> null
	}

	/** Deletes and clears every pooled instance - the unbound (`bounds == null`) state, and [_delete]. */
	private fun clearInstances() {
		xRailInstances.forEach(Instance::delete)
		zRailInstances.forEach(Instance::delete)
		yRodInstances.forEach(Instance::delete)
		xRailInstances.clear()
		zRailInstances.clear()
		yRodInstances.clear()
		bottomRodInstance?.delete()
		bottomRodInstance = null
		headInstance?.delete()
		headInstance = null
		outlineInstance?.delete()
		outlineInstance = null
		carriedItemInstances.forEach(Instance::delete)
		carriedItemInstances = emptyList()
		carriedItemsCacheKey = emptyList()
	}

	override fun updateLight(partialTick: Float) {
		val bounds = blockEntity.bounds ?: return
		val headWorld = GantryClientCache.get(blockEntity.blockPos, WarehouseScale.fromBounds(bounds).baseSpeedPerTick)?.pos
			?: Vec3.atCenterOf(blockEntity.blockPos)

		val railYInt = bounds.max.y
		val headBlockPos = BlockPos.containing(headWorld)

		// Relight X Rails
		for ((xIdx, x) in (bounds.min.x..bounds.max.x).withIndex()) {
			if (xIdx < xRailInstances.size) {
				relight(BlockPos(x, railYInt, headBlockPos.z), xRailInstances[xIdx])
			}
		}

		// Relight Z Rails
		for ((zIdx, z) in (bounds.min.z..bounds.max.z).withIndex()) {
			if (zIdx < zRailInstances.size) {
				relight(BlockPos(headBlockPos.x, railYInt, z), zRailInstances[zIdx])
			}
		}

		// Relight Y Rods
		val bottomY = floor(headWorld.y).toInt()
		val fullYStart = bottomY + 1
		var yIdx = 0
		if (fullYStart <= railYInt) {
			for (y in fullYStart..railYInt) {
				if (yIdx < yRodInstances.size) {
					relight(BlockPos(headBlockPos.x, y, headBlockPos.z), yRodInstances[yIdx])
					yIdx++
				}
			}
		}

		val lightPos = if (level?.getBlockState(headBlockPos)?.isRedstoneConductor(level, headBlockPos) == true) {
			headBlockPos.above()
		} else {
			headBlockPos
		}

		bottomRodInstance?.let { relight(lightPos, it) }
		headInstance?.let { relight(lightPos, it) }
		carriedItemInstances.forEach { relight(lightPos, it) }
	}

	override fun _delete() = clearInstances()

	override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
		xRailInstances.forEach(consumer::accept)
		zRailInstances.forEach(consumer::accept)
		yRodInstances.forEach(consumer::accept)
		bottomRodInstance?.let(consumer::accept)
		headInstance?.let(consumer::accept)
	}

	private fun beginFrame(context: DynamicVisual.Context) {
		updateInstances(context.partialTick())
		updateLight(context.partialTick())
	}

	override fun planFrame(): Plan<DynamicVisual.Context> = SimplePlan.of({ context ->
		beginFrame(context)
	})
}
package net.kernelpanicsoft.boilerplate.warehouse.client

import com.mojang.math.Axis
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.instance.Instancer
import dev.engine_room.flywheel.api.model.IndexSequence
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.vertex.MutableVertexList
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.math.MoreMath
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.SingleMeshModel
import net.kernelpanicsoft.boilerplate.client.WorldMeshMotion
import net.kernelpanicsoft.boilerplate.client.preferredMaterial
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.task.SimplePlan
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.SResourceStack
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.*
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerVisual.Companion.buildClippedRodMesh
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.OverlayTexture
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.FrustumIntersection
import org.joml.Vector4f
import org.joml.Vector4fc
import org.lwjgl.system.MemoryUtil
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.util.div
import net.kernelpanicsoft.archie.util.rem

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
	private var carriedItemInstances: List<TransformedInstance> = emptyList()
	private var carriedItemsCacheKey: List<ResourceStack<ResourceComponent>> = emptyList()

	companion object {
		val HEAD_MODEL_RL: ResourceLocation = Boilerplate.MOD % "block" / "gantry_head"
		val HEAD_PARTIAL_MODEL: PartialModel = PartialModel.of(HEAD_MODEL_RL)

		/** Half the head model's own extent - `block/gantry_head` spans `[3,3,3]`..`[13,13,13]`, so 5/16 either side of its block cell's centre. Every head-relative offset is derived from this rather than assuming a full 16x16x16 block. */
		private const val HEAD_HALF_EXTENT = 5f / 16f

		/** Clear of the head's own top face by a small gap. */
		private const val CARRIED_ITEM_Y_OFFSET = HEAD_HALF_EXTENT + 0.12f
		/** Kept inside the head's own footprint, so a batch orbits over it rather than out past its corners. */
		private const val CARRIED_ITEM_RADIUS = HEAD_HALF_EXTENT * 0.7f
		private const val CARRIED_ITEM_SPIN_DEGREES_PER_TICK = 3.0

		/** The crane's own copy of the droplet wobble - see [net.kernelpanicsoft.boilerplate.pipe.client.TravelingItemInstances] for what each of these means. */
		private const val WOBBLE_AMPLITUDE = 0.14f
		private const val WOBBLE_RADIANS_PER_DEGREE = 0.18f
		private const val WOBBLE_PHASE_OFFSET = 1.7f
		private const val TUMBLE_X_PER_DEGREE = 0.9f
		private const val TUMBLE_Z_PER_DEGREE = 0.63f

		/** Ceiling on how many chunk sections one warehouse tracks for lighting - see [setSectionCollector]. */
		private const val MAX_TRACKED_SECTIONS = 512

		/** The baked mesh one carried stack draws as, via its own kind - `null` for a kind with no world visual. */
		private fun meshFor(stack: SResourceStack<*>): Mesh? {
			val resource = stack.resource as ResourceComponent
			return ResourceKindRegistry.forResource(resource)?.display?.worldMesh(resource, stack.amount)
		}

		/** How [stack]'s own kind moves while the crane carries it - see [WorldMeshMotion]. */
		private fun motionOf(stack: SResourceStack<*>?): WorldMeshMotion {
			val resource = stack?.resource as? ResourceComponent ?: return WorldMeshMotion.SPIN
			return ResourceKindRegistry.forResource(resource)?.display?.worldMeshMotion ?: WorldMeshMotion.SPIN
		}

		/**
		 * [net.kernelpanicsoft.boilerplate.registry.BlockRegistry.GantryRail]'s default state with
		 * [negativeProperty] connected iff [current] has a segment behind it (`current > min`) and
		 * [positiveProperty] connected iff it has one ahead (`current < max`) - so the two ends of a
		 * run only connect inward and cap off cleanly, rather than every segment (including the true
		 * ends) rendering as fully connected regardless of what is actually next to it.
		 */
		fun connectionState(
			current: Int,
			min: Int,
			max: Int,
			negativeProperty: BooleanProperty?,
			positiveProperty: BooleanProperty?,
		): BlockState {
			var state = BlockRegistry.GantryRail.defaultBlockState()
			if (negativeProperty != null) state = state.setValue(negativeProperty, current > min)
			if (positiveProperty != null) state = state.setValue(positiveProperty, current < max)
			return state
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

		/** One (position, UV) pair from a [BakedQuad]'s own packed vertex data, clip-plane math only - no brightness or light is carried, since every instance in this class is flat-lit through [relight] rather than smooth per-vertex lighting. */
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

		/** Sutherland-Hodgman clip of the (convex, planar) [vertices] polygon against the horizontal plane `y = [clipY]`, keeping the side at or above it. */
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
		 * a clipped rail segment, built once per call rather than
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

		val head = GantryClientCache.get(blockEntity.blockPos, (level?.gameTime ?: 0L) + partialTick.toDouble())?.pos
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
			connectionState(
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
			connectionState(
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
			connectionState(y, bottomY, railY, DOWN_PROPERTY, UP_PROPERTY)
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
			val state = connectionState(
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

		// Whatever each carried stack's own kind bakes itself as - an item's model, a fluid's
		// droplet. A kind with no world mesh simply isn't drawn on the crane, which is the kind's
		// own answer (see [net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind.worldMesh])
		// rather than a check here.
		val carried = GantryClientCache.carriedItems(blockEntity.blockPos).filter { meshFor(it) != null }
		if (carried != carriedItemsCacheKey) {
			carriedItemInstances.forEach(Instance::delete)
			carriedItemInstances = carried.map { stack ->
				// The mesh picks its own material, exactly as it does for pipe cargo - see MaterialMesh.
				val mesh = meshFor(stack)!!
				val model = SingleMeshModel(mesh, mesh.preferredMaterial())
				instancerProvider().instancer(InstanceTypes.TRANSFORMED, model).createInstance()
			}
			carriedItemsCacheKey = carried
		}

		if (carried.isNotEmpty()) {
			val spinDegrees = ((level?.gameTime ?: 0L) + partialTick) * CARRIED_ITEM_SPIN_DEGREES_PER_TICK
			// headOffset is the head cell's *corner* - the convention every block-shaped mesh here
			// wants, since those span [0,1] from their own origin. A carried stack's mesh is not one
			// of those: [InstancedMeshes] bakes both the recentre and the transport scale in, so it
			// is already centred about the origin at final size. Placing it at the corner offset put
			// every item half a block out along all three axes at once - i.e. off at the head's
			// corner - and scaling it again here would shrink it twice over.
			val headCenterX = headOffsetX + 0.5f
			val headCenterY = headOffsetY + 0.5f
			val headCenterZ = headOffsetZ + 0.5f
			// One item on a ring is just an item circling nothing, which reads as the same
			// off-to-one-side problem - centre it, and only orbit once there's more than one.
			val radius = if (carried.size == 1) 0f else CARRIED_ITEM_RADIUS
			for ((index, instance) in carriedItemInstances.withIndex()) {
				val totalDegrees = (spinDegrees + index * (360.0 / carried.size)).toFloat()
				val angle = Math.toRadians(totalDegrees.toDouble()).toFloat()
				instance.apply {
					setIdentityTransform()
					translate(
						headCenterX + cos(angle) * radius,
						headCenterY + CARRIED_ITEM_Y_OFFSET,
						headCenterZ + sin(angle) * radius,
					)
					rotate(Axis.YP.rotationDegrees(totalDegrees))
					// The same tumble and slosh a droplet gets in a pipe, so cargo does not change
					// character between the two places it is drawn - see [WorldMeshMotion].
					if (motionOf(carried.getOrNull(index)) == WorldMeshMotion.TUMBLE) {
						rotate(Axis.XP.rotationDegrees(spinDegrees.toFloat() * TUMBLE_X_PER_DEGREE))
						rotate(Axis.ZP.rotationDegrees(spinDegrees.toFloat() * TUMBLE_Z_PER_DEGREE))
						val wobble = spinDegrees.toFloat() * WOBBLE_RADIANS_PER_DEGREE + index * WOBBLE_PHASE_OFFSET
						scale(
							1f + WOBBLE_AMPLITUDE * sin(wobble),
							1f + WOBBLE_AMPLITUDE * sin(wobble + 2f * PI.toFloat() / 3f),
							1f + WOBBLE_AMPLITUDE * sin(wobble + 4f * PI.toFloat() / 3f),
						)
					}
					setChanged()
				}
			}
		}
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
		carriedItemInstances.forEach(Instance::delete)
		carriedItemInstances = emptyList()
		carriedItemsCacheKey = emptyList()
	}

	override fun updateLight(partialTick: Float) {
		val bounds = blockEntity.bounds ?: return
		val headWorld = GantryClientCache.get(blockEntity.blockPos, (level?.gameTime ?: 0L) + partialTick.toDouble())?.pos
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

	/**
	 * The whole bound warehouse volume, not the controller block.
	 *
	 * [AbstractBlockEntityVisual]'s default tests a sphere around the block itself, which is right
	 * for a visual whose geometry sits inside its own cell and wrong for this one: the gantry's
	 * rails, rod and head are drawn across the entire bound volume, so testing the controller's own
	 * cell made the whole crane vanish the instant that one block left the frustum. (The vanilla
	 * renderer this replaces had the same problem, patched on NeoForge only, by a mixin overriding
	 * `getRenderBoundingBox` - handled here once, for both loaders, instead.)
	 *
	 * Deliberately the bound volume rather than an always-visible box: the gantry can never draw
	 * outside it, so this never wrongly culls, while a camera genuinely pointed elsewhere still
	 * skips the per-frame work.
	 */
	override fun isVisible(frustum: FrustumIntersection): Boolean {
		val bounds = blockEntity.bounds ?: return super.isVisible(frustum)
		val origin = visualPos.subtract(pos)
		return frustum.testAab(
			(bounds.min.x + origin.x).toFloat(), (bounds.min.y + origin.y).toFloat(), (bounds.min.z + origin.z).toFloat(),
			(bounds.max.x + origin.x + 1).toFloat(), (bounds.max.y + origin.y + 1).toFloat(), (bounds.max.z + origin.z + 1).toFloat(),
		)
	}

	/**
	 * Tracks this visual in every section its volume touches, not just the controller's own.
	 *
	 * Flywheel relights a visual when one of its tracked sections changes; a gantry drawn across a
	 * whole warehouse would otherwise keep the lighting it happened to have when the controller's
	 * own section last updated. Capped at [MAX_TRACKED_SECTIONS] because a large warehouse is
	 * genuinely enormous and tracking thousands of sections costs more than the lighting accuracy is
	 * worth - past that it falls back to the controller's own section, which is what it did before.
	 */
	override fun setSectionCollector(sectionCollector: SectionTrackedVisual.SectionCollector) {
		super.setSectionCollector(sectionCollector)
		val bounds = blockEntity.bounds ?: return
		val sections = LongOpenHashSet()
		for (x in SectionPos.blockToSectionCoord(bounds.min.x)..SectionPos.blockToSectionCoord(bounds.max.x)) {
			for (y in SectionPos.blockToSectionCoord(bounds.min.y)..SectionPos.blockToSectionCoord(bounds.max.y)) {
				for (z in SectionPos.blockToSectionCoord(bounds.min.z)..SectionPos.blockToSectionCoord(bounds.max.z)) {
					if (sections.size >= MAX_TRACKED_SECTIONS) return
					sections.add(SectionPos.asLong(x, y, z))
				}
			}
		}
		sectionCollector.sections(sections)
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
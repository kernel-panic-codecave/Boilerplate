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
import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.FastColor
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.BitSet
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Renders the *moving* half of a warehouse gantry, styled after BuildCraft's Quarry: the static
 * perimeter is real [BlockRegistry.GantryRail] blocks placed by
 * [WarehouseControllerBlockEntity.bounds] (ordinary chunk sync handles those), but the two
 * crossbeams that slide to track the head, the vertical drop rod connecting down to it, and the
 * head itself all change every tick and aren't practical as real blocks - so they stay a
 * client-only dynamic render instead, drawn (dead-reckoned, from [GantryClientCache]) unconditionally
 * rather than only while actively mid-move: the head is always sitting somewhere, so the arm/head
 * stay visible parked there between jobs too, not just during a delivery. [GantryClientCache] has
 * no entry at all until the very first sync (nothing has ever moved since this controller loaded),
 * in which case [render] falls back to the controller's own position - exactly where a freshly
 * bound, never-yet-run gantry actually sits. [WarehouseControllerBlockEntity.bounds] is read
 * directly off [tile] rather than threaded through the sync packet, since it's already `@Sync`'d
 * independently. Vanilla only frustum-culls a block entity renderer by the
 * block's own single-block space otherwise (the same reason `BeaconBlockEntity`'s beam needs the
 * equivalent), which would otherwise cut the gantry off mid-render the moment the controller itself
 * scrolls off screen - [shouldRenderOffScreen] handles the vanilla/Fabric side of that, but NeoForge
 * layers its own separate bounding-box check
 * (`net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension.getRenderBoundingBox`)
 * on top even for entries `shouldRenderOffScreen` already exempted. That interface isn't visible
 * from common code at all (`common` compiles against a plain vanilla `BlockEntityRenderer`, not
 * NeoForge's patched one), so it can't be overridden here directly -
 * `net.kernelpanicsoft.tubularstorage.mixin.neoforge.client.WarehouseControllerBlockEntityRendererMixin`
 * injects the equivalent override into this class's own bytecode, NeoForge-side only.
 *
 * The crossbeams/rod reuse [GantryRailBlock]'s own baked model rather than any placed block. Each
 * segment's connected [BlockState] is computed from where it actually sits along its own run
 * ([connectionState]) rather than a single blanket "fully connected" state - the two segments at a
 * run's true ends only connect *inward*, so they cap off with a plain core face instead of an arm
 * stub poking into nothing. The rod's own bottom segment additionally renders through
 * [drawClippedAt] instead of a whole unit block: since the head's own Y is continuous but each
 * segment is a fixed one-block unit, the naive approach only ever shows a full or absent block,
 * popping into existence a whole block at a time as the head crosses each boundary. Clipping that
 * one segment's quads against a horizontal plane at the head's exact fractional height - in the
 * model's own local space, via Sutherland-Hodgman rather than any non-uniform scale - lets it grow
 * and shrink smoothly instead, without deforming the model itself (future hand-authored Blockbench
 * geometry couldn't tolerate being stretched). That clipped path computes real per-corner ambient
 * occlusion for its quads via [ModelBlockRenderer.AmbientOcclusionFace] - package-private in
 * vanilla with no public equivalent, unlocked here via `tubularstorage.accesswidener` - and
 * interpolates it at the clip boundary alongside position/UV, so the growing segment shades the
 * same way its unclipped siblings (rendered normally, through the ordinary `tesselateBlock` path)
 * already do. [modelFor] memoizes the resulting handful of distinct baked models, since the same
 * state recurs at every interior position. One beam spans the bound footprint's full X extent at
 * the head's current Z, the other spans the full Z extent at the head's current X, intersecting
 * directly above wherever the head is. The head itself keeps using [ItemRegistry.GantryHead]'s
 * placeholder model, a fake item that exists purely as a bake target for this renderer.
 */
class WarehouseControllerBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WarehouseControllerBlockEntity> {
	private val blockModelShaper = context.blockRenderDispatcher.blockModelShaper
	private val modelRenderer = context.blockRenderDispatcher.modelRenderer
	private val headModel = blockModelShaper.modelManager.getModel(HEAD_MODEL_ID)

	private val modelCache = HashMap<BlockState, BakedModel>()
	private fun modelFor(state: BlockState): BakedModel = modelCache.getOrPut(state) { blockModelShaper.getBlockModel(state) }

	override fun shouldRenderOffScreen(blockEntity: WarehouseControllerBlockEntity): Boolean = true

	override fun render(
		tile: WarehouseControllerBlockEntity,
		partialTick: Float,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int,
	) {
		val level = tile.level ?: return
		val bounds = tile.bounds ?: return
		val head = GantryClientCache.get(tile.blockPos)?.pos ?: Vec3.atCenterOf(tile.blockPos)

		val consumer = bufferSource.getBuffer(RenderType.solid())
		val railY = bounds.max.y

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
			val model = modelFor(state)
			if (y == bottomY) {
				val clipFraction = (head.y - bottomY).toFloat()
				drawClippedAt(tile.blockPos, Vec3(head.x, y + 0.5, head.z), level, poseStack, consumer, model, state, clipFraction, packedOverlay)
			} else {
				drawAt(tile.blockPos, Vec3(head.x, y + 0.5, head.z), level, poseStack, consumer, model, state, packedOverlay)
			}
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

	/** As [drawAt], but discards every quad's geometry below local Y [clipFraction] (0..1 up the segment's own unit cube) instead of drawing the whole block - see the class KDoc. */
	private fun drawClippedAt(
		originPos: BlockPos,
		worldPos: Vec3,
		level: Level,
		poseStack: PoseStack,
		consumer: VertexConsumer,
		model: BakedModel,
		state: BlockState,
		clipFraction: Float,
		packedOverlay: Int,
	) {
		if (clipFraction <= 0f) {
			drawAt(originPos, worldPos, level, poseStack, consumer, model, state, packedOverlay)
			return
		}

		val blockPos = BlockPos.containing(worldPos)
		poseStack.pushPose()
		poseStack.translate(worldPos.x - originPos.x - 0.5, worldPos.y - originPos.y - 0.5, worldPos.z - originPos.z - 0.5)
		val pose = poseStack.last()
		val ao = ModelBlockRenderer.AmbientOcclusionFace()
		val shape = FloatArray(12)
		val shapeFlags = BitSet(3)
		for (quad in model.getQuads(state, null, RandomSource.create())) {
			emitClippedQuad(quad, level, state, blockPos, pose, clipFraction, consumer, ao, shape, shapeFlags, packedOverlay)
		}
		poseStack.popPose()
	}

	private fun emitClippedQuad(
		quad: BakedQuad,
		level: Level,
		state: BlockState,
		blockPos: BlockPos,
		pose: PoseStack.Pose,
		clipY: Float,
		consumer: VertexConsumer,
		ao: ModelBlockRenderer.AmbientOcclusionFace,
		shape: FloatArray,
		shapeFlags: BitSet,
		packedOverlay: Int,
	) {
		calculateShape(level, state, blockPos, quad.vertices, quad.direction, shape, shapeFlags)
		ao.calculate(level, state, blockPos, quad.direction, shape, shapeFlags, quad.isShade)
		val clipped = clipBelow(decodeVertices(quad, ao.brightness, ao.lightmap), clipY)
		if (clipped.size < 3) return
		val rawNormal = quad.direction.normal
		val normal = pose.transformNormal(rawNormal.x.toFloat(), rawNormal.y.toFloat(), rawNormal.z.toFloat(), Vector3f())
		for (i in 1 until clipped.size - 1) {
			emitVertex(clipped[0], pose, normal, consumer, packedOverlay)
			emitVertex(clipped[i], pose, normal, consumer, packedOverlay)
			emitVertex(clipped[i + 1], pose, normal, consumer, packedOverlay)
			emitVertex(clipped[i + 1], pose, normal, consumer, packedOverlay)
		}
	}

	private fun emitVertex(vertex: ClipVertex, pose: PoseStack.Pose, normal: Vector3f, consumer: VertexConsumer, packedOverlay: Int) {
		val transformed = pose.pose().transformPosition(vertex.x, vertex.y, vertex.z, Vector3f())
		val gray = (vertex.brightness.coerceIn(0f, 1f) * 255f).roundToInt()
		val color = FastColor.ARGB32.color(255, gray, gray, gray)
		consumer.addVertex(transformed.x(), transformed.y(), transformed.z(), color, vertex.u, vertex.v, packedOverlay, vertex.packedLight, normal.x(), normal.y(), normal.z())
	}

	private data class ClipVertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float, val brightness: Float, val packedLight: Int)

	companion object {
		private val WEST_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.WEST)
		private val EAST_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.EAST)
		private val NORTH_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.NORTH)
		private val SOUTH_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.SOUTH)
		private val UP_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.UP)
		private val DOWN_PROPERTY = GantryRailBlock.propertiesByDirection.getValue(Direction.DOWN)

		private val HEAD_MODEL_ID = ModelResourceLocation(BuiltInRegistries.ITEM.getKey(ItemRegistry.GantryHead), "inventory")

		private const val VERTEX_STRIDE_INTS = 8
		private const val AO_EPSILON = 1.0E-4F
		private const val AO_NEARLY_ONE = 0.9999F

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

		/** [quad]'s 4 vertices decoded from [BakedQuad.getVertices]'s packed `DefaultVertexFormat.BLOCK` layout - position and UV in the model's own local (pre-transform) space, paired with the per-vertex [brightness]/[lightmap] [ModelBlockRenderer.AmbientOcclusionFace.calculate] already computed for this same quad. */
		private fun decodeVertices(quad: BakedQuad, brightness: FloatArray, lightmap: IntArray): Array<ClipVertex> {
			val raw = quad.vertices
			return Array(4) { i ->
				val base = i * VERTEX_STRIDE_INTS
				ClipVertex(
					x = Float.fromBits(raw[base]),
					y = Float.fromBits(raw[base + 1]),
					z = Float.fromBits(raw[base + 2]),
					u = Float.fromBits(raw[base + 4]),
					v = Float.fromBits(raw[base + 5]),
					brightness = brightness[i],
					packedLight = lightmap[i],
				)
			}
		}

		/** Sutherland-Hodgman clip of the (convex, planar) [vertices] polygon against the horizontal plane `y = [clipY]`, keeping the side at or above it - interpolating every attribute, including the baked [ClipVertex.brightness]/[ClipVertex.packedLight], at the new boundary vertices it introduces. */
		private fun clipBelow(vertices: Array<ClipVertex>, clipY: Float): List<ClipVertex> {
			val result = ArrayList<ClipVertex>(vertices.size + 1)
			for (i in vertices.indices) {
				val current = vertices[i]
				val previous = vertices[(i + vertices.size - 1) % vertices.size]
				val currentInside = current.y >= clipY
				val previousInside = previous.y >= clipY
				if (currentInside != previousInside) {
					val t = (clipY - previous.y) / (current.y - previous.y)
					result += ClipVertex(
						x = previous.x + (current.x - previous.x) * t,
						y = clipY,
						z = previous.z + (current.z - previous.z) * t,
						u = previous.u + (current.u - previous.u) * t,
						v = previous.v + (current.v - previous.v) * t,
						brightness = previous.brightness + (current.brightness - previous.brightness) * t,
						packedLight = lerpPackedLight(previous.packedLight, current.packedLight, t),
					)
				}
				if (currentInside) result += current
			}
			return result
		}

		/** Linearly interpolates a packed light value's block/sky halves independently, rather than the raw int (which doesn't interpolate meaningfully across the packed boundary). */
		private fun lerpPackedLight(a: Int, b: Int, t: Float): Int {
			val blockA = a and 0xFFFF
			val skyA = (a shr 16) and 0xFFFF
			val blockB = b and 0xFFFF
			val skyB = (b shr 16) and 0xFFFF
			val block = (blockA + (blockB - blockA) * t).roundToInt().coerceIn(0, 0xFFFF)
			val sky = (skyA + (skyB - skyA) * t).roundToInt().coerceIn(0, 0xFFFF)
			return (sky shl 16) or block
		}

		/**
		 * Reimplements `ModelBlockRenderer.calculateShape` (private, so not reachable even with
		 * [ModelBlockRenderer.AmbientOcclusionFace] itself widened) - the bounding-box/offset shape
		 * data [ModelBlockRenderer.AmbientOcclusionFace.calculate] needs as input, derived purely from
		 * [vertices]' own extents plus [state]'s collision shape, both public. Ported 1:1 from vanilla
		 * rather than simplified, since [state] here is always [BlockRegistry.GantryRail] but this has
		 * no reason to assume that shape stays simple forever.
		 */
		private fun calculateShape(level: Level, state: BlockState, pos: BlockPos, vertices: IntArray, direction: Direction, shape: FloatArray, shapeFlags: BitSet) {
			var minX = 32f
			var minY = 32f
			var minZ = 32f
			var maxX = -32f
			var maxY = -32f
			var maxZ = -32f
			for (i in 0 until 4) {
				val base = i * VERTEX_STRIDE_INTS
				val x = Float.fromBits(vertices[base])
				val y = Float.fromBits(vertices[base + 1])
				val z = Float.fromBits(vertices[base + 2])
				minX = minOf(minX, x)
				minY = minOf(minY, y)
				minZ = minOf(minZ, z)
				maxX = maxOf(maxX, x)
				maxY = maxOf(maxY, y)
				maxZ = maxOf(maxZ, z)
			}

			shape[Direction.WEST.get3DDataValue()] = minX
			shape[Direction.EAST.get3DDataValue()] = maxX
			shape[Direction.DOWN.get3DDataValue()] = minY
			shape[Direction.UP.get3DDataValue()] = maxY
			shape[Direction.NORTH.get3DDataValue()] = minZ
			shape[Direction.SOUTH.get3DDataValue()] = maxZ
			val offset = Direction.entries.size
			shape[Direction.WEST.get3DDataValue() + offset] = 1f - minX
			shape[Direction.EAST.get3DDataValue() + offset] = 1f - maxX
			shape[Direction.DOWN.get3DDataValue() + offset] = 1f - minY
			shape[Direction.UP.get3DDataValue() + offset] = 1f - maxY
			shape[Direction.NORTH.get3DDataValue() + offset] = 1f - minZ
			shape[Direction.SOUTH.get3DDataValue() + offset] = 1f - maxZ

			val fullBlock = state.isCollisionShapeFullBlock(level, pos)
			when (direction) {
				Direction.DOWN -> {
					shapeFlags.set(1, minX >= AO_EPSILON || minZ >= AO_EPSILON || maxX <= AO_NEARLY_ONE || maxZ <= AO_NEARLY_ONE)
					shapeFlags.set(0, minY == maxY && (minY < AO_EPSILON || fullBlock))
				}
				Direction.UP -> {
					shapeFlags.set(1, minX >= AO_EPSILON || minZ >= AO_EPSILON || maxX <= AO_NEARLY_ONE || maxZ <= AO_NEARLY_ONE)
					shapeFlags.set(0, minY == maxY && (maxY > AO_NEARLY_ONE || fullBlock))
				}
				Direction.NORTH -> {
					shapeFlags.set(1, minX >= AO_EPSILON || minY >= AO_EPSILON || maxX <= AO_NEARLY_ONE || maxY <= AO_NEARLY_ONE)
					shapeFlags.set(0, minZ == maxZ && (minZ < AO_EPSILON || fullBlock))
				}
				Direction.SOUTH -> {
					shapeFlags.set(1, minX >= AO_EPSILON || minY >= AO_EPSILON || maxX <= AO_NEARLY_ONE || maxY <= AO_NEARLY_ONE)
					shapeFlags.set(0, minZ == maxZ && (maxZ > AO_NEARLY_ONE || fullBlock))
				}
				Direction.WEST -> {
					shapeFlags.set(1, minY >= AO_EPSILON || minZ >= AO_EPSILON || maxY <= AO_NEARLY_ONE || maxZ <= AO_NEARLY_ONE)
					shapeFlags.set(0, minX == maxX && (minX < AO_EPSILON || fullBlock))
				}
				Direction.EAST -> {
					shapeFlags.set(1, minY >= AO_EPSILON || minZ >= AO_EPSILON || maxY <= AO_NEARLY_ONE || maxZ <= AO_NEARLY_ONE)
					shapeFlags.set(0, minX == maxX && (maxX > AO_NEARLY_ONE || fullBlock))
				}
			}
		}
	}
}

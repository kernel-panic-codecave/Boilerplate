package net.kernelpanicsoft.boilerplate.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.engine_room.flywheel.api.model.IndexSequence
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.api.material.Material
import dev.engine_room.flywheel.api.vertex.MutableVertexList
import dev.engine_room.flywheel.lib.math.MoreMath
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.render.AFluidRenderPlatform
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.FastColor
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Vector3f
import org.joml.Vector4f
import org.joml.Vector4fc
import org.lwjgl.system.MemoryUtil
import kotlin.math.sqrt

/**
 * Baked, reusable [Mesh]es for the things Boilerplate draws that are not blocks - an item tumbling
 * down a pipe, a fluid riding the warehouse crane.
 *
 * Everything this mod renders goes through Flywheel (there are no vanilla `BlockEntityRenderer`
 * fallbacks left, which is why the loader metadata declares Flywheel as a hard dependency), and
 * Flywheel instances *baked* geometry rather than re-emitting quads each frame. So each kind of
 * cargo needs one mesh, built once and shared by every instance of it on screen: a busy network has
 * hundreds of items in flight, and baking per item per frame would be the immediate-mode cost the
 * whole engine exists to avoid.
 *
 * Meshes are cached by [ResourceIdentity], not by the resource itself - see that class for why a
 * resource is not reliably usable as a map key.
 */
object InstancedMeshes {
	/** One baked mesh per distinct resource, built on first sight and kept for the session. */
	private val cache = HashMap<Any, Mesh>()

	/** [stack]'s own item model, baked flat with its ground transform already applied - see [buildItemMesh]. */
	fun itemMesh(stack: ItemStack): Mesh =
		cache.getOrPut(ResourceIdentity.of(ItemResource.of(stack))) { buildItemMesh(stack) }

	/** [fluid]'s droplet, an icosahedron skinned with its own still sprite - see [dropletMesh]. */
	fun fluidMesh(fluid: FluidResource): Mesh? {
		val type = fluid.type
		return dropletMesh(
			ResourceIdentity.of(fluid),
			AFluidRenderPlatform.getStillSprite(type) ?: return null,
			AFluidRenderPlatform.getTintColor(type),
			// The fluid's *own* render layer, which is where a fluid's transparency actually lives.
			// Water's tint is fully opaque `0xFF3F76E4`; what makes water see-through is its still
			// texture's alpha, drawn on the translucent layer - so reading the tint said "opaque"
			// and a cutout material then rounded the texture's alpha away too, leaving a solid blue
			// pebble tumbling down the pipe.
			translucent = ItemBlockRenderTypes.getRenderLayer(type.defaultFluidState()) == RenderType.translucent(),
		)
	}

	/**
	 * A droplet skinned with [sprite] and tinted by [tint], cached under [key] - what a fluid looks
	 * like tumbling down a pipe, and what any other kind drawn from an atlas sprite gets to look
	 * like for free.
	 *
	 * Public and sprite-shaped rather than fluid-shaped because a fluid is not the only such kind:
	 * Mekanism's chemicals are a sprite and a tint too, and their display kind lives in the NeoForge
	 * module, which cannot reach a private fluid-only builder.
	 */
	fun dropletMesh(key: Any, sprite: TextureAtlasSprite, tint: Int, translucent: Boolean = false): Mesh =
		cache.getOrPut(key) { buildDropletMesh(sprite, tint, translucent) }

	/**
	 * [itemStack]'s baked model re-baked into a plain triangle list, with
	 * [ItemDisplayContext.GROUND]'s own transform (the one `ItemRenderer.renderStatic` would apply)
	 * pre-applied to every vertex.
	 *
	 * Applied once here rather than through the instance's transform every frame, because it never
	 * changes for a given item. The trailing `-0.5` is vanilla's own recentre, which puts the model's
	 * middle on the origin so a caller positions the item by its centre rather than by a corner; the
	 * leading [ITEM_SCALE] is the outer scale the immediate-mode renderer used to apply *around*
	 * that whole transform, baked in here so every mesh this object hands out is already at its
	 * final world size and a caller only ever has to place and spin it.
	 */
	private fun buildItemMesh(itemStack: ItemStack): Mesh {
		val model = Minecraft.getInstance().itemRenderer.getModel(itemStack, null, null, 0)
		val pose = PoseStack()
		pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
		model.transforms.getTransform(ItemDisplayContext.GROUND).apply(false, pose)
		pose.translate(-0.5, -0.5, -0.5)
		val matrix = pose.last().pose()
		val normalMatrix = pose.last().normal()

		val vertices = ArrayList<MeshVertex>()
		for (quad in model.getQuads(null, null, RandomSource.create())) {
			val transformed = decodeQuadVertices(quad).map { point ->
				val moved = matrix.transformPosition(Vector3f(point.x, point.y, point.z))
				MeshPoint(moved.x(), moved.y(), moved.z(), point.u, point.v, point.r, point.g, point.b, point.a)
			}
			val rawNormal = quad.direction.normal
			val normal = normalMatrix.transform(Vector3f(rawNormal.x.toFloat(), rawNormal.y.toFloat(), rawNormal.z.toFloat())).normalize()
			for (i in 1 until transformed.size - 1) {
				vertices += MeshVertex(transformed[0], normal.x(), normal.y(), normal.z())
				vertices += MeshVertex(transformed[i], normal.x(), normal.y(), normal.z())
				vertices += MeshVertex(transformed[i + 1], normal.x(), normal.y(), normal.z())
			}
		}
		// An item model is ordinary cutout geometry - its own texture does the transparency, per
		// fragment, exactly as it does when vanilla draws it.
		return TriangleListMesh(vertices, Materials.CUTOUT_BLOCK)
	}

	/**
	 * A fluid drawn as an icosahedron skinned with its own still texture, tinted by the fluid's own
	 * colour.
	 *
	 * A fluid has no item model to borrow, and a textured cube reads as a block of ice rather than a
	 * droplet. An icosahedron is the cheapest solid that reads as *round* - 20 faces, no seam a
	 * player can pick out while it spins - and the caller tumbles it through the instance transform.
	 *
	 * The immediate-mode droplet this replaces also displaced each vertex on a sine wave every
	 * frame, so the surface visibly sloshed. A baked mesh cannot: instanced geometry is uploaded
	 * once and only its transform changes per frame. The tumble survives, the ripple does not - and
	 * bringing it back would mean per-droplet vertex animation, which is exactly the per-frame cost
	 * instancing buys away.
	 *
	 * Every face samples the whole sprite rather than a per-face UV atlas: a fluid still texture is
	 * a flat tiling pattern with no meaningful orientation, so anything more elaborate buys nothing.
	 */
	private fun buildDropletMesh(sprite: TextureAtlasSprite, tint: Int, translucent: Boolean): Mesh {
		val red = FastColor.ARGB32.red(tint) / 255f
		val green = FastColor.ARGB32.green(tint) / 255f
		val blue = FastColor.ARGB32.blue(tint) / 255f
		// A zero alpha byte means "no alpha given" rather than "invisible" - plenty of tints are
		// written as a bare 0xRRGGBB, and taking that literally would draw nothing at all.
		val rawAlpha = FastColor.ARGB32.alpha(tint)
		val alpha = (if (rawAlpha == 0) 0xFF else rawAlpha) / 255f

		val vertices = ArrayList<MeshVertex>()
		for ((faceIndex, face) in ICOSAHEDRON_FACES.withIndex()) {
			// A flat normal per face - an icosahedron has no smooth shading to preserve, and this
			// keeps neighbouring faces distinguishable as the solid turns.
			val normal = faceNormal(face)
			// Each corner samples a different corner of the sprite, so the texture reads as material
			// rather than a single flat colour smeared over the triangle - and the triple is rotated
			// per face, so neighbouring faces do not sample identically. Without that an icosahedron
			// skinned in a flat tiling texture is very nearly rotationally featureless: it *was*
			// spinning the whole time and there was no way to tell.
			val corners = arrayOf(sprite.u0 to sprite.v0, sprite.u1 to sprite.v0, sprite.u1 to sprite.v1, sprite.u0 to sprite.v1)
			val turn = faceIndex % corners.size
			val uvs = Array(3) { corners[(it + turn) % corners.size] }
			for (i in face.indices) {
				val corner = face[i]
				val point = MeshPoint(
					corner.x() * DROPLET_RADIUS, corner.y() * DROPLET_RADIUS, corner.z() * DROPLET_RADIUS,
					uvs[i].first, uvs[i].second, red, green, blue, alpha,
				)
				vertices += MeshVertex(point, normal.x(), normal.y(), normal.z())
			}
		}
		// Blending, but only when there is something to blend. A cutout material rounds alpha to
		// all-or-nothing - the sprite's as much as the tint's - and a translucent one costs a sorted
		// pass that genuinely opaque geometry has no reason to pay for.
		return TriangleListMesh(
			vertices,
			if (translucent || alpha < 1f) Materials.TRANSLUCENT_BLOCK else Materials.CUTOUT_BLOCK,
		)
	}

	/**
	 * One (position, UV, colour) corner of a [BakedQuad], unpacked from its own packed vertex data.
	 *
	 * Clip-plane and transform math only: no brightness or light is carried, since every instance
	 * built here is flat-lit through the visual's own `relight` rather than smooth per-vertex
	 * lighting.
	 */
	private fun decodeQuadVertices(quad: BakedQuad): List<MeshPoint> {
		val raw = quad.vertices
		return (0 until 4).map { i ->
			val base = i * VERTEX_STRIDE_INTS
			MeshPoint(
				Float.fromBits(raw[base]), Float.fromBits(raw[base + 1]), Float.fromBits(raw[base + 2]),
				Float.fromBits(raw[base + 4]), Float.fromBits(raw[base + 5]),
				// White and opaque: a baked quad's own packed colour at `raw[base + 3]` is a tint
				// index's result, which nothing here resolves - the same white this always used,
				// now with the alpha it always implied stated rather than assumed downstream.
				1f, 1f, 1f, 1f,
			)
		}
	}

	private fun centroidOf(face: Array<Vector3f>): Vector3f =
		Vector3f(face[0]).add(face[1]).add(face[2]).mul(1f / 3f)

	private fun faceNormal(face: Array<Vector3f>): Vector3f {
		val edge1 = Vector3f(face[1]).sub(face[0])
		val edge2 = Vector3f(face[2]).sub(face[0])
		return edge1.cross(edge2).normalize()
	}

	/**
	 * The icosahedron's 20 triangular faces, as unit-length vertices in object space.
	 *
	 * Built from the standard golden-ratio construction: the 12 vertices are the cyclic permutations
	 * of `(0, ±1, ±φ)`, normalised onto the unit sphere.
	 */
	private val ICOSAHEDRON_FACES: Array<Array<Vector3f>> by lazy { buildIcosahedron() }

	private fun buildIcosahedron(): Array<Array<Vector3f>> {
		val phi = (1f + sqrt(5f)) / 2f
		val points = listOf(
			Vector3f(0f, 1f, phi), Vector3f(0f, -1f, phi), Vector3f(0f, 1f, -phi), Vector3f(0f, -1f, -phi),
			Vector3f(1f, phi, 0f), Vector3f(-1f, phi, 0f), Vector3f(1f, -phi, 0f), Vector3f(-1f, -phi, 0f),
			Vector3f(phi, 0f, 1f), Vector3f(-phi, 0f, 1f), Vector3f(phi, 0f, -1f), Vector3f(-phi, 0f, -1f),
		).map { it.normalize() }
		val faces = arrayOf(
			intArrayOf(0, 1, 8), intArrayOf(0, 8, 4), intArrayOf(0, 4, 5), intArrayOf(0, 5, 9), intArrayOf(0, 9, 1),
			intArrayOf(3, 2, 11), intArrayOf(3, 11, 7), intArrayOf(3, 7, 6), intArrayOf(3, 6, 10), intArrayOf(3, 10, 2),
			intArrayOf(1, 6, 7), intArrayOf(1, 7, 9), intArrayOf(1, 8, 6),
			intArrayOf(8, 10, 6), intArrayOf(8, 4, 10), intArrayOf(4, 2, 10), intArrayOf(4, 5, 2),
			intArrayOf(5, 11, 2), intArrayOf(5, 9, 11), intArrayOf(9, 7, 11),
		)
		return faces.map { indices ->
			val corners = arrayOf(points[indices[0]], points[indices[1]], points[indices[2]])
			// Wound outward, whichever order the table happens to list a face in: on a solid centred
			// at the origin an outward normal points the same way as the face's own centroid, so a
			// negative dot means the triangle faces inward and gets flipped. Without this a
			// backface-culled material renders the droplet inside out for some faces and not others.
			if (faceNormal(corners).dot(centroidOf(corners)) < 0f) arrayOf(corners[0], corners[2], corners[1])
			else corners
		}.toTypedArray()
	}

	/** How far down an item model is scaled for transport - small enough to sit inside a pipe's own 6x6x6 core. */
	private const val ITEM_SCALE = 0.4f

	/** Radius of a fluid droplet in block units - deliberately smaller than an item's own scale, so a fluid reads as a droplet rather than a boulder. */
	private const val DROPLET_RADIUS = 0.17f

	private const val VERTEX_STRIDE_INTS = 8

	/** A conservative bounding sphere for anything built here - every mesh is recentred about its own origin and fits inside the unit cube. */
	private val UNIT_CUBE_BOUNDING_SPHERE: Vector4fc = Vector4f(0f, 0f, 0f, MoreMath.SQRT_3_OVER_2)

	private data class MeshPoint(
		val x: Float, val y: Float, val z: Float,
		val u: Float, val v: Float,
		val r: Float, val g: Float, val b: Float, val a: Float,
	)

	private data class MeshVertex(val point: MeshPoint, val normalX: Float, val normalY: Float, val normalZ: Float)

	/**
	 * A plain (non-indexed) triangle list, flat-lit and flat-shaded - `light`/`overlay` are
	 * placeholders the visual's own `relight` and Flywheel's overlay handling override at render
	 * time, not baked-in values.
	 *
	 * Carries its own [material] rather than leaving it to whoever instances it: geometry with a
	 * vertex alpha below one needs a blending material, and only the builder knows whether it has
	 * any. See [MaterialMesh].
	 */
	private class TriangleListMesh(
		private val vertices: List<MeshVertex>,
		override val material: Material,
	) : MaterialMesh {
		override fun vertexCount(): Int = vertices.size

		override fun write(vertexList: MutableVertexList) {
			for (i in vertices.indices) {
				val vertex = vertices[i]
				vertexList.x(i, vertex.point.x)
				vertexList.y(i, vertex.point.y)
				vertexList.z(i, vertex.point.z)
				vertexList.r(i, vertex.point.r)
				vertexList.g(i, vertex.point.g)
				vertexList.b(i, vertex.point.b)
				vertexList.a(i, vertex.point.a)
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

	/** Identity index buffer (`0, 1, 2, ...`) for [TriangleListMesh]'s plain triangle list. */
	private object SequentialIndexSequence : IndexSequence {
		override fun fill(ptr: Long, vertexCount: Int) {
			for (i in 0 until vertexCount) MemoryUtil.memPutInt(ptr + i * 4L, i)
		}
	}
}

package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import net.kernelpanicsoft.archie.gui.render.AFluidRenderPlatform
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.util.FastColor
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a fluid in transit as a rippling icosahedron tumbling on all three axes, skinned with the
 * fluid's own still texture.
 *
 * A fluid has no item model to borrow, and a textured cube reads as a block of ice rather than a
 * droplet. An icosahedron is the cheapest solid that reads as *round* - 20 faces, no seam a player
 * can pick out while it spins - and displacing each vertex along its own radius on a sine wave
 * makes the surface visibly slosh instead of tumbling rigidly. Tumbling on all three axes at
 * mutually prime-ish rates keeps it from settling into an obvious loop.
 *
 * The geometry is generated once ([FACES]) in unit-radius object space and reused for every droplet
 * and every frame; a frame only computes the ripple offsets and writes vertices. That matters
 * because a busy pipe network can have hundreds of these on screen, and this is the same discipline
 * the debug overlays follow.
 *
 * Every face samples the *whole* sprite rather than a per-face UV atlas: a fluid still texture is a
 * flat tiling pattern with no meaningful orientation, so anything more elaborate buys nothing and
 * would need per-fluid unwrapping.
 */
object TravelingFluidRenderer {
	/** Radius of the droplet in block units - deliberately smaller than [TravelingItemRenderer]'s own item scale, so a fluid reads as a droplet rather than a boulder. */
	private const val RADIUS = 0.17f

	/** How far, as a fraction of [RADIUS], the surface swells and shrinks. Enough to read as motion, not enough to break the silhouette. */
	private const val RIPPLE_DEPTH = 0.18f

	/** Ripple cycles per tick. */
	private const val RIPPLE_SPEED = 0.35f

	/** Spin rate per tick, degrees, per axis. Deliberately unequal so the tumble never settles into a visible repeat. */
	private const val SPIN_X = 2.7f
	private const val SPIN_Y = 3.9f
	private const val SPIN_Z = 1.7f

	/**
	 * The icosahedron's 20 triangular faces, as unit-length vertices in object space.
	 *
	 * Built from the standard golden-ratio construction: the 12 vertices are the cyclic permutations
	 * of `(0, ±1, ±φ)`, normalised onto the unit sphere so the ripple can displace each one along
	 * its own radius by simple scaling.
	 */
	private val FACES: Array<Array<Vector3f>> = buildIcosahedron()

	/** Scratch for the ripple-displaced vertex currently being written. Render-thread-only, never held across a call. */
	private val scratch = Vector3f()

	/**
	 * Renders one droplet of [fluid] at [center] (already relative to the block being rendered).
	 *
	 * [gameTime] is the render thread's own fractional game time - it drives both the ripple phase
	 * and the tumble, so every droplet of the same fluid moves in step rather than each carrying its
	 * own animation state.
	 */
	fun render(
		fluid: FluidResource,
		center: Vec3,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int,
		gameTime: Float,
	) {
		val sprite = AFluidRenderPlatform.getStillSprite(fluid.type) ?: return
		val tint = AFluidRenderPlatform.getTintColor(fluid.type)
		val red = FastColor.ARGB32.red(tint)
		val green = FastColor.ARGB32.green(tint)
		val blue = FastColor.ARGB32.blue(tint)
		// Fluid sprites live on the block atlas; translucent so a partly transparent fluid reads as one.
		val consumer = bufferSource.getBuffer(RenderType.translucent())

		poseStack.pushPose()
		poseStack.translate(center.x, center.y, center.z)
		poseStack.mulPose(Axis.XP.rotationDegrees(gameTime * SPIN_X))
		poseStack.mulPose(Axis.YP.rotationDegrees(gameTime * SPIN_Y))
		poseStack.mulPose(Axis.ZP.rotationDegrees(gameTime * SPIN_Z))

		val pose = poseStack.last()
		val u0 = sprite.u0
		val u1 = sprite.u1
		val v0 = sprite.v0
		val v1 = sprite.v1

		for (face in FACES) {
			// A flat normal per face - an icosahedron has no smooth shading to preserve, and this
			// keeps neighbouring faces distinguishable as the solid turns.
			val normal = faceNormal(face)
			// Each corner samples a different corner of the sprite, so the texture reads as material
			// rather than a single flat colour smeared over the tri.
			writeVertex(consumer, pose, face[0], gameTime, red, green, blue, u0, v0, packedLight, packedOverlay, normal)
			writeVertex(consumer, pose, face[1], gameTime, red, green, blue, u1, v0, packedLight, packedOverlay, normal)
			writeVertex(consumer, pose, face[2], gameTime, red, green, blue, u1, v1, packedLight, packedOverlay, normal)
			// RenderType.translucent() is a quad format, so the triangle's last corner is doubled -
			// the same degenerate-quad trick the clipped-gantry-rod path uses.
			writeVertex(consumer, pose, face[2], gameTime, red, green, blue, u0, v1, packedLight, packedOverlay, normal)
		}

		poseStack.popPose()
	}

	/** Writes one ripple-displaced corner. [vertex] is a unit-sphere direction; the ripple scales it, [RADIUS] sizes it. */
	private fun writeVertex(
		consumer: com.mojang.blaze3d.vertex.VertexConsumer,
		pose: PoseStack.Pose,
		vertex: Vector3f,
		gameTime: Float,
		red: Int,
		green: Int,
		blue: Int,
		u: Float,
		v: Float,
		packedLight: Int,
		packedOverlay: Int,
		normal: Vector3f,
	) {
		// Phase the wave by the vertex's own position so different parts of the surface swell at
		// different moments - a single global phase would just pulse the whole solid in and out.
		val phase = vertex.x * 3f + vertex.y * 5f + vertex.z * 7f
		val swell = 1f + RIPPLE_DEPTH * sin(gameTime * RIPPLE_SPEED + phase)
		scratch.set(vertex).mul(RADIUS * swell)
		consumer.addVertex(pose, scratch.x, scratch.y, scratch.z)
			.setColor(red, green, blue, 255)
			.setUv(u, v)
			.setOverlay(packedOverlay)
			.setLight(packedLight)
			.setNormal(pose, normal.x, normal.y, normal.z)
	}

	/** The unit normal of [face], from the cross product of two of its edges. */
	private fun faceNormal(face: Array<Vector3f>): Vector3f {
		val edge1 = Vector3f(face[1]).sub(face[0])
		val edge2 = Vector3f(face[2]).sub(face[0])
		return edge1.cross(edge2).normalize()
	}

	/** The 20 faces of a unit icosahedron - see [FACES]. */
	private fun buildIcosahedron(): Array<Array<Vector3f>> {
		val phi = (1f + sqrt(5f)) / 2f
		val vertices = listOf(
			Vector3f(-1f, phi, 0f), Vector3f(1f, phi, 0f), Vector3f(-1f, -phi, 0f), Vector3f(1f, -phi, 0f),
			Vector3f(0f, -1f, phi), Vector3f(0f, 1f, phi), Vector3f(0f, -1f, -phi), Vector3f(0f, 1f, -phi),
			Vector3f(phi, 0f, -1f), Vector3f(phi, 0f, 1f), Vector3f(-phi, 0f, -1f), Vector3f(-phi, 0f, 1f),
		).map { it.normalize() }

		val indices = arrayOf(
			intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
			intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
			intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
			intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
		)
		return Array(indices.size) { i -> Array(3) { c -> Vector3f(vertices[indices[i][c]]) } }
	}
}

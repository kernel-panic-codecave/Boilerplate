package net.kernelpanicsoft.boilerplate.debug.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.floats.FloatArrayList
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * A batch of world-space debug lines, built once and replayed cheaply every frame.
 *
 * Debug overlays draw the same geometry for many consecutive frames while the state behind it only
 * changes a few times a second, so building it per frame is pure waste - and at overlay scale
 * (thousands of rack boxes, a whole pipe network's outline) it is enough waste to cost real frame
 * time. Callers [add] their lines whenever their source data actually changes and [draw] the result
 * every frame; between rebuilds a frame is one pass over three flat primitive arrays.
 *
 * Everything is stored in absolute world coordinates and rebased onto the camera at draw time, in
 * `double` precision, because a float world coordinate far from the origin is too coarse to
 * subtract a camera position from without visible wobble.
 *
 * Vanilla's `VertexConsumer.addVertex(Pose, ...)`/`setNormal(Pose, ...)` helpers allocate a fresh
 * `Vector3f` per call, which at these vertex counts is the frame's dominant allocation - [draw]
 * transforms through one reused [scratch] instead.
 */
class DebugLineBatch {
	/** Endpoint pairs, stride 6: `x1, y1, z1, x2, y2, z2`, absolute world coordinates. */
	private val points = DoubleArrayList()

	/** Per-segment unit direction, stride 3 - what vanilla's line shader expands the segment's screen-space width along. */
	private val normals = FloatArrayList()

	/** Per-segment RGBA, stride 4. */
	private val colors = FloatArrayList()

	/** [points]/[normals]/[colors] flattened to plain arrays by [seal], so [draw]'s inner loop is raw array indexing rather than a growable-list call per component. Rebuilt on the first [draw] after any change. */
	private var sealedPoints = EMPTY_POINTS
	private var sealedNormals = EMPTY_NORMALS
	private var sealedColors = EMPTY_NORMALS
	private var dirty = true

	val isEmpty: Boolean get() = points.isEmpty()

	fun clear() {
		points.clear()
		normals.clear()
		colors.clear()
		dirty = true
	}

	/** Appends one segment. The normal is the segment's own normalized direction, matching what vanilla's own shape/box line rendering feeds the shader; a zero-length segment is dropped rather than emitting a `NaN` normal. */
	fun add(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: DebugColor) {
		val dx = (x2 - x1).toFloat()
		val dy = (y2 - y1).toFloat()
		val dz = (z2 - z1).toFloat()
		val length = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
		if (length == 0f) return

		points.add(x1); points.add(y1); points.add(z1)
		points.add(x2); points.add(y2); points.add(z2)
		normals.add(dx / length); normals.add(dy / length); normals.add(dz / length)
		colors.add(color.red); colors.add(color.green); colors.add(color.blue); colors.add(color.alpha)
		dirty = true
	}

	fun add(from: Vec3, to: Vec3, color: DebugColor) = add(from.x, from.y, from.z, to.x, to.y, to.z, color)

	/** Appends the 12 edges of an axis-aligned box. */
	fun addBox(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, color: DebugColor) {
		add(minX, minY, minZ, maxX, minY, minZ, color)
		add(minX, minY, maxZ, maxX, minY, maxZ, color)
		add(minX, maxY, minZ, maxX, maxY, minZ, color)
		add(minX, maxY, maxZ, maxX, maxY, maxZ, color)

		add(minX, minY, minZ, minX, minY, maxZ, color)
		add(maxX, minY, minZ, maxX, minY, maxZ, color)
		add(minX, maxY, minZ, minX, maxY, maxZ, color)
		add(maxX, maxY, minZ, maxX, maxY, maxZ, color)

		add(minX, minY, minZ, minX, maxY, minZ, color)
		add(maxX, minY, minZ, maxX, maxY, minZ, color)
		add(minX, minY, maxZ, minX, maxY, maxZ, color)
		add(maxX, minY, maxZ, maxX, maxY, maxZ, color)
	}

	/** A cube of half-width [radius] centred on [centre]. */
	fun addCube(centre: Vec3, radius: Double, color: DebugColor) = addBox(
		centre.x - radius, centre.y - radius, centre.z - radius,
		centre.x + radius, centre.y + radius, centre.z + radius,
		color,
	)

	/** Emits every segment, rebased onto [cam] and transformed by [pose]. */
	fun draw(consumer: VertexConsumer, pose: PoseStack.Pose, cam: Vec3) {
		seal()
		val points = sealedPoints
		val normals = sealedNormals
		val colors = sealedColors
		val matrix = pose.pose()
		var point = 0
		var normal = 0
		var color = 0
		while (point < points.size) {
			pose.transformNormal(normals[normal], normals[normal + 1], normals[normal + 2], scratch)
			val normalX = scratch.x
			val normalY = scratch.y
			val normalZ = scratch.z
			val red = colors[color]
			val green = colors[color + 1]
			val blue = colors[color + 2]
			val alpha = colors[color + 3]

			matrix.transformPosition(
				(points[point] - cam.x).toFloat(),
				(points[point + 1] - cam.y).toFloat(),
				(points[point + 2] - cam.z).toFloat(),
				scratch,
			)
			consumer.addVertex(scratch.x, scratch.y, scratch.z).setColor(red, green, blue, alpha).setNormal(normalX, normalY, normalZ)

			matrix.transformPosition(
				(points[point + 3] - cam.x).toFloat(),
				(points[point + 4] - cam.y).toFloat(),
				(points[point + 5] - cam.z).toFloat(),
				scratch,
			)
			consumer.addVertex(scratch.x, scratch.y, scratch.z).setColor(red, green, blue, alpha).setNormal(normalX, normalY, normalZ)

			point += 6
			normal += 3
			color += 4
		}
	}

	/** Flattens the growable builders into the plain arrays [draw] walks, once per change rather than once per frame. */
	private fun seal() {
		if (!dirty) return
		dirty = false
		sealedPoints = points.toDoubleArray()
		sealedNormals = normals.toFloatArray()
		sealedColors = colors.toFloatArray()
	}

	private companion object {
		/** Shared scratch destination for every pose transform - render-thread-only, and never read across a transform call. */
		private val scratch = Vector3f()

		private val EMPTY_POINTS = DoubleArray(0)
		private val EMPTY_NORMALS = FloatArray(0)
	}
}

/** One overlay line color. A value type rather than four loose floats, so the palettes below read as names at every call site. */
data class DebugColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
	/** The same hue at a different opacity - what distinguishes a queued item from an in-flight one across the warehouse palette. */
	fun alpha(alpha: Float): DebugColor = DebugColor(red, green, blue, alpha)

	companion object {
		/** Unpacks a `0xRRGGBB` literal at [alpha]. */
		fun rgb(packed: Int, alpha: Float = 1f): DebugColor = DebugColor(
			(packed shr 16 and 0xff) / 255f,
			(packed shr 8 and 0xff) / 255f,
			(packed and 0xff) / 255f,
			alpha,
		)
	}
}

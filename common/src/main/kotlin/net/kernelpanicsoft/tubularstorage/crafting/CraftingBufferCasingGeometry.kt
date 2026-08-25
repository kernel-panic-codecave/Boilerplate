package net.kernelpanicsoft.tubularstorage.crafting

import net.kernelpanicsoft.tubularstorage.pipe.block.ConnectingEncasementModelBlock.FaceMode
import net.minecraft.core.Direction
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Collision/targeting geometry for [CraftingBufferEncasementType]'s casing, matching the part
 * model set (`crafting_buffer_encasement_{core,arm,cap,edge,corner}.json`) element for element: an
 * open frame around the pipe hole (the core model's eight corner cubes and twelve edge rails), a
 * 2px ring flange bridging each ARM face to its neighboring member (the arm model's four walls), a
 * 6x6 plug plate over each CAP face's dead-end pipe hole (the cap model), and - on a formed
 * cluster, exactly like the render state gates them - the `_edge`/`_corner` seam fillers spanning
 * the outer 2px margins between adjacent arms.
 *
 * Every piece is static; only the assembled union varies, and that is memoized by packed mode bits
 * plus the formed flag - shape queries run many times per frame against every aimed-at or
 * colliding segment.
 */
internal object CraftingBufferCasingGeometry {

	private const val LOW_MIN = 0.125
	private const val LOW_MAX = 0.3125
	private const val MID_MIN = 0.3125
	private const val MID_MAX = 0.6875
	private const val HIGH_MIN = 0.6875
	private const val HIGH_MAX = 0.875

	private val CORNERS: List<Pair<Double, Double>> = listOf(LOW_MIN to LOW_MAX, HIGH_MIN to HIGH_MAX)

	/** The open frame - the one piece every arrangement shows. Axis-symmetric, so never rotated. */
	val FRAME: VoxelShape = buildFrame()

	/** The arm model's ring flange (authored facing north), rotated onto each direction. */
	private val RINGS: Map<Direction, VoxelShape> = Direction.entries.associateWith { buildRing(it) }

	/** The cap model's plug plate (authored facing north), rotated onto each direction. */
	private val CAPS: Map<Direction, VoxelShape> = Direction.entries.associateWith { buildCap(it) }

	/** The edge model's seam filler (authored along the north+east vertical margins): the outer 2px strip between two adjacent arms, full block height of the casing's own band. */
	private val EDGE_BOX = doubleArrayOf(HIGH_MAX, LOW_MIN, 0.0, 1.0, HIGH_MAX, 0.125)

	/** The corner model's seam filler (authored at the north+east+up corner): the outer 2px post where three mutually-adjacent arms meet. */
	private val CORNER_BOX = doubleArrayOf(HIGH_MAX, HIGH_MAX, 0.0, 1.0, 1.0, 0.125)

	/**
	 * Where each authored-north seam filler lands per model rotation - the same tables the datagen
	 * blockstate provider's own `EDGE_ROTATIONS`/`CORNER_ROTATIONS` use: each entry names the
	 * directions a filler rotated by `(rotationX, rotationY)` bridges.
	 */
	private val EDGES: List<Pair<Pair<Int, Int>, List<Direction>>> = listOf(
		(0 to 0) to listOf(Direction.NORTH, Direction.EAST),
		(0 to 90) to listOf(Direction.SOUTH, Direction.EAST),
		(0 to 180) to listOf(Direction.SOUTH, Direction.WEST),
		(0 to 270) to listOf(Direction.NORTH, Direction.WEST),
		(90 to 0) to listOf(Direction.DOWN, Direction.EAST),
		(90 to 90) to listOf(Direction.DOWN, Direction.SOUTH),
		(90 to 180) to listOf(Direction.DOWN, Direction.WEST),
		(90 to 270) to listOf(Direction.DOWN, Direction.NORTH),
		(270 to 0) to listOf(Direction.UP, Direction.EAST),
		(270 to 90) to listOf(Direction.UP, Direction.SOUTH),
		(270 to 180) to listOf(Direction.UP, Direction.WEST),
		(270 to 270) to listOf(Direction.UP, Direction.NORTH),
	)

	/** See [EDGES]. */
	private val CORNERS_BY_ROTATION: List<Pair<Pair<Int, Int>, List<Direction>>> = listOf(
		(0 to 0) to listOf(Direction.UP, Direction.NORTH, Direction.EAST),
		(0 to 90) to listOf(Direction.UP, Direction.SOUTH, Direction.EAST),
		(0 to 180) to listOf(Direction.UP, Direction.SOUTH, Direction.WEST),
		(0 to 270) to listOf(Direction.UP, Direction.NORTH, Direction.WEST),
		(90 to 0) to listOf(Direction.DOWN, Direction.NORTH, Direction.EAST),
		(90 to 90) to listOf(Direction.DOWN, Direction.SOUTH, Direction.EAST),
		(90 to 180) to listOf(Direction.DOWN, Direction.SOUTH, Direction.WEST),
		(90 to 270) to listOf(Direction.DOWN, Direction.NORTH, Direction.WEST),
	)

	private val COMBINED: MutableMap<Int, VoxelShape> = HashMap()

	/**
	 * [FRAME] plus each direction's ring/cap per its [FaceMode], plus - only while [formed] - every
	 * edge/corner filler whose bridged directions are all ARM, mirroring the blockstate definition's
	 * gating so geometry never covers a seam the model doesn't draw. Memoized across calls.
	 */
	fun forFaceModes(modes: Map<Direction, FaceMode>, formed: Boolean): VoxelShape {
		var key = (if (formed) 1 else 0) shl 12
		for ((index, direction) in Direction.entries.withIndex()) {
			key = key or ((modes[direction]?.ordinal ?: 0) shl (index * 2))
		}
		return COMBINED.getOrPut(key) {
			var shape = FRAME
			for ((direction, mode) in modes) {
				when (mode) {
					FaceMode.ARM -> shape = Shapes.or(shape, RINGS.getValue(direction))
					FaceMode.CAP -> shape = Shapes.or(shape, CAPS.getValue(direction))
					FaceMode.NONE -> {}
				}
			}
			if (formed) {
				for ((rotation, directions) in EDGES) {
					if (directions.all { modes[it] == FaceMode.ARM }) {
						shape = Shapes.or(shape, rotatedBox(rotation.first, rotation.second, EDGE_BOX))
					}
				}
				for ((rotation, directions) in CORNERS_BY_ROTATION) {
					if (directions.all { modes[it] == FaceMode.ARM }) {
						shape = Shapes.or(shape, rotatedBox(rotation.first, rotation.second, CORNER_BOX))
					}
				}
			}
			shape
		}
	}

	private fun buildFrame(): VoxelShape {
		var shape = Shapes.empty()
		for (x in CORNERS) for (y in CORNERS) for (z in CORNERS) {
			shape = Shapes.or(shape, Shapes.box(x.first, y.first, z.first, x.second, y.second, z.second))
		}
		for (axis in 0..2) {
			for (a in CORNERS) for (b in CORNERS) {
				val ranges = arrayOf(MID_MIN to MID_MAX, MID_MIN to MID_MAX, MID_MIN to MID_MAX)
				ranges[(axis + 1) % 3] = a
				ranges[(axis + 2) % 3] = b
				shape = Shapes.or(shape, Shapes.box(ranges[0].first, ranges[1].first, ranges[2].first, ranges[0].second, ranges[1].second, ranges[2].second))
			}
		}
		return shape
	}

	/**
	 * One box hugging [direction]'s own face plane: [depth0]..[depth1] blocks in from that face,
	 * [span0]..[span1] along the face's first lateral axis and [cross0]..[cross1] along the second.
	 */
	private fun faceBox(direction: Direction, depth0: Double, depth1: Double, span0: Double, span1: Double, cross0: Double, cross1: Double): VoxelShape =
		when (direction) {
			Direction.NORTH -> Shapes.box(span0, cross0, depth0, span1, cross1, depth1)
			Direction.SOUTH -> Shapes.box(1 - span1, cross0, 1 - depth1, 1 - span0, cross1, 1 - depth0)
			Direction.EAST -> Shapes.box(1 - depth1, cross0, span0, 1 - depth0, cross1, span1)
			Direction.WEST -> Shapes.box(depth0, cross0, 1 - span1, depth1, cross1, 1 - span0)
			Direction.UP -> Shapes.box(span0, 1 - depth1, cross0, span1, 1 - depth0, cross1)
			Direction.DOWN -> Shapes.box(span0, depth0, cross0, span1, depth1, cross1)
		}

	/** The arm model's four walls: a full-width strip along each lateral edge plus two strips flanking the central opening, 2px deep against the face. */
	private fun buildRing(direction: Direction): VoxelShape =
		Shapes.or(
			faceBox(direction, 0.0, 0.125, LOW_MIN, HIGH_MAX, LOW_MIN, LOW_MAX),
			faceBox(direction, 0.0, 0.125, LOW_MIN, HIGH_MAX, HIGH_MIN, HIGH_MAX),
			faceBox(direction, 0.0, 0.125, LOW_MIN, LOW_MAX, MID_MIN, MID_MAX),
			faceBox(direction, 0.0, 0.125, HIGH_MIN, HIGH_MAX, MID_MIN, MID_MAX),
		)

	/** The cap model's plate: 6x6 centered over the pipe hole, spanning 2px..4px in from the face. */
	private fun buildCap(direction: Direction): VoxelShape =
		faceBox(direction, 0.125, 0.25, MID_MIN, MID_MAX, MID_MIN, MID_MAX)

	/**
	 * Carries one authored-north box onto its target faces via [BlockModelRotation]'s own
	 * composition (`rotateYXZ(-y, -x, 0)` - around X first, then around Y, both negated): quarter
	 * turns about X cycle north→down→south→up, quarter turns about Y cycle north→east→south→west,
	 * both about the block center. The same transform the datagen tables were verified against, so
	 * a box lands exactly where its model counterpart renders.
	 */
	private fun rotatedBox(xRot: Int, yRot: Int, box: DoubleArray): VoxelShape {
		val a = rotate(box[0], box[1], box[2], xRot / 90, yRot / 90)
		val b = rotate(box[3], box[4], box[5], xRot / 90, yRot / 90)
		return Shapes.box(
			minOf(a.first, b.first), minOf(a.second, b.second), minOf(a.third, b.third),
			maxOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.third, b.third),
		)
	}

	private fun rotate(x: Double, y: Double, z: Double, quarterTurnsX: Int, quarterTurnsY: Int): Triple<Double, Double, Double> {
		var vx = x - 0.5
		var vy = y - 0.5
		var vz = z - 0.5
		repeat(quarterTurnsX) { val nextY = vz; val nextZ = -vy; vy = nextY; vz = nextZ }
		repeat(quarterTurnsY) { val nextX = -vz; val nextZ = vx; vx = nextX; vz = nextZ }
		return Triple(vx + 0.5, vy + 0.5, vz + 0.5)
	}
}

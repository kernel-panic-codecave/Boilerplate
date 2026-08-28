package net.kernelpanicsoft.boilerplate.util

import net.minecraft.core.Direction
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

@DslMarker
annotation class VoxelShapeBuilderDsl

@VoxelShapeBuilderDsl
class VoxelShapeBuilder(private val op: BooleanOp = BooleanOp.OR, private var shape: VoxelShape = Shapes.empty())
{
	fun or(block: VoxelShapeBuilder.() -> Unit) = join(BooleanOp.OR, block)

	fun and(block: VoxelShapeBuilder.() -> Unit) = join(BooleanOp.AND, block)

	fun join(newOp: BooleanOp, block: VoxelShapeBuilder.() -> Unit)
	{
		val newShape = VoxelShapeBuilder(newOp)
		newShape.block()
		this.shape = Shapes.join(this.shape, newShape.build(), op)
	}

	fun box(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double)
	{
		this.shape = Shapes.join(this.shape, Shapes.box(minX, minY, minZ, maxX, maxY, maxZ), op)
	}

	fun build(): VoxelShape
	{
		return shape
	}
}

val VoxelShape.byDirection: Map<Direction, VoxelShape>
	get() = mapOf(
		Direction.NORTH to this,
		Direction.SOUTH to rotated(xRot = 0, yRot = 180),
		Direction.EAST to rotated(xRot = 0, yRot = 90),
		Direction.WEST to rotated(xRot = 0, yRot = 270),
		Direction.DOWN to rotated(xRot = 90, yRot = 0),
		Direction.UP to rotated(xRot = 270, yRot = 0),
	)

fun VoxelShape.rotated(xRot: Int, yRot: Int): VoxelShape
{
	var result = Shapes.empty()
	for (box in this.toAabbs()) {
		val a = rotateCorner(box.minX, box.minY, box.minZ, xRot / 90, yRot / 90)
		val b = rotateCorner(box.maxX, box.maxY, box.maxZ, xRot / 90, yRot / 90)
		result = Shapes.join(
			result,
			Shapes.box(
				minOf(a.first, b.first), minOf(a.second, b.second), minOf(a.third, b.third),
				maxOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.third, b.third),
			),
			BooleanOp.OR,
		)
	}

	return result
}


private fun rotateCorner(x: Double, y: Double, z: Double, quarterTurnsX: Int, quarterTurnsY: Int): Triple<Double, Double, Double> {
	var vx = x - 0.5
	var vy = y - 0.5
	var vz = z - 0.5
	repeat(quarterTurnsX) { val nextY = vz; val nextZ = -vy; vy = nextY; vz = nextZ }
	repeat(quarterTurnsY) { val nextX = -vz; val nextZ = vx; vx = nextX; vz = nextZ }
	return Triple(vx + 0.5, vy + 0.5, vz + 0.5)
}

inline fun voxelShape(op: BooleanOp = BooleanOp.OR, block: VoxelShapeBuilder.() -> Unit): VoxelShape
{
	val builder = VoxelShapeBuilder(op)
	builder.block()
	return builder.build()
}

inline fun VoxelShape.modify(op: BooleanOp = BooleanOp.OR, block: VoxelShapeBuilder.() -> Unit): VoxelShape
{
	val builder = VoxelShapeBuilder(op, this)
	builder.block()
	return builder.build()
}
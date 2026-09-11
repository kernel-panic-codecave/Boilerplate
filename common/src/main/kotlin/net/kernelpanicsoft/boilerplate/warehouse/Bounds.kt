package net.kernelpanicsoft.boilerplate.warehouse

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

/**
 * The warehouse volume a [net.kernelpanicsoft.boilerplate.warehouse.item.WarehousePlannerItem] binds to a [WarehouseControllerBlockEntity] - always
 * normalized so [min]/[max] are the actual lower/upper corners regardless of the order the two
 * clicks that produced them came in (see [of]).
 */
@Serializable
data class Bounds(val min: SBlockPos, val max: SBlockPos) {
	/** Whether [pos] falls inside this volume, inclusive of both corners. */
	operator fun contains(pos: BlockPos): Boolean =
		pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z

	/** Volume in total blocks inside this box. */
	val volume: Long
		get() = (max.x.toLong() - min.x + 1) *
				(max.y.toLong() - min.y + 1) *
				(max.z.toLong() - min.z + 1)

	/**
	 * Lazily iterates over every packed `Long` coordinate in this volume without pre-allocating memory.
	 */
	fun primitivePositions(): Iterable<Long> = Iterable {
		object : LongIterator() {
			private var currentX = min.x
			private var currentY = min.y
			private var currentZ = min.z

			override fun hasNext(): Boolean = currentY <= max.y

			override fun nextLong(): Long {
				if (!hasNext()) throw NoSuchElementException()
				val packed = BlockPos.asLong(currentX, currentY, currentZ)

				currentX++
				if (currentX > max.x) {
					currentX = min.x
					currentZ++
					if (currentZ > max.z) {
						currentZ = min.z
						currentY++
					}
				}
				return packed
			}
		}
	}

	/** [min]/[max] as a vanilla [BoundingBox], for APIs that expect one. */
	fun toBoundingBox(): BoundingBox = BoundingBox(min.x, min.y, min.z, max.x, max.y, max.z)

	/** Whether [pos] sits on this footprint's border, inline with the outer rail lines - where [net.kernelpanicsoft.boilerplate.warehouse.block.WarehouseControllerBlock] is required to bind (see [railPerimeter]). */
	fun isOnBorder(pos: BlockPos): Boolean = pos.x == min.x || pos.x == max.x || pos.z == min.z || pos.z == max.z

	/**
	 * The hollow rectangle [net.kernelpanicsoft.boilerplate.warehouse.block.GantryRailBlock] frame traces around this footprint's border, at [max]'s
	 * y (rail height) - every position with `x`/`z` on the min/max edge, inclusive of corners, each
	 * listed once.
	 */
	fun railPerimeter(): Set<BlockPos> {
		val y = max.y
		val positions = mutableListOf<BlockPos>()
		for (x in min.x..max.x) {
			positions += BlockPos(x, y, min.z)
			if (max.z != min.z) positions += BlockPos(x, y, max.z)
		}
		for (z in (min.z + 1) until max.z) {
			positions += BlockPos(min.x, y, z)
			if (max.x != min.x) positions += BlockPos(max.x, y, z)
		}
		return positions.map { it.immutable() }.toSortedSet()
	}

	fun railSupports(): Set<BlockPos> {
		val positions = mutableListOf<BlockPos>()
		for ((x, z) in listOf(
			min.x to min.z,
			max.x to min.z,
			min.x to max.z,
			max.x to max.z
		)) {
			for (y in min.y until max.y) {
				positions += BlockPos(x, y, z)
			}
		}
		return positions.map { it.immutable() }.toSortedSet()
	}

	fun railStructure(): Set<BlockPos> = (railPerimeter() + railSupports()).toSortedSet()

	companion object {
		/** Builds a [Bounds] from two arbitrary corners, normalizing them into (min, max) order regardless of which one the player clicked first. */
		fun of(first: BlockPos, second: BlockPos): Bounds = Bounds(
			BlockPos(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z)),
			BlockPos(maxOf(first.x, second.x), maxOf(first.y, second.y), maxOf(first.z, second.z)),
		)
	}
}

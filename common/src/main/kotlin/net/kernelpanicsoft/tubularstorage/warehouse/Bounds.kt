package net.kernelpanicsoft.tubularstorage.warehouse

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox

/**
 * The warehouse volume a [WarehouseWandItem] binds to a [WarehouseControllerBlockEntity] - always
 * normalized so [min]/[max] are the actual lower/upper corners regardless of the order the two
 * clicks that produced them came in (see [of]).
 */
@Serializable
data class Bounds(val min: SBlockPos, val max: SBlockPos) {
	/** Whether [pos] falls inside this volume, inclusive of both corners. */
	operator fun contains(pos: BlockPos): Boolean =
		pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z

	/** Every position inside this volume, inclusive of both corners. */
	fun positions(): Iterable<BlockPos> = BlockPos.betweenClosed(min, max)

	/** [min]/[max] as a vanilla [BoundingBox], for APIs that expect one. */
	fun toBoundingBox(): BoundingBox = BoundingBox(min.x, min.y, min.z, max.x, max.y, max.z)

	companion object {
		/** Builds a [Bounds] from two arbitrary corners, normalizing them into (min, max) order regardless of which one the player clicked first. */
		fun of(first: BlockPos, second: BlockPos): Bounds = Bounds(
			BlockPos(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z)),
			BlockPos(maxOf(first.x, second.x), maxOf(first.y, second.y), maxOf(first.z, second.z)),
		)
	}
}

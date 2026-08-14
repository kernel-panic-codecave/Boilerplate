package net.kernelpanicsoft.tubularstorage.warehouse

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * A warehouse's single binding point - holds the [Bounds] volume a [WarehouseWandItem] defines for
 * it (see `docs/design/m3-warehouse-storage.md`), and, from later M3 phases, the rack index/job
 * queue built over that volume.
 */
class WarehouseControllerBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.WarehouseController, pos, state) {

	@Sync
	private var boundsSlot: BoundsSlot by field(BoundsSlot.serializer()) { BoundsSlot() }

	/** The bound warehouse volume, or `null` until a [WarehouseWandItem] binds one. */
	var bounds: Bounds?
		get() = boundsSlot.bounds
		set(value) {
			boundsSlot = BoundsSlot(value)
		}
}

/**
 * Wraps [Bounds] so [WarehouseControllerBlockEntity.bounds] can round-trip as `null` while unbound
 * - a bare nullable [net.kernelpanicsoft.archie.serialization.NBTHolder.field] encodes rootless
 * (outside any structure), and knbt can't represent a bare `null` there; see
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookState]'s `ColorSlot` for the same
 * workaround - and, like that one, this must stay a plain data class, not a `@JvmInline value
 * class`, or the same encoding failure comes back.
 */
@Serializable
private data class BoundsSlot(val bounds: Bounds? = null)

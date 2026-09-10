package net.kernelpanicsoft.boilerplate.warehouse

import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.server.level.ServerLevel
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

/**
 * Plans a warehouse's own consolidation pass ("defrag" - see `docs/design/m3-warehouse-storage.md`'s
 * Job queue section): for every [earth.terrarium.common_storage_lib.resources.item.ItemResource]
 * scattered across more than one [WarehouseIndex.RackSlotRef], picks the single biggest existing
 * entry as the absorber and plans a [GantryJob.Move] draining as much as currently fits from each
 * other entry into it - biggest partial stacks absorb the smallest, the same way disk
 * defragmentation frees contiguous space. Purely a batch of ordinary moves against the existing
 * index; no new scanning or gantry machinery beyond [GantryJob.Move] itself. A capacity check here
 * is a simulated insert against the absorber's own live storage, not a guess from stale index data
 * - the same pattern [WarehouseControllerBlockEntity.bestRackFor] already uses for the identical
 * reason.
 *
 * Deliberately doesn't try to fully drain every scattered entry in one pass (a source with more
 * than currently fits in the absorber just moves the portion that does, leaving the rest for a
 * later run) - good enough for "opportunistic housekeeping," not worth the extra complexity of
 * picking a second/third absorber within one planning pass.
 */
object WarehouseDefragPlanner {
	fun plan(level: ServerLevel, controller: WarehouseControllerBlockEntity): List<GantryJob.Move> {
		val jobs = mutableListOf<GantryJob.Move>()

		for ((key, entries) in controller.index.locations) {
			// Any registered kind, not just items. Consolidation was item-only on the reasoning that
			// two half-full tanks "aren't wasting a slot the way two partial item stacks are" - but
			// that is exactly backwards in a warehouse whose racks *are* tanks: a tank holds one
			// fluid, so two half-full tanks of water occupy two of them and deny the second to
			// anything else. Merging them frees a whole rack, which is the same win consolidating
			// item stacks gives.
			val resource = key.resource
			val storageKind = ResourceKindRegistry.storageFor(resource) ?: continue
			if (entries.size <= 1) continue
			val sorted = entries.sortedByDescending { it.amount }
			val absorber = sorted.first()
			if (!level.hasChunk(absorber.pos.x shr 4, absorber.pos.z shr 4)) continue

			for (source in sorted.drop(1)) {
				if (!level.hasChunk(source.pos.x shr 4, source.pos.z shr 4)) continue
				val absorberStorage = storageKind.find(level, absorber.pos, absorber.direction) ?: continue
				val room = storageKind.roomFor(absorberStorage, resource, source.amount)
				if (room <= 0) continue
				jobs += GantryJob.Move(source, absorber.pos, absorber.direction, ResourceStack(resource, room))
			}
		}

		return jobs
	}
}

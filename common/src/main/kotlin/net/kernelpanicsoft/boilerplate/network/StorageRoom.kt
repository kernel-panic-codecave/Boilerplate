package net.kernelpanicsoft.boilerplate.network

import earth.terrarium.common_storage_lib.resources.Resource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage

/**
 * How much of [resource] this storage says it will accept right now, up to [limit].
 *
 * Simply the storage's own simulated insert, and deliberately so. An earlier version of this walked
 * each slot once instead, to work around Common Storage Lib's own whole-storage insert
 * (`TransferUtil.insertSubset`) walking the slot list **twice** - once over the occupied slots, then
 * once over all of them - so that a *simulated* insert counts the same free space twice and a chest
 * holding 62 of a 64 stack answers "3" to a request for 3.
 *
 * That workaround was unsound. It assumed `get(index)` is a faithful partition of the storage, which
 * is not part of the [CommonStorage] contract and is false for several of this mod's own storages:
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternBufferIO] exposes one slot per *pattern* while
 * inserting into whole nine-slot buffers (so a walk under-reports, and a delivery aimed at a pattern
 * provider stalls in the pipe forever), and an interface's pass-through surface answers every slot
 * with the entire network's room (so a walk over-reports by the slot count). A storage that
 * overrides `insert` with real admission logic is the only thing that can answer this correctly, so
 * this asks it.
 *
 * The upstream over-report is therefore still present here, and is handled where it actually
 * mattered instead: at the arrival gate in
 * [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity], which inserts and then takes back
 * anything that did not land as a whole batch - exact for every storage shape, because it measures
 * a real insert rather than predicting one.
 */
fun <T : Resource> CommonStorage<T>.roomFor(resource: T, limit: Long): Long {
	if (limit <= 0L) return 0L
	return insert(resource, limit, true)
}

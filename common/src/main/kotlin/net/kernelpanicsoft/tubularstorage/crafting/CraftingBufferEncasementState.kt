package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter

/**
 * One member of a Crafting CPU multiblock - see [CraftingBufferEncasementType] for the job execution
 * this state feeds. [localStorage] is this member's own slice of the cluster's combined pool;
 * [combinedStorage] concatenates every member's own via [CraftingCpuStorage], so placing another
 * encased segment against the cluster is what grows its capacity.
 *
 * The backlog and active job are deliberately not persisted, the same runtime-only tradeoff
 * [CraftingBufferJob] itself has: [localStorage]'s real contents are what survives a reload, and a
 * job with nothing left to show for itself afterward is treated as already finished.
 */
class CraftingBufferEncasementState : EncasementHolderState(CraftingBufferEncasementType.ID) {

	val localStorage: ArchieItemStorage by itemField(LOCAL_SLOTS)

	/**
	 * Whether this member's own cluster currently fills its bounding box exactly
	 * ([CraftingCpuManager.Cluster.valid]) - recomputed server-side for every member a cluster
	 * change could have flipped ([CraftingBufferEncasementType.onAttached]'s refresh) and synced to
	 * clients, whose render-state reads it to gate the casing's edge/corner pieces: those need
	 * whole-cluster knowledge no single client-side neighborhood probe can reconstruct cheaply.
	 */
	var formed: Boolean by field(Boolean.serializer()) { false }

	internal val backlog: ArrayDeque<CraftingBufferJob> = ArrayDeque()
	internal var activeJob: CraftingBufferJob? = null
	private var nextJobId: Int = 0

	/** This member's own cluster's combined storage - every member's [localStorage] concatenated, in cluster order. Falls back to just this member's own if [tile] isn't in a real [ServerLevel] yet, or the cluster's arrangement isn't a valid cuboid (see [CraftingCpuManager.Cluster.valid]) - a CPU that didn't form has no pool to concatenate. */
	fun combinedStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> {
		val level = tile.level as? ServerLevel ?: return CraftingCpuStorage(listOf(localStorage))
		val cluster = CraftingCpuManager.get(level).clusterOf(level, tile.blockPos)
		if (!cluster.valid) return CraftingCpuStorage(listOf(localStorage))
		val members = cluster.members.mapNotNull { craftingBufferAt(level, it)?.localStorage }
		return CraftingCpuStorage(members)
	}

	/** Queues [plan] as a new job on this member's own cluster - straight onto the active slot if idle, the backlog otherwise. Returns the new job's own id, for a submitting terminal to poll via [jobStatus]. */
	fun enqueue(plan: CraftingResolver.Plan): String {
		val id = (nextJobId++).toString()
		val job = CraftingBufferJob(id, plan.target, plan.targetAmount, plan.steps)
		for ((resource, amount) in plan.stockPulls) job.outstandingStockClaims[resource] = amount
		if (activeJob == null) activeJob = job else backlog += job
		return id
	}

	/** [id]'s own job, wherever it currently sits (active or still queued) - `null` once it's finished and cleared, or it never existed on this cluster. */
	fun jobStatus(id: String): CraftingBufferJob? = activeJob?.takeIf { it.id == id } ?: backlog.firstOrNull { it.id == id }

	/** How many jobs (active plus queued) this cluster is currently carrying - lower is preferred when a terminal picks among several reachable clusters. */
	fun backlogDepth(): Int = backlog.size + if (activeJob != null) 1 else 0

	/**
	 * Cancels [id]'s own job on this cluster, from
	 * [net.kernelpanicsoft.tubularstorage.crafting.gui.CraftingBufferMenu]'s own Cancel button. A
	 * still-queued backlog entry is simply dropped - nothing's been claimed for it yet.
	 * The active job instead flips its own [CraftingBufferJob.done] so
	 * [CraftingBufferEncasementType]'s existing per-tick drain pushes whatever it already claimed
	 * back onto the network before clearing it, exactly like an ordinary completion - reusing that
	 * path rather than a second bespoke "abandon" one. Returns whether a job matching [id] was
	 * actually found.
	 */
	fun cancelJob(id: String): Boolean {
		activeJob?.takeIf { it.id == id }?.let {
			it.done = true
			return true
		}
		return backlog.removeAll { it.id == id }
	}

	companion object {
		/** [localStorage]'s own slot count - the unit a cluster's combined capacity grows by per encased segment. */
		private const val LOCAL_SLOTS = 9
	}
}

/** The Crafting Buffer encasement wrapping the pipe segment at [pos], or `null` if that segment carries none. */
fun craftingBufferAt(level: BlockGetter, pos: BlockPos): CraftingBufferEncasementState? =
	(level.getBlockEntity(pos) as? MultipartBlockEntity)?.encasement?.value as? CraftingBufferEncasementState

/**
 * Concatenates every member's own [ArchieItemStorage] into one [CommonStorage] - a Crafting CPU
 * cluster's combined pool, sized by however many encased segments currently belong to it.
 */
class CraftingCpuStorage(private val members: List<ArchieItemStorage>) : CommonStorage<ItemResource> {
	override fun size(): Int = members.sumOf { it.size() }

	override fun get(index: Int): StorageSlot<ItemResource> {
		var remaining = index
		for (member in members) {
			if (remaining < member.size()) return member.get(remaining)
			remaining -= member.size()
		}
		throw IndexOutOfBoundsException("index $index out of bounds for a combined storage of size ${size()}")
	}

	override fun insert(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		for (member in members) {
			if (remaining <= 0) break
			remaining -= member.insert(resource, remaining, simulate)
		}
		return amount - remaining
	}

	override fun extract(resource: ItemResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		for (member in members) {
			if (remaining <= 0) break
			remaining -= member.extract(resource, remaining, simulate)
		}
		return amount - remaining
	}
}

/** Total amount of [resource] currently sitting across every slot of [storage] - a generic [CommonStorage] has no direct "how much of X do I hold" query of its own. */
internal fun amountIn(storage: CommonStorage<ItemResource>, resource: ItemResource): Long {
	var total = 0L
	for (i in 0 until storage.size()) {
		val slot = storage.get(i)
		if (slot.resource == resource) total += slot.amount
	}
	return total
}

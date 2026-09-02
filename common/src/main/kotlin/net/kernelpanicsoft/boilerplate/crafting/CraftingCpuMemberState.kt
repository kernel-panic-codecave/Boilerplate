package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter

/**
 * One member of a Crafting CPU multiblock, whichever kind it is - a
 * [CraftingBufferEncasementState] contributing item slots, or a [CraftingTankEncasementState]
 * contributing fluid tanks. Both cluster together through the same [CraftingCpuManager], because a
 * [Pattern] may now name a fluid on either side and a CPU that could only hold items would have
 * nowhere to stage one.
 *
 * The job queue lives here rather than on the item member specifically: the cluster's own leader is
 * simply its lowest [BlockPos], which may perfectly well be a tank, and job execution
 * ([CraftingCpuRuntime]) is identical either way. What differs between the kinds is only which pool
 * a member contributes to - see [localItemStorage]/[localFluidStorage], both `null` by default so a
 * future third kind adds a pool without touching either existing one.
 *
 * Backlog and active job are deliberately not persisted, the same runtime-only tradeoff
 * [CraftingBufferJob] itself has: the pools' real contents are what survives a reload, and a job
 * with nothing left to show for itself afterward is treated as already finished.
 */
abstract class CraftingCpuMemberState(defaultType: ResourceLocation) : EncasementHolderState(defaultType), PressureConsumer {

	/** This member's own contribution to the cluster's **item** pool, or `null` if it contributes none (a tank). */
	open val localItemStorage: ArchieItemStorage? get() = null

	/** This member's own contribution to the cluster's **fluid** pool, or `null` if it contributes none (a buffer). */
	open val localFluidStorage: ArchieFluidStorage? get() = null

	internal val backlog: ArrayDeque<CraftingBufferJob> = ArrayDeque()
	internal var activeJob: CraftingBufferJob? = null
	private var nextJobId: Int = 0

	/**
	 * This member's own cluster's combined **item** pool - every buffer member's own
	 * [localItemStorage] concatenated, in cluster order. Falls back to just this member's own if
	 * [tile] isn't in a real [ServerLevel] yet, or the cluster's arrangement isn't a valid cuboid
	 * (see [CraftingCpuManager]) - a CPU that didn't form has no pool to concatenate.
	 */
	fun combinedStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> =
		CraftingCpuStorage(membersOf(tile) { it.localItemStorage })

	/** [combinedStorage]'s fluid counterpart - every tank member's own [localFluidStorage] concatenated. Empty (accepting nothing) on a cluster with no tank in it at all. */
	fun combinedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource> =
		CraftingCpuFluidStorage(membersOf(tile) { it.localFluidStorage })

	/** Every member's own [select]ed storage across this member's cluster, in cluster order, skipping members that contribute none - see [combinedStorage]. */
	private fun <S> membersOf(tile: MultipartBlockEntity, select: (CraftingCpuMemberState) -> S?): List<S> {
		val own = listOfNotNull(select(this))
		val level = tile.level as? ServerLevel ?: return own
		val cluster = CraftingCpuManager.get(level).clusterOf(level, tile.blockPos)
		if (!cluster.valid) return own
		return cluster.members.mapNotNull { craftingCpuMemberAt(level, it)?.let(select) }
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
	 * [net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu]'s own Cancel button. A
	 * still-queued backlog entry is simply dropped - nothing's been claimed for it yet.
	 * The active job instead flips its own [CraftingBufferJob.done] so [CraftingCpuRuntime]'s
	 * existing per-tick drain pushes whatever it already claimed back onto the network before
	 * clearing it, exactly like an ordinary completion - reusing that path rather than a second
	 * bespoke "abandon" one. Returns whether a job matching [id] was actually found.
	 */
	fun cancelJob(id: String): Boolean {
		activeJob?.takeIf { it.id == id }?.let {
			it.done = true
			return true
		}
		return backlog.removeAll { it.id == id }
	}

	/** Nonzero only while [activeJob] is running - an idle leader draining leftovers ([CraftingCpuRuntime.drainEverything]) isn't doing work pressure should scale, so it costs nothing. */
	override val basePressureCost: Long get() = if (activeJob != null) BASE_PRESSURE_COST else 0

	/** See [basePressureCost]. */
	override val maxPressureDraw: Long get() = if (activeJob != null) MAX_PRESSURE_DRAW else 0

	companion object {
		private const val BASE_PRESSURE_COST = 10L
		private const val MAX_PRESSURE_DRAW = 20L
	}
}

/** The Crafting CPU member (buffer or tank) wrapping the pipe segment at [pos], or `null` if that segment carries neither. */
fun craftingCpuMemberAt(level: BlockGetter, pos: BlockPos): CraftingCpuMemberState? =
	(level.getBlockEntity(pos) as? MultipartBlockEntity)?.encasement?.value as? CraftingCpuMemberState

/** The Crafting Buffer encasement wrapping the pipe segment at [pos], or `null` if that segment carries none (a Crafting Tank included - it is a member, but not a buffer). */
fun craftingBufferAt(level: BlockGetter, pos: BlockPos): CraftingBufferEncasementState? =
	(level.getBlockEntity(pos) as? MultipartBlockEntity)?.encasement?.value as? CraftingBufferEncasementState

/**
 * Concatenates every buffer member's own [ArchieItemStorage] into one [CommonStorage] - a Crafting
 * CPU cluster's combined item pool, sized by however many encased segments currently belong to it.
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

/** [CraftingCpuStorage]'s fluid counterpart - every Crafting Tank member's own [ArchieFluidStorage] concatenated into the cluster's combined fluid pool. */
class CraftingCpuFluidStorage(private val members: List<ArchieFluidStorage>) : CommonStorage<FluidResource> {
	override fun size(): Int = members.sumOf { it.size() }

	override fun get(index: Int): StorageSlot<FluidResource> {
		var remaining = index
		for (member in members) {
			if (remaining < member.size()) return member.get(remaining)
			remaining -= member.size()
		}
		throw IndexOutOfBoundsException("index $index out of bounds for a combined storage of size ${size()}")
	}

	override fun insert(resource: FluidResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		for (member in members) {
			if (remaining <= 0) break
			remaining -= member.insert(resource, remaining, simulate)
		}
		return amount - remaining
	}

	override fun extract(resource: FluidResource, amount: Long, simulate: Boolean): Long {
		var remaining = amount
		for (member in members) {
			if (remaining <= 0) break
			remaining -= member.extract(resource, remaining, simulate)
		}
		return amount - remaining
	}
}

/**
 * Total amount of [resource] currently sitting across every slot of [storage] - a generic
 * [CommonStorage] has no direct "how much of X do I hold" query of its own.
 *
 * Identity-compared rather than `==`, so it answers correctly for a fluid pool too - see
 * [ResourceIdentity].
 */
internal fun amountIn(storage: CommonStorage<*>, resource: ResourceComponent): Long {
	val key = ResourceIdentity.of(resource)
	var total = 0L
	for (i in 0 until storage.size()) {
		val slot = storage.get(i)
		val held = slot.resource as? ResourceComponent ?: continue
		if (!held.isBlank && ResourceIdentity.of(held) == key) total += slot.amount
	}
	return total
}

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
import net.kernelpanicsoft.boilerplate.pipe.network.RoutingDemand
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace

/**
 * One member of a Crafting CPU multiblock - today always a [CraftingBufferEncasementState], whose
 * slots take any registered kind.
 *
 * Still an abstraction over "a member" rather than being folded into that class, because it is what
 * a second member type would extend. There was one - a Crafting Tank, back when a buffer could hold
 * only items and a [Pattern] naming a fluid had nowhere to stage it - and it stopped earning its
 * keep the moment a buffer could hold anything.
 *
 * The job queue lives here rather than on the buffer specifically: the cluster's own leader is
 * simply its lowest [BlockPos], which may perfectly well be a tank, and job execution
 * ([CraftingCpuRuntime]) is identical either way. What differs between the member kinds is only
 * what each contributes to the cluster's pools - see [localStorageFor], which answers `null` by
 * default so a future member type adds a pool without touching either existing one.
 *
 * Backlog and active job are deliberately not persisted, the same runtime-only tradeoff
 * [CraftingBufferJob] itself has: the pools' real contents are what survives a reload, and a job
 * with nothing left to show for itself afterward is treated as already finished.
 */
abstract class CraftingCpuMemberState(defaultType: ResourceLocation) : EncasementHolderState(defaultType), PressureConsumer {

	/**
	 * This member's own contribution to its cluster's pool of [kind], or `null` if it contributes
	 * none.
	 *
	 * A question rather than a declared `pooledKind` and one pool, because a member may pool *many*
	 * kinds: a Crafting Buffer holds whatever a pattern names now, so it answers for every
	 * registered kind at once. A member that pools exactly one - a Crafting Tank, which holds a lot
	 * of one fluid-like thing rather than a little of anything - simply answers for that one and
	 * `null` otherwise.
	 *
	 * This is all a newly registered kind needs to have a Crafting CPU stage it.
	 */
	open fun localStorageFor(kind: ResourceKind): CommonStorage<*>? = null

	internal val backlog: ArrayDeque<CraftingBufferJob> = ArrayDeque()
	/** Assigning this starts or stops the cluster awaiting its job's own outputs, so it bumps [RoutingDemand] - see there for what routing does with that. */
	internal var activeJob: CraftingBufferJob? = null
		set(value) {
			if (field === value) return
			field = value
			RoutingDemand.changed()
		}
	private var nextJobId: Int = 0

	/**
	 * This member's own cluster's combined **item** pool - every member's own item contribution
	 * concatenated, in cluster order. Falls back to just this member's own if
	 * [tile] isn't in a real [ServerLevel] yet, or the cluster's arrangement isn't a valid cuboid
	 * (see [CraftingCpuManager]) - a CPU that didn't form has no pool to concatenate.
	 */
	@Suppress("UNCHECKED_CAST")
	fun combinedStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> =
		combinedFor(tile, ResourceKindRegistry.Item) as CommonStorage<ItemResource>

	/** [combinedStorage]'s fluid counterpart - every member's own fluid contribution concatenated. Empty (accepting nothing) on a cluster that can hold no fluid at all. */
	@Suppress("UNCHECKED_CAST")
	fun combinedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource> =
		combinedFor(tile, ResourceKindRegistry.Fluid) as CommonStorage<FluidResource>

	/**
	 * This member's own cluster's combined pool of [kind] - every member pooling that kind
	 * concatenated, in cluster order. Empty (accepting nothing) on a cluster with no such member in
	 * it at all, and `null` only for a kind that cannot be stored.
	 *
	 * The kind-agnostic form the two typed accessors above are now written in terms of, and what
	 * makes a newly registered kind poolable here with no edit to this class.
	 */
	fun combinedFor(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>? =
		kind.storage?.combine(membersOf(tile) { it.localStorageFor(kind) })

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
		for ((resource, amount) in plan.stockPulls) {
			job.outstandingStockClaims[resource] = amount
			job.stockPlanned[resource] = amount
		}
		if (activeJob == null) activeJob = job else backlog += job
		ResourceTrace.job("queued", job, "steps" to job.steps.size, "backlog" to backlog.size)
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

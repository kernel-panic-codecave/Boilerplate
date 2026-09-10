package net.kernelpanicsoft.boilerplate.pipe.entity

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.ResourceStorage
import net.kernelpanicsoft.boilerplate.pipe.hook.acceptedAtRouteEnd
import net.kernelpanicsoft.boilerplate.pipe.hook.batchedForRoute
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * The face a pipe segment presents to whatever is pushing into it: an insert is routed straight
 * into the network, or refused with `0`.
 *
 * **Every** segment presents one, on every face that has nothing more specific to say. That is what
 * lets a machine with its own auto-output feed the network without a hook dedicated to receiving
 * it: point the machine at a pipe and its output goes wherever the network wants it. Automating a
 * machine is then a Pattern Provider and nothing else - the provider pushes ingredients in, the
 * machine pushes its result back out into the same pipe, and the CPU sees it arrive.
 *
 * [backing] is what reads and extractions hit - an interface hook's own stock, so a machine can
 * pull stocked resources out of it like any inventory. A bare pipe has nothing to offer and passes
 * [emptyFace], which reports one empty slot: **not** zero slots, because a machine that inserts
 * per-slot (which most do) sees a storage with no slots as having nowhere to put anything and never
 * offers it a thing.
 *
 * [stage] is the opt-in half: a storage that gets first refusal on every insert, with only what it
 * declines routing onward. Off by default, and deliberately so - an interface hook stages *nothing*,
 * because anything pushed at it belongs on the far side of the subnet boundary it anchors. A Pattern
 * Provider is the case that wants it: the pattern's ingredient buffer keeps what that pattern
 * actually consumes, and the machine's own *result*, which the buffer refuses, routes onto the
 * network instead of having nowhere to go. A hook answering alone, with no pass-through behind it,
 * leaves that result stuck in the machine with nowhere to be put.
 *
 * Insertion is otherwise pass-through on every write path a machine can reach: the whole-storage
 * insert, slot-indexed inserts, and individual [StorageSlot] writes alike (Common Storage Lib's own
 * NeoForge `IItemHandler` bridge, which most cross-mod machines drive items through, writes per-slot
 * via [CommonStorage.get] - if the slots leaked through to [backing], such machines would stage
 * resources instead of scattering them into the network).
 *
 * Re-entrancy (a probe of this surface from within its own route search - two pass-throughs pointing
 * at each other) resolves as a rejected insert rather than recursing: while one is in flight, any
 * nested entry for the same position returns `0`. The guard is per *position*, not per kind or per
 * face, since a loop is a loop whichever way it re-enters.
 */
class PassThroughStorage(
	private val tile: PipeBlockEntity,
	private val face: Direction,
	private val backing: CommonStorage<ResourceComponent>,
	private val stage: CommonStorage<ResourceComponent>? = null,
) : CommonStorage<ResourceComponent> {

	override fun size(): Int = backing.size()

	/** Slot-valued reads and extractions resolve to the real [backing] slot; slot inserts join the pass-through surface. */
	override fun get(index: Int): StorageSlot<ResourceComponent> = PassThroughSlot(backing[index])

	override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
		stageThenRoute(resource, amount, simulate)

	/** The destination slot is irrelevant - only whether [stage] or the network accepts the resource. */
	override fun insert(index: Int, resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
		stageThenRoute(resource, amount, simulate)

	override fun extract(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
		backing.extract(resource, amount, simulate)

	private inner class PassThroughSlot(private val delegate: StorageSlot<ResourceComponent>) :
		StorageSlot<ResourceComponent> by delegate {
		override fun insert(resource: ResourceComponent, amount: Long, simulate: Boolean): Long =
			stageThenRoute(resource, amount, simulate)
	}

	/**
	 * [stage]'s first refusal, then the network for whatever it left - the single insert rule every
	 * write path above shares. A face with no [stage] routes the lot, which is every bare pipe and
	 * every hook but one; see this class's own KDoc.
	 */
	private fun stageThenRoute(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val staged = stage?.insert(resource, amount, simulate) ?: 0L
		if (staged >= amount) return staged
		return staged + passThrough(resource, amount - staged, simulate)
	}

	private fun passThrough(resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0) return 0
		val level = tile.level as? ServerLevel ?: return 0
		val networkType = networkTypeForResource(resource) ?: return 0
		val pos = tile.blockPos
		val key = pos.asLong()
		if (!PASS_THROUGH_IN_FLIGHT.get().add(key)) return 0
		try {
			// Only a directly-adjacent feeder - a real accepting storage - is shut out of the route,
			// so a resource never bounces straight back into the block that pushed it. A neighbouring
			// pipe is never a route destination anyway, and excluding one would sever this segment's
			// own onward path.
			val source = pos.relative(face).takeIf { !networkType.isPipeAt(level, it) }
			val route = networkType.route(level, pos, ResourceStack(resource, amount), exclude = setOfNotNull(source)) ?: return 0
			// Only ever as much as the far end will take - see acceptedAtRouteEnd - and then only a
			// whole multiple of any batch the destination demands, so a pass-through cannot deliver
			// the partial an extraction would have withheld.
			val deliverable = batchedForRoute(
				level, pos, route, resource, amount,
				room = acceptedAtRouteEnd(level, pos, route, resource, amount),
			)
			if (deliverable <= 0) return 0
			if (simulate) return deliverable
			tile.acceptEntry(ResourceStack(resource, deliverable), face, route)
			return deliverable
		}
		finally {
			PASS_THROUGH_IN_FLIGHT.get().remove(key)
		}
	}

	companion object {
		/**
		 * The read side of a face with nothing behind it - one slot per kind, permanently empty.
		 *
		 * Cached rather than built per lookup: a capability query happens constantly, and nothing
		 * ever writes here (every insert path above is intercepted before it reaches [backing]), so
		 * one instance per kind is safe for every hookless face on every segment in the level. That
		 * one slot is what gives a machine a slot to aim at, with that kind's own blank and limit.
		 *
		 * Keyed per kind, and built on first use, rather than one [ResourceStorage] covering every
		 * kind at once: that one would snapshot [net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry]
		 * the moment this class was first loaded, and a kind a loader registers - chemicals - would
		 * be missing from it for good if that happened to come first. A kind that registers no
		 * storage has no face to offer and answers `null`.
		 */
		private val emptyFaces = ConcurrentHashMap<ResourceKind, CommonStorage<out ResourceComponent>>()

		private fun emptyFace(kind: ResourceKind): CommonStorage<out ResourceComponent>? =
			emptyFaces.getOrPut(kind) { ResourceStorage(slots = 1, kinds = listOf(kind)).viewOf(kind) ?: return null }

		/**
		 * The pass-through [kind] sees on [face] of [tile], reading from [backing] - or from nothing,
		 * for a face that merely conducts.
		 *
		 * [stage] opts into first refusal on inserts; omit it for a face that should route
		 * everything pushed at it. See this class's own KDoc for which is which.
		 */
		@Suppress("UNCHECKED_CAST")
		fun of(
			tile: PipeBlockEntity,
			face: Direction,
			kind: ResourceKind,
			backing: CommonStorage<*>? = null,
			stage: CommonStorage<*>? = null,
		): CommonStorage<*>? {
			val read = backing ?: emptyFace(kind) ?: return null
			return PassThroughStorage(
				tile, face,
				read as CommonStorage<ResourceComponent>,
				stage as CommonStorage<ResourceComponent>?,
			)
		}
	}
}

/**
 * Positions currently mid pass-through resolution on this thread. A route search that probes back
 * into a surface already resolving - its own, or another in-flight one - would otherwise recurse
 * forever. See [PassThroughStorage].
 */
internal val PASS_THROUGH_IN_FLIGHT = object : ThreadLocal<MutableSet<Long>>() {
	override fun initialValue(): MutableSet<Long> = hashSetOf()
}

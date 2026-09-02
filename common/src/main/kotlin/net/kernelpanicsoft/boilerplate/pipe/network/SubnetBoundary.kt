package net.kernelpanicsoft.boilerplate.pipe.network

import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * A hook facing directly into another hook normally just merges the two sides into one flat pipe
 * network, same as any other pipe-to-pipe connection (`docs/design/m2-sorting-routing.md`'s
 * "Future: hook-to-hook/hook-to-pipe facing as a subnet boundary" section) - *except* when one side
 * is an [InterfaceHookType] hook, which deliberately keeps the two sides logically separate
 * networks instead, bridged only through that hook's own [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState.stock].
 * [isBoundaryEdge] is that one check, shared by every place that otherwise treats hook-carrying
 * pipe adjacency as free-flowing topology: [PipeNetworkManager.ensureRegistered] (network
 * merge/version bookkeeping), [PipeRouter.step] (push-routing BFS), and
 * [RequestFulfillment.reachablePipes] (request/terminal reachability BFS) each stop right at a
 * boundary edge instead of walking straight through it - what happens *at* that edge (extract-only,
 * insert-only, filtered two-way, or an active hook's own direct pull/push) is each of those
 * partner hook types' own concern, not this object's.
 */
object SubnetBoundary {
	fun isBoundaryEdge(level: ServerLevel, pos: BlockPos, direction: Direction): Boolean {
		val ownHook = hookFacing(level, pos, direction) ?: return false
		val neighborHook = hookFacing(level, pos.relative(direction), direction.opposite) ?: return false
		return ownHook.type == InterfaceHookType.ID || neighborHook.type == InterfaceHookType.ID
	}

	/**
	 * The [InterfaceHookState] attached to [pos]'s [direction] face, if any - for
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType]/
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType]'s own active boundary-partner
	 * roles, which need the state itself (to read/supply [InterfaceHookState.stock]) rather than
	 * just [isBoundaryEdge]'s yes/no.
	 */
	fun interfaceAt(level: ServerLevel, pos: BlockPos, direction: Direction): InterfaceHookState? =
		hookFacing(level, pos, direction) as? InterfaceHookState

	/**
	 * The [RequesterHookState] attached to [pos]'s [direction] face, if any - [interfaceAt]'s mirror,
	 * for the interface side of the same partnership.
	 *
	 * An interface needs this because a requester facing it *takes over* what that interface is
	 * stocked with (see [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType]): the
	 * interface has to know its own ghost row is out of play, or it would keep requisitioning
	 * against it and draining whatever the requester supplied straight back out.
	 */
	fun requesterAt(level: ServerLevel, pos: BlockPos, direction: Direction): RequesterHookState? =
		hookFacing(level, pos, direction) as? RequesterHookState
}

package net.kernelpanicsoft.tubularstorage.pipe.network

import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * A hook facing directly into another hook normally just merges the two sides into one flat pipe
 * network, same as any other pipe-to-pipe connection (`docs/design/m2-sorting-routing.md`'s
 * "Future: hook-to-hook/hook-to-pipe facing as a subnet boundary" section) - *except* when one side
 * is an [InterfaceHookType] hook, which deliberately keeps the two sides logically separate
 * networks instead, bridged only through that hook's own [net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState.stock].
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
		val ownHook = hookAt(level, pos, direction) ?: return false
		val neighborHook = hookAt(level, pos.relative(direction), direction.opposite) ?: return false
		return ownHook.type == InterfaceHookType.ID || neighborHook.type == InterfaceHookType.ID
	}

	/**
	 * The [InterfaceHookState] attached to [pos]'s [direction] face, if any - for
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType]/
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType]'s own active boundary-partner
	 * roles, which need the state itself (to read/supply [InterfaceHookState.stock]) rather than
	 * just [isBoundaryEdge]'s yes/no.
	 */
	fun interfaceAt(level: ServerLevel, pos: BlockPos, direction: Direction): InterfaceHookState? =
		hookAt(level, pos, direction) as? InterfaceHookState

	private fun hookAt(level: ServerLevel, pos: BlockPos, direction: Direction): HookHolderState? =
		(level.getBlockEntity(pos) as? MultipartBlockEntity)?.hooks?.get(direction.name) as? HookHolderState
}

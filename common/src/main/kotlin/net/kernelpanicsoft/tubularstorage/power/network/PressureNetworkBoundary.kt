package net.kernelpanicsoft.tubularstorage.power.network

import net.kernelpanicsoft.tubularstorage.pipe.network.SubnetBoundary
import net.kernelpanicsoft.tubularstorage.pipe.network.adapterBridges
import net.kernelpanicsoft.tubularstorage.pipe.network.primaryNetworkTypeAt
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * The pressure-side sibling of [net.kernelpanicsoft.tubularstorage.pipe.network.SubnetBoundary]: an
 * item-pipe segment always conducts pressure among itself and into a directly adjacent
 * [net.kernelpanicsoft.tubularstorage.power.PressureApi] block (`PressureNetworkType`), but two
 * edges are boundaries by default, each bridgeable only by an
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.AdapterHookType] hook facing across it (see
 * [net.kernelpanicsoft.tubularstorage.pipe.network.adapterBridges]):
 *
 * - where it touches a *dedicated* [net.kernelpanicsoft.tubularstorage.power.block.PressurePipeBlock]
 *   run - a segment whose own [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.primaryNetworkType]
 *   is `Pressure` rather than carrying it merely as a secondary;
 * - [SubnetBoundary]'s own item-network boundary (an [net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookType]
 *   hook facing another hook) - the two sides are logically separate *networks* by design, and
 *   pressure crossing there for free same as it crosses an ordinary unhooked pipe-to-pipe
 *   connection would undermine that separation just as much as an item silently routing straight
 *   through would.
 *
 * [isBoundaryEdge] is that combined check, consulted by [PressurePipeNetworkManager.isBoundaryEdge]
 * exactly like [SubnetBoundary.isBoundaryEdge] is consulted by
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager] - and, since [PressureLine.find]
 * needs to respect the same boundary its own neighbor-network fallback would otherwise blindly reach
 * across, also by a caller whose own [pos] isn't a pipe at all (a non-pipe
 * [net.kernelpanicsoft.tubularstorage.power.PressureConsumer] like a warehouse controller, sitting
 * directly against a dedicated Pressure Pipe run). The primary-mismatch check below requires *both*
 * sides to actually resolve a primary type for exactly that reason - a non-pipe consumer's own
 * [primaryNetworkTypeAt] is `null`, and treating `null` as "mismatched" against literally any
 * neighbor's real primary would wrongly wall off every such consumer from a dedicated pressure run
 * sitting right next to it, which was never the point (only an actual item-pipe-vs-pressure-pipe
 * junction needs bridging).
 */
object PressureNetworkBoundary {
	fun isBoundaryEdge(level: ServerLevel, pos: BlockPos, direction: Direction): Boolean {
		val ownPrimary = primaryNetworkTypeAt(level, pos)
		val neighborPrimary = primaryNetworkTypeAt(level, pos.relative(direction))
		val primaryMismatch = ownPrimary != null && neighborPrimary != null && ownPrimary != neighborPrimary
		val subnetBoundary = SubnetBoundary.isBoundaryEdge(level, pos, direction)
		return (primaryMismatch || subnetBoundary) && !adapterBridges(level, pos, direction)
	}
}

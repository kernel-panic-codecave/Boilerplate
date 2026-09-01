package net.kernelpanicsoft.boilerplate.power.network

import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.pipe.network.adapterBridges
import net.kernelpanicsoft.boilerplate.pipe.network.primaryNetworkTypesAt
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * The pressure-side sibling of [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary]: an
 * item-pipe segment always conducts pressure among itself and into a directly adjacent
 * [net.kernelpanicsoft.boilerplate.power.PressureApi] block (`PressureNetworkType`), but two
 * edges are boundaries by default, each bridgeable only by an
 * [net.kernelpanicsoft.boilerplate.pipe.hook.AdapterHookType] hook facing across it (see
 * [net.kernelpanicsoft.boilerplate.pipe.network.adapterBridges]):
 *
 * - where it touches a *dedicated* [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]
 *   run - a segment whose own [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.primaryNetworkTypes]
 *   is `{Pressure}` rather than carrying it merely as a secondary;
 * - [SubnetBoundary]'s own item-network boundary (an [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType]
 *   hook facing another hook) - the two sides are logically separate *networks* by design, and
 *   pressure crossing there for free same as it crosses an ordinary unhooked pipe-to-pipe
 *   connection would undermine that separation just as much as an item silently routing straight
 *   through would.
 *
 * [isBoundaryEdge] is that combined check, consulted by [PressurePipeNetworkManager.isBoundaryEdge]
 * exactly like [SubnetBoundary.isBoundaryEdge] is consulted by
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager] - and, since [PressureLine.find]
 * needs to respect the same boundary its own neighbor-network fallback would otherwise blindly reach
 * across, also by a caller whose own [pos] isn't a pipe at all (a non-pipe
 * [net.kernelpanicsoft.boilerplate.power.PressureConsumer] like a warehouse controller, sitting
 * directly against a dedicated Pressure Pipe run). The primary-mismatch check below requires *both*
 * sides to actually resolve a primary set for exactly that reason - a non-pipe consumer's own
 * [primaryNetworkTypesAt] is empty, and treating empty as "mismatched" against literally any
 * neighbor's real primaries would wrongly wall off every such consumer from a dedicated pressure run
 * sitting right next to it, which was never the point (only an actual item-pipe-vs-pressure-pipe
 * junction needs bridging).
 */
object PressureNetworkBoundary {
	fun isBoundaryEdge(level: ServerLevel, pos: BlockPos, direction: Direction): Boolean {
		val ownPrimaries = primaryNetworkTypesAt(level, pos)
		val neighborPrimaries = primaryNetworkTypesAt(level, pos.relative(direction))
		val primaryMismatch = ownPrimaries.isNotEmpty() && neighborPrimaries.isNotEmpty() && ownPrimaries.none { it in neighborPrimaries }
		val subnetBoundary = SubnetBoundary.isBoundaryEdge(level, pos, direction)
		return (primaryMismatch || subnetBoundary) && !adapterBridges(level, pos, direction)
	}
}

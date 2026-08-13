package net.kernelpanicsoft.tubularstorage.pipe.entity

import kotlinx.serialization.Serializable
import net.minecraft.core.Direction

/**
 * One [RoutingModule] per face of a [PipeBlockEntity], read/written as a single
 * `@Sync`-annotated field ([PipeBlockEntity.routing]) rather than six separate fields or a
 * `Direction`-keyed map: Archie's `BlockEntityStateContainer` derives the synced-property key a
 * multi-word property name would need (e.g. `routingNorth`) differently depending on whether it's
 * discovered via its own reflective scan (raw property name) or via
 * [net.kernelpanicsoft.archie.serialization.NBTHolder]'s manual registration
 * (`property.name.toSnakeCase()`), so a multi-word `@Sync` scalar field silently fails to sync -
 * see `docs/design/m1-pipe-network.md`. A single, already-working `routing` (one word) field
 * sidesteps that entirely.
 */
@Serializable
data class FaceRouting(
	val north: RoutingModule = RoutingModule(),
	val south: RoutingModule = RoutingModule(),
	val east: RoutingModule = RoutingModule(),
	val west: RoutingModule = RoutingModule(),
	val up: RoutingModule = RoutingModule(),
	val down: RoutingModule = RoutingModule(),
) {
	operator fun get(direction: Direction): RoutingModule = when (direction) {
		Direction.NORTH -> north
		Direction.SOUTH -> south
		Direction.EAST -> east
		Direction.WEST -> west
		Direction.UP -> up
		Direction.DOWN -> down
	}

	fun with(direction: Direction, routing: RoutingModule): FaceRouting = when (direction) {
		Direction.NORTH -> copy(north = routing)
		Direction.SOUTH -> copy(south = routing)
		Direction.EAST -> copy(east = routing)
		Direction.WEST -> copy(west = routing)
		Direction.UP -> copy(up = routing)
		Direction.DOWN -> copy(down = routing)
	}
}

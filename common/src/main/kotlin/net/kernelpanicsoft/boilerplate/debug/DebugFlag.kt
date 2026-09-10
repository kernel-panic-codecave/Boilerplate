package net.kernelpanicsoft.boilerplate.debug

import kotlinx.serialization.Serializable

/**
 * One independently switchable debug subsystem - what `/bp debug <id>` turns on and off, and the
 * granularity every gate downstream reads.
 *
 * Each is a whole vertical slice rather than a single drawing: a flag gates the client renderer
 * that draws it *and* the server-side work that feeds it (a snapshot broadcast, a trace funnel), so
 * turning one on is what makes that work happen at all and leaving it off costs a boolean read per
 * tick. That pairing is the reason these are named per subsystem rather than per line drawn - the
 * server has nothing to send for half a subsystem.
 *
 * @property id what the command spells, and the stable name this serializes as.
 * @property summary one line for `/bp debug`'s own listing.
 */
@Serializable
enum class DebugFlag(val id: String, val summary: String) {
	/**
	 * Pipe networks and route searches: a wireframe hugging each network's real block geometry,
	 * every hop a recent extraction's search considered, and the route it settled on.
	 *
	 * Gates [net.kernelpanicsoft.boilerplate.pipe.network.DebugRouteTrace] and the snapshot
	 * broadcast that carries it ([net.kernelpanicsoft.boilerplate.network.DebugNetworkSync]).
	 */
	NETWORK("network", "Pipe network volumes, route searches and the chosen route"),

	/**
	 * Warehouses: each bound volume, the racks its index found, the crane's queued and in-flight
	 * hops, and the head's own path and status colour.
	 *
	 * Gates [net.kernelpanicsoft.boilerplate.network.WarehouseDebugSync].
	 */
	WAREHOUSE("warehouse", "Warehouse volumes, indexed racks and gantry jobs"),

	/**
	 * The resource hand-off log - every point a resource changes hands, written to the server log
	 * rather than drawn.
	 *
	 * Gates [ResourceTrace], which is the one flag here with no in-world drawing of its own: it is
	 * how a quantity that goes missing is followed to the step that lost it.
	 */
	TRACE("trace", "Log every resource hand-off, to find where a quantity goes missing");

	companion object {
		/** The flag [id] names, or `null` for an id nothing declares. */
		fun byId(id: String): DebugFlag? = entries.firstOrNull { it.id == id }
	}
}

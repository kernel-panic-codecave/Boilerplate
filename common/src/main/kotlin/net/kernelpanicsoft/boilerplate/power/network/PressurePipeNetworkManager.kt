package net.kernelpanicsoft.boilerplate.power.network

import earth.terrarium.common_storage_lib.storage.util.TransferUtil
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager
import net.kernelpanicsoft.boilerplate.pipe.network.networkTypesAt
import net.kernelpanicsoft.boilerplate.power.PressureApi
import net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock
import net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager.Companion.EQUALIZE_EPSILON
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.*

/**
 * The pressure-pipe [AbstractPipeNetworkManager] - members are any position whose
 * [net.kernelpanicsoft.boilerplate.pipe.network.networkTypesAt] includes [PressureNetworkType]:
 * a dedicated [PressurePipeBlock] (carries it as primary) and a plain item pipe alike (carries it
 * as secondary - a plain item pipe conducts pressure alongside items, so it doesn't take a
 * dedicated [PressurePipeBlock] run to reach a tank/compressor). [isBoundaryEdge] is the one
 * exception: the specific edge between a dedicated [PressurePipeBlock] and an item-pipe-as-
 * secondary segment doesn't auto-merge - see [PressureNetworkBoundary].
 *
 * Also runs the per-tick [equalize] pass that makes "networked" pressure real - see the
 * architectural note in `docs/design/m5-pressure-power.md`: there is no network-level
 * [ArchieEnergyStorage] bucket, so every tank/compressor endpoint reachable on one network is
 * instead leveled toward the same fill *fraction* (not absolute amount, since tanks can differ in
 * capacity) each tick.
 */
class PressurePipeNetworkManager private constructor() : AbstractPipeNetworkManager<PressurePipeNetwork>() {
	override fun createNetwork(id: UUID): PressurePipeNetwork = PressurePipeNetwork(id)
	override fun isMember(level: ServerLevel, pos: BlockPos): Boolean =
		PressureNetworkType in networkTypesAt(level, pos)
	override fun isBoundaryEdge(level: ServerLevel, pos: BlockPos, direction: Direction): Boolean =
		PressureNetworkBoundary.isBoundaryEdge(level, pos, direction)

	/** Advances the base class's own chunked-rebuild [tick], then [equalize]s every currently-known network. */
	override fun tick(level: ServerLevel) {
		super.tick(level)
		for (network in allNetworks()) equalize(level, network)
	}

	/**
	 * Gathers every tank/compressor [ArchieEnergyStorage] adjacent to any of [network]'s own
	 * members (deduped by identity - two adjacent members can both border the same endpoint), then
	 * moves a bounded amount from the fullest toward the emptiest, by fill *fraction*, until
	 * they're within [EQUALIZE_EPSILON] of each other or the per-tick budget runs out. Repeated
	 * every tick, this converges the whole network toward one shared level over a handful of
	 * ticks rather than in one jump - gentler than a single full-leveling pass, and cheap either
	 * way since a real network rarely holds more than a handful of endpoints.
	 */
	private fun equalize(level: ServerLevel, network: PressurePipeNetwork) {
		val endpoints = LinkedHashSet<ArchieEnergyStorage>()
		for (memberPos in network.members) {
			// See PressureLine.find's identical note: a tank/compressor encasement is itself a
			// member, so its own position is checked directly, not just its neighbors.
			(PressureApi.BLOCK.find(level, memberPos, Direction.NORTH) as? ArchieEnergyStorage)?.let { endpoints += it }
			for (direction in Direction.entries) {
				val storage = PressureApi.BLOCK.find(level, memberPos.relative(direction), direction.opposite) as? ArchieEnergyStorage ?: continue
				endpoints += storage
			}
		}
		if (endpoints.size < 2) return

		repeat(EQUALIZE_PASSES_PER_TICK) {
			val fullest = endpoints.maxByOrNull { fillFraction(it) } ?: return
			val emptiest = endpoints.minByOrNull { fillFraction(it) } ?: return
			if (fullest === emptiest) return
			if (fillFraction(fullest) - fillFraction(emptiest) <= EQUALIZE_EPSILON) return

			val amount = ((fillFraction(fullest) - fillFraction(emptiest)) * emptiest.capacity / 2)
				.toLong()
				.coerceIn(1, EQUALIZE_AMOUNT_PER_PASS)
			TransferUtil.moveValue(fullest, emptiest, amount, false)
		}
	}

	private fun fillFraction(storage: ArchieEnergyStorage): Double =
		if (storage.capacity <= 0) 0.0 else storage.storedAmount.toDouble() / storage.capacity

	companion object {
		/** How many endpoint pairs get leveled per network per tick - a flat cap, matching every other per-tick budget in this subsystem. */
		private const val EQUALIZE_PASSES_PER_TICK = 4

		/** Once the fullest and emptiest endpoint on a network are within this fraction of each other, [equalize] stops for that tick - avoids endlessly shuffling single units of pressure back and forth once they're already effectively level. */
		private const val EQUALIZE_EPSILON = 0.01

		/** Caps how much a single [TransferUtil.moveValue] call in [equalize] can move at once. */
		private const val EQUALIZE_AMOUNT_PER_PASS = 200L

		private val byLevel = WeakHashMap<ServerLevel, PressurePipeNetworkManager>()

		fun get(level: ServerLevel): PressurePipeNetworkManager = byLevel.getOrPut(level) { PressurePipeNetworkManager() }
	}
}

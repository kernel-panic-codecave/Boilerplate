package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionDistribution
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionDistributionSerializer
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.boilerplate.util.DirectionSerializer
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: the whole of an extraction hook's configuration, in one message.
 *
 * One packet rather than four because the screen holds all of it as a single piece of local state
 * and any edit rewrites that state - sending only the field that moved would mean four packets,
 * four handlers and four chances for them to arrive out of order against a hook the player is still
 * editing. The card itself is not here: it is a real vanilla slot and rides vanilla's own container
 * syncing.
 *
 * A nested [net.kernelpanicsoft.archie.serialization.NBTHolder] field is not wired into live GUI
 * observation the way a top-level `@Sync` field is, so an edit needs a packet at all - the same
 * reason [UpdateSortingRoutingPacket] and [UpdateFilterBatchPacket] exist, and the same
 * `hooks.touch()` and resync afterwards.
 */
@Serializable
data class UpdateExtractionConfigPacket(
	val pos: SBlockPos,
	val direction: @Serializable(with = DirectionSerializer::class) Direction,
	val filterMode: @Serializable(with = FilterModeSerializer::class) FilterMode,
	val distribution: @Serializable(with = ExtractionDistributionSerializer::class) ExtractionDistribution,
	val intervalTicks: Int,
	val amount: Long,
	val queueWholes: Int,
) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return
		val hookState = tile.hooks[direction.name] as? ExtractionHookState ?: return

		hookState.filterMode = filterMode
		hookState.distribution = distribution
		// Clamped here rather than trusted: these arrive from a client, and an interval of zero would
		// turn one hook into a pull on every tick of the server's own loop.
		hookState.intervalTicks = intervalTicks.coerceIn(1, ExtractionHookState.MAX_INTERVAL_TICKS)
		hookState.amountAuthored = amount.coerceIn(ExtractionHookState.KIND_DEFAULT_AMOUNT, MAX_AMOUNT)
		hookState.queueWholes = queueWholes.coerceIn(0, ExtractionHookState.MAX_QUEUE_WHOLES)
		// A cursor into a set of destinations chosen under the old settings means nothing under the
		// new ones - most obviously when the distribution mode itself just changed.
		hookState.servedThisCycle.clear()
		tile.hooks.touch()

		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}

	companion object {
		/** Matches the ceiling the screen offers - see [net.kernelpanicsoft.boilerplate.pipe.gui.ExtractionHookMenu.maxAmount]. */
		private val MAX_AMOUNT: Long get() = net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState.maxBatchSize
	}
}

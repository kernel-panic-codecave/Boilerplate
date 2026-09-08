package net.kernelpanicsoft.boilerplate.pipe.client

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Client-side cache of the last synced [TravelingItem]s per pipe, with [get] dead-reckoning each
 * item's [TravelingItem.progress] forward by elapsed **game time** since the sync was *generated*:
 * [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket.serverTick] anchors every payload
 * to the server tick it was sampled on, so [get] replays exactly the trajectory the server is
 * running. Wall-clock elapsed time (this cache's original receipt-time approach) let a stalled
 * render frame run an item on ahead of the server and snapped it back on the next sync - and, worse,
 * decoupled every adjacent block's copy from each other, so an item crossing a block boundary left
 * one fragment parked on the shared face plane until its own next sync while the neighbor's already
 * drew the same item from the same point.
 *
 * [get] deliberately does *not* clamp progress to `1f`, and **performs the hand-off itself**: once
 * an item's dead-reckoned progress runs past its exit face it stops being reported for the segment
 * it left and starts being reported for the one it entered, re-based to that segment's own entry
 * face. That is the same transfer the server makes, computed from data the client already holds -
 * the remaining path says exactly where the item goes next.
 *
 * Predicting it here rather than waiting for the packet is what stops an item blinking out at every
 * block boundary. A hand-off is two packets, and however tightly the server pairs them (it now
 * sends both on the same tick - see [PipeBlockEntity]'s own `syncNow`) they still take a moment to
 * arrive. In that window the departing segment's cached copy has already run past its exit face
 * while the receiving segment's copy has not appeared, so without this every crossing left the item
 * drawn by nobody for as long as the round trip took. With it, ownership moves the instant the
 * geometry says it should and the packets merely confirm what was already being drawn.
 *
 * A predicted arrival is dropped if the receiving segment's own entry already reports the same item
 * ([sameItem]), so the brief overlap while both packets land can't draw it twice.
 *
 * Only ever populated by [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket]'s
 * client-side handler, which is itself environment-gated - this object holds no client-only
 * references, so it's safe to load on a dedicated server, it's simply never written to there.
 */
object PipeContentsClientCache {
	private data class Entry(val items: List<TravelingItem>, val serverTick: Long, val speedMultiplier: Float)

	private val entries = HashMap<BlockPos, Entry>()

	fun update(pos: BlockPos, items: List<TravelingItem>, speedMultiplier: Float, serverTick: Long) {
		entries[pos] = Entry(items, serverTick, speedMultiplier)
	}

	/**
	 * [fractionalTick] is the caller's own current client game time on the render thread - whole
	 * ticks plus the frame's fractional tick - so each frame advances every item by exactly as much
	 * as the server did over the same span, at this block's own
	 * [net.kernelpanicsoft.boilerplate.network.PipeContentsSyncPacket.speedMultiplier] rather than
	 * the unpressurised baseline.
	 */
	fun get(pos: BlockPos, fractionalTick: Double): List<TravelingItem> {
		val result = ArrayList<TravelingItem>()

		entries[pos]?.let { entry ->
			val advance = advanceOf(entry, fractionalTick)
			for (item in entry.items) {
				val progress = item.progress + advance
				// Past its exit face with somewhere further to go: it belongs to the next segment
				// now, which picks it up in the inbound pass below. An item on its *final* leg has
				// no next segment - it holds at the face mouth until the deposit's sync removes it.
				if (progress >= 1f && item.path.size > 1) continue
				result += if (advance > 0f) item.copy(progress = progress) else item
			}
		}

		for (direction in Direction.entries) {
			val neighbour = entries[pos.relative(direction)] ?: continue
			val advance = advanceOf(neighbour, fractionalTick)
			for (item in neighbour.items) {
				val progress = item.progress + advance
				if (progress < 1f || item.path.size <= 1) continue
				if (item.path.firstOrNull() != pos) continue
				val arrived = item.copy(
					// Carry the overshoot across rather than restarting at 0: the item is already
					// that far into this segment by the time the frame is drawn.
					progress = progress - 1f,
					path = item.path.drop(1),
					// [direction] points from us toward the neighbour, which is exactly the face
					// the item enters through.
					fromDirection = direction,
				)
				if (result.none { sameItem(it, arrived) }) result += arrived
			}
		}

		return result
	}

	/** How far this entry's items have moved since the tick it was sampled on, at that segment's own speed. */
	private fun advanceOf(entry: Entry, fractionalTick: Double): Float {
		val elapsedTicks = (fractionalTick - entry.serverTick).coerceAtLeast(0.0)
		return (elapsedTicks * (1f / BoilerplateConfig.Gameplay.Pipes.ticksPerSegment) * entry.speedMultiplier).toFloat()
	}

	/**
	 * Whether [a] and [b] are the same physical delivery - a predicted arrival against the real one
	 * once its packet lands.
	 *
	 * Compares the resource through [ResourceIdentity] rather than with `==`: `FluidResource` has no
	 * value equality of its own, so a plain comparison would never match two fluids and every fluid
	 * hand-off would draw twice for the moment both packets are in flight.
	 */
	private fun sameItem(a: TravelingItem, b: TravelingItem): Boolean {
		if (a.stack.amount != b.stack.amount || a.path != b.path) return false
		val resourceA = a.stack.resource as? ResourceComponent ?: return false
		val resourceB = b.stack.resource as? ResourceComponent ?: return false
		return ResourceIdentity.of(resourceA) == ResourceIdentity.of(resourceB)
	}

	fun remove(pos: BlockPos) {
		entries.remove(pos)
	}
}

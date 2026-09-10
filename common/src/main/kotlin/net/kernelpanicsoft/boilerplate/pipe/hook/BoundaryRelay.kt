package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * The second leg of a boundary crossing: what the far side delivered into an interface's stock,
 * pulled across and sent on to whoever asked for it.
 *
 * Driven from [MultipartBlockEntity]'s own hook loop for every hook whose type
 * [provides][PipeHookType.providesItems], rather than from any one hook type's `tick` - the
 * mechanism belongs to *being a source*, not to being a provider specifically, so a sync hook and a
 * pattern provider relay on exactly the same terms and a hook type added later gets it for free.
 *
 * A hook with no outstanding [HookHolderState.relays] does no work here at all, which is every hook
 * on a network with no recursive boundary on it.
 *
 * Nothing distinguishes leg 2 from an ordinary pull, which is the point: the interface is the
 * neighbour this hook has always been able to draw from, and its stock is now simply the place the
 * far network was asked to put something. A claim only partly filled keeps its remainder and takes
 * the rest on a later pass.
 */
object BoundaryRelay {
	fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: HookHolderState) {
		// Read once: every access to the property decodes the whole list out of NBT afresh, and
		// this runs for every providing hook on every tick.
		val relays = state.relays
		if (relays.isEmpty()) return
		state.ticksSinceRelay++
		if (state.ticksSinceRelay < BoilerplateConfig.Gameplay.Hooks.requestIntervalTicks) return
		state.ticksSinceRelay = 0

		val now = level.gameTime
		val source = RequestFulfillment.ProviderSource(pos, direction, state)
		val remaining = mutableListOf<RelayClaim>()
		for (claim in relays) {
			// A delivery that never arrived - a jam, a mined interface - or one that has since had
			// time to land. Dropped rather than kept forever, so the request can be made afresh.
			if (now > claim.expiresAtTick) continue
			if (claim.amount <= 0) {
				// Nothing left to carry, only the tail of it still travelling this network. Held
				// until its own deadline so this side keeps counting it as on its way.
				remaining += claim
				continue
			}
			// Unreserved, always - see [RelayClaim] for why a reservation must not cross a seam.
			val moved = RequestFulfillment.fulfillFromProvider(
				level,
				listOf(source),
				ResourceStack(claim.resource, claim.amount),
				claim.deliverTo,
				claim.deliverFace,
			)
			if (moved <= 0) {
				remaining += claim
				continue
			}
			// What was sent on is still spoken for until it has had time to land - see
			// [RelayClaim.settling].
			remaining += claim.copy(
				amount = claim.amount - moved,
				settling = claim.settling + moved,
				expiresAtTick = maxOf(claim.expiresAtTick, now + SETTLE_TICKS),
			)
		}
		if (remaining == relays) return
		// One write, not a clear followed by an append - each persists the whole list.
		relays.setAll(remaining)
		tile.setChanged()
	}

	/**
	 * How long a dispatched claim stays counted as outstanding, in ticks - long enough for the near
	 * leg to actually land.
	 *
	 * Not a guess at a travel time so much as a floor under one: the cost of holding it too long is
	 * that a genuinely lost delivery is re-requested a few seconds late, and the cost of releasing it
	 * too early is asking the far network for a second copy of something already in the pipe.
	 */
	private const val SETTLE_TICKS = 20L * 15
}

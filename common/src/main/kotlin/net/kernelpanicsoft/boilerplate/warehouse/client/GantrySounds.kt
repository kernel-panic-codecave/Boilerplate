package net.kernelpanicsoft.boilerplate.warehouse.client

import dev.architectury.event.events.client.ClientTickEvent
import net.kernelpanicsoft.boilerplate.registry.SoundRegistry
import net.kernelpanicsoft.boilerplate.warehouse.GantryClientCache
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

/**
 * Drives the gantry's three-part motor sound on the client: [SoundRegistry.GantryStart] as it
 * pulls away, [SoundRegistry.GantryLoop] for as long as it runs, [SoundRegistry.GantryStop] as it
 * settles.
 *
 * Minecraft will not chain those for you - a looping [net.minecraft.client.resources.sounds.SoundInstance]
 * is all it offers, so the start and stop are ordinary one-shots fired around it. The three clips
 * are built to splice (see `tools/sfx/synth.py`), which is what lets that read as one continuous
 * machine rather than three sounds in a row.
 *
 * Driven from [GantryClientCache] rather than from a packet of its own. That cache is already fed
 * only while a gantry is actually moving, and dead-reckons to a stop on the final waypoint when the
 * syncs stop coming - so `isMoving` on the extrapolated state is exactly the signal wanted here,
 * with no new traffic and nothing to keep in step with the renderer, which reads the same source.
 */
object GantrySounds {

	private val running = HashMap<BlockPos, GantryLoop>()

	/**
	 * Game time each controller's spin-down will have finished by, so the next run does not start
	 * over the top of it - see [RESTART_QUIET_TICKS].
	 */
	private val quietUntil = HashMap<BlockPos, Double>()

	fun register() {
		ClientTickEvent.CLIENT_POST.register { minecraft -> tick(minecraft) }
	}

	private fun tick(minecraft: Minecraft) {
		val level = minecraft.level
		if (level == null) {
			// Left the world. The sound manager has dropped these already; forgetting them here
			// stops the next world from thinking a gantry it has never seen is still running.
			running.clear()
			quietUntil.clear()
			return
		}
		val now = level.gameTime.toDouble()
		val known = GantryClientCache.positions()

		for (controller in known) {
			val state = GantryClientCache.get(controller, now)
			val moving = state != null && state.isMoving
			val loop = running[controller]
			when {
				// Still inside the previous run's spin-down. A gantry that stops and immediately takes
				// its next job - which is most of them, since the queue hands one straight to the
				// next - would otherwise fire its start clip over a stop clip that has not finished,
				// and the two motors together read as a stutter rather than a machine picking up
				// again. The head moves silently for those few ticks, which is the cheaper artefact:
				// a missing start is a machine already running, a doubled one is a broken machine.
				moving && loop == null && now < (quietUntil[controller] ?: Double.NEGATIVE_INFINITY) -> Unit

				moving && loop == null -> {
					quietUntil.remove(controller)
					val at = state!!.pos
					level.playLocalSound(at.x, at.y, at.z, SoundRegistry.GantryStart, SoundSource.BLOCKS, MOTOR_VOLUME, 1.0f, false)
					GantryLoop(controller, at, delayTicks = START_TICKS).also {
						running[controller] = it
						minecraft.soundManager.play(it)
					}
				}
				!moving && loop != null -> {
					running.remove(controller)
					// Crossfaded, not cut and not merely faded under. The loop is stopped at whatever
					// point in its cycle the gantry happened to halt - never its own boundary - so a
					// hard edge is a click no care in the asset can prevent; and the stop clip opens
					// at full motor level, because it is built to *continue* the loop, so simply
					// starting it over a fading loop is two motors at once for as long as the fade.
					//
					// Both sides move together on a sine/cosine pair rather than straight lines,
					// which is what keeps the total constant: two linear ramps crossing at half
					// amplitude sum to a dip in the middle, audible as the motor thinning out right
					// at the moment it should sound heaviest.
					loop.release()
					state?.pos?.let { at ->
						minecraft.soundManager.play(GantryStop(controller, at))
					}
					quietUntil[controller] = now + RESTART_QUIET_TICKS
				}
			}
		}

		// A controller that left the cache entirely - unloaded, or broken mid-run - never reports
		// "stopped", so its loop would otherwise run forever.
		for (controller in running.keys.filter { it !in known }) running.remove(controller)?.release()
		quietUntil.keys.retainAll(known)
	}

	/**
	 * The motor itself, following the gantry head as it travels.
	 *
	 * Tickable because it moves: an ordinary one-shot is fixed where it was played, which for a
	 * machine that crosses a whole warehouse would leave the sound behind at the controller.
	 */
	private class GantryLoop(private val controller: BlockPos, start: Vec3, delayTicks: Int) : AbstractTickableSoundInstance(
		SoundRegistry.GantryLoop, SoundSource.BLOCKS, RandomSource.create(),
	) {
		private var fadeTicksIn = -1

		init {
			looping = true
			// Held back until the spin-up has finished rather than started alongside it: the two
			// are one continuous machine, and playing them together buries the ramp under a motor
			// already at full speed.
			delay = delayTicks
			volume = MOTOR_VOLUME
			attenuation = SoundInstance.Attenuation.LINEAR
			// Handed in rather than looked up: the caller already has the dead-reckoned position it
			// decided to start on, and asking the cache again from here would need the game time to
			// extrapolate against - which this has no reason to know.
			x = start.x; y = start.y; z = start.z
		}

		/** Begins the fade that [tick] finishes - see the caller for why this is not an outright stop. */
		fun release() {
			if (fadeTicksIn < 0) fadeTicksIn = 0
		}

		override fun tick() {
			val minecraft = Minecraft.getInstance()
			val level = minecraft.level
			if (level == null) {
				stop()
				return
			}
			GantryClientCache.get(controller, level.gameTime.toDouble())?.pos?.let {
				x = it.x; y = it.y; z = it.z
			}
			if (fadeTicksIn >= 0) {
				val progress = fadeTicksIn.toFloat() / FADE_TICKS
				if (progress >= 1f) {
					stop()
					return
				}
				// The falling half of the crossfade - see the caller. Counted the same way up as
				// [GantryStop] counts its own, so the two read the same progress on the same tick:
				// cosine against sine at a matching angle is what holds the total constant, and an
				// offset of even one tick puts a dip where the handover should be seamless.
				volume = MOTOR_VOLUME * cos(progress * PI.toFloat() / 2f)
				fadeTicksIn++
			}
		}
	}

	private const val MOTOR_VOLUME = 0.5f

	/**
	 * The motor spinning down, and the rising half of the crossfade out of [GantryLoop].
	 *
	 * A tickable instance rather than the plain one-shot this could be, for two reasons: it has to
	 * follow the gantry to where it actually stopped, and its volume has to be driven for the first
	 * few ticks so the loop can hand over to it without either a gap or a doubling.
	 */
	private class GantryStop(private val controller: BlockPos, start: Vec3) : AbstractTickableSoundInstance(
		SoundRegistry.GantryStop, SoundSource.BLOCKS, RandomSource.create(),
	) {
		private var ticksIn = 0

		init {
			looping = false
			delay = 0
			// Opens at the level the loop is leaving, not at full - see [GantryLoop.tick].
			volume = 0f
			attenuation = SoundInstance.Attenuation.LINEAR
			x = start.x; y = start.y; z = start.z
		}

		override fun tick() {
			val level = Minecraft.getInstance().level
			if (level == null) {
				stop()
				return
			}
			GantryClientCache.get(controller, level.gameTime.toDouble())?.pos?.let {
				x = it.x; y = it.y; z = it.z
			}
			if (ticksIn <= FADE_TICKS) {
				val progress = ticksIn.toFloat() / FADE_TICKS
				volume = MOTOR_VOLUME * sin(progress * PI.toFloat() / 2f)
				ticksIn++
			}
		}
	}

	/**
	 * How long [GantryLoop] and [GantryStop] take to trade places, in client ticks.
	 *
	 * Short because the stop clip is only seven ticks long: the handover has to be done early in it,
	 * not still running halfway through. Not zero - cutting the loop lands on an arbitrary point of
	 * its waveform and clicks.
	 */
	private const val FADE_TICKS = 2

	/**
	 * How long the spin-up runs before the loop takes over, in client ticks - `gantry_start.ogg`'s
	 * own length, which is what makes the loop begin exactly where that clip was built to end.
	 *
	 * Six ticks, and the clip is exactly 0.30s, so the two agree to the sample. They did not before:
	 * a 0.62s clip against twelve ticks left 0.02s of spin-up still playing under the loop.
	 */
	private const val START_TICKS = 6

	/**
	 * How long a controller stays silent after stopping before it may sound a new run, in client
	 * ticks.
	 *
	 * `gantry_stop.ogg`'s own length plus a breath - the clip is 0.35s, which is seven ticks exactly
	 * (see `gantry_end` in `tools/sfx/synth.py`), and the two extra are what keep the next spin-up
	 * from beginning on the very tick the last one released rather than after it.
	 */
	private const val RESTART_QUIET_TICKS = 9
}

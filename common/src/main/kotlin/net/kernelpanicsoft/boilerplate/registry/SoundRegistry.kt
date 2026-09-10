package net.kernelpanicsoft.boilerplate.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvent

/**
 * Boilerplate's own sounds. Each id here has to match a key in `assets/boilerplate/sounds.json`,
 * which is what names the actual `.ogg` files behind it.
 *
 * The audio is synthesised rather than recorded or sampled - see `tools/sfx/synth.py`, which is
 * checked in beside it. That is not incidental: [GantryLoop] has to tile with no seam, and
 * [GantryStart]/[GantryStop] have to hand off to it without a step in level or a jump in phase.
 * Three separately-sourced clips cannot do that; three renders of one model at one set of
 * parameters can, and the script asserts it.
 */
object SoundRegistry : ADeferredRegistryHolder<SoundEvent>(Boilerplate.MOD, Registries.SOUND_EVENT) {

	/**
	 * A resource entering or leaving a pipe - a short, hollow tube impact.
	 *
	 * Three variants behind one event, chosen at random per play. A sound this short fired this
	 * often reads as a machine gun if it is always the identical waveform; the variants differ in
	 * the tube's fundamental and how fast it dies away.
	 */
	val PipeThunk: SoundEvent by register("pipe.thunk") { event("pipe.thunk") }

	/** The gantry motor engaging: a clutch impact, then spin-up into [GantryLoop]'s own opening phase. */
	val GantryStart: SoundEvent by register("gantry.start") { event("gantry.start") }

	/**
	 * The gantry motor running. **Seamless**, and meant to be played as a looping sound instance
	 * for as long as the gantry is moving, with [GantryStart] and [GantryStop] as one-shots either
	 * side - Minecraft will not chain the three for you.
	 */
	val GantryLoop: SoundEvent by register("gantry.loop") { event("gantry.loop") }

	/** The gantry motor spinning down out of [GantryLoop], and the carriage settling. */
	val GantryStop: SoundEvent by register("gantry.stop") { event("gantry.stop") }

	/**
	 * Variable-range rather than fixed: the attenuation distance comes from whatever plays the
	 * sound, which is what lets a gantry be audible across a warehouse while a pipe thunk stays
	 * local to its own segment.
	 */
	private fun event(id: String): SoundEvent = SoundEvent.createVariableRangeEvent(Boilerplate.MOD % id)
}

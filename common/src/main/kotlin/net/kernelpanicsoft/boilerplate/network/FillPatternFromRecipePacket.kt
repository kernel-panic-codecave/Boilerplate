package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.crafting.PatternKindSerializer
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu
import net.kernelpanicsoft.boilerplate.resource.SResourceStack

/**
 * Client -> server: replaces the whole ghost grid of whichever [PatternTerminalHookMenu] the
 * requesting player has open, and switches its [PatternKind] to match - what a recipe viewer's own
 * transfer button fills a pattern from.
 *
 * One packet rather than the nineteen
 * [SetPatternGhostInputPacket]/[SetPatternGhostOutputPacket]/[SetPatternKindPacket] messages the
 * same edit would otherwise take, because a recipe fill is a single atomic replacement and those
 * nineteen are not: the kind decides how the cells are read, so a cell arriving under the old kind
 * means something different from the same cell under the new one, and a grid half-filled from two
 * recipes is not a state any sequence of these should be able to leave behind.
 *
 * @property kind what the terminal is switched to before the cells are written.
 * @property inputs the whole input grid, blanks included.
 * @property outputs the whole output grid, blanks included - all blank for [PatternKind.CRAFTING].
 */
@Serializable
data class FillPatternFromRecipePacket(
	val kind: @Serializable(with = PatternKindSerializer::class) PatternKind,
	val inputs: List<SResourceStack<*>>,
	val outputs: List<SResourceStack<*>>,
) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.applyRecipeFill(kind, inputs, outputs)
	}
}

package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData

/**
 * Holds up to [SLOT_COUNT] [net.kernelpanicsoft.tubularstorage.crafting.PatternItem] stacks - see
 * [PatternProviderHookType].
 */
class PatternProviderHookState : HookHolderState(PatternProviderHookType.ID) {
	val patterns: ArchieItemStorage by itemField(SLOT_COUNT)

	/** Index into [patterns] the attached target is currently feeding/processing, or `null` if idle - runtime-only, like [net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity.activePattern]; a reload just re-detects it from whatever's still sitting in the target. */
	var activeSlot: Int? = null

	/** The actual [Pattern] carried by each non-blank slot in [patterns], in slot order - what [net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest]/[net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType] search across reachable pattern providers for. */
	fun heldPatterns(): List<Pattern> =
		(0 until patterns.size()).mapNotNull { i -> patterns.get(i).getItem().takeIf { !it.isEmpty }?.let { PatternItemData(it).pattern } }

	companion object {
		const val SLOT_COUNT = 9
	}
}

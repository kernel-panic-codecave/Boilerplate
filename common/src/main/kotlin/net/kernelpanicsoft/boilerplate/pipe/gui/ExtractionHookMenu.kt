package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionDistribution
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for the extraction hook on [tile]'s [direction] face: its filter card and the three settings
 * that decide what one pull does - where it goes, how often it happens, and how much it moves.
 *
 * Reads the hook's own state when the screen opens rather than observing it live, the same way
 * [SortingHookMenu] does and for the same reason: a nested
 * [net.kernelpanicsoft.archie.serialization.NBTHolder] field is not wired into
 * [net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStateManager]. Edits go back through
 * [net.kernelpanicsoft.boilerplate.network.UpdateExtractionConfigPacket]; the card is a real vanilla
 * slot, so vanilla's own container syncing carries it.
 */
class ExtractionHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, ExtractionHookMenu>(GuiRegistry.ExtractionHook, id, inventory, tile) {

	/** [tile]'s own position - exposed since [tile] itself is `protected`, for the config-edit packet. */
	val pos: BlockPos get() = tile.blockPos

	private fun state(): ExtractionHookState? = tile.hooks[direction.name] as? ExtractionHookState

	/** Whether [filter] names what this hook may pull or what it may not. */
	fun filterMode(): FilterMode = state()?.filterMode ?: FilterMode.WHITELIST

	/** How this hook spreads its pulls across the destinations that would accept them. */
	fun distribution(): ExtractionDistribution = state()?.distribution ?: ExtractionDistribution.NEAREST_FIRST

	/** Ticks between pulls - this hook's speed, lower being faster. */
	fun intervalTicks(): Int = state()?.intervalTicks ?: BoilerplateConfig.Gameplay.Hooks.extractionIntervalTicks

	/** How much one pull moves, in authored units, or [ExtractionHookState.KIND_DEFAULT_AMOUNT] for the kind's own batch. */
	fun amount(): Long = state()?.amountAuthored ?: ExtractionHookState.KIND_DEFAULT_AMOUNT

	/**
	 * The largest amount worth offering, in authored units - the same ceiling a filter hook's batch
	 * uses, since both are "how much of one resource crosses in one go" and both are kind-agnostic.
	 */
	fun maxAmount(): Long = net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState.maxBatchSize

	/** A real, vanilla-[net.minecraft.world.inventory.Slot]-backed card slot, exactly as [SortingHookMenu] registers its own. */
	override fun registerSlotHandlers() {
		val state = state() ?: return
		handler("filter", state.filter) { it.item is FilterCardItem }
	}
}

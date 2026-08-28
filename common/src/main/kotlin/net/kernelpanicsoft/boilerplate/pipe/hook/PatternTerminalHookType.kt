package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A [TerminalHookType] with a ghost grid added for authoring [net.kernelpanicsoft.boilerplate.crafting.Pattern]s
 * (see [PatternTerminalHookState]) instead of a real one for instant crafting
 * ([CraftingTerminalHookType]'s own role). Store/Craft tabs and job-queue advancement work
 * identically to a plain terminal - see `docs/design/m4-crafting-automation.md`.
 */
object PatternTerminalHookType : PipeHookType<PatternTerminalHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "pattern_terminal"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - Drives pattern encoding - heavier than a middling hook, lighter than the two below. */
	override val basePressureCost: Long = 3L

	override fun createState(): PatternTerminalHookState = PatternTerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		PatternTerminalHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: PatternTerminalHookState) {
		advanceTerminalJobs(level, pos, direction, tile, state)
	}

	override fun asItem(): Item = ItemRegistry.PatternTerminalHook

	/** Identical to [TerminalHookType.shapesByDirection] - the same wide face plate, just with more menu behind it. */
	override val shapesByDirection: Map<Direction, VoxelShape> get() = TerminalHookType.shapesByDirection
}

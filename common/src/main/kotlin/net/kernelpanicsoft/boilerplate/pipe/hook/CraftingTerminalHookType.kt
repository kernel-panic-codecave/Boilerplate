package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
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
 * A [TerminalHookType] with a real, [CraftingTerminalHookState.grid]-backed crafting grid added -
 * "instant" network-backed crafting (see [CraftingTerminalHookState]), distinct from
 * [PatternProviderHookType]'s slow pattern-driven processing. Store/Craft tabs and job-queue
 * advancement work identically to a plain terminal - see `docs/design/m4-crafting-automation.md`.
 */
object CraftingTerminalHookType : PipeHookType<CraftingTerminalHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "crafting_terminal"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - Drives a Crafting CPU job's own submission/tracking - the heaviest per-tick work among the hooks. */
	override val basePressureCost: Long = 4L

	override fun createState(): CraftingTerminalHookState = CraftingTerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		CraftingTerminalHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: CraftingTerminalHookState) {
		advanceTerminalJobs(level, pos, direction, tile, state)
	}

	override fun asItem(): Item = ItemRegistry.CraftingTerminalHook

	/** Identical to [TerminalHookType.shapesByDirection] - the same wide face plate, just with more menu behind it. */
	override val shapesByDirection: Map<Direction, VoxelShape> get() = TerminalHookType.shapesByDirection
}
